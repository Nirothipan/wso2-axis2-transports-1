/*
* Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package org.apache.axis2.transport.jms;

import junit.framework.Assert;
import junit.framework.TestCase;
import org.apache.axis2.context.ConfigurationContext;
import org.apache.axis2.context.MessageContext;
import org.apache.axis2.description.TransportOutDescription;
import org.apache.axis2.engine.AxisConfiguration;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.powermock.reflect.Whitebox;

import javax.jms.Session;
import javax.transaction.HeuristicMixedException;
import javax.transaction.HeuristicRollbackException;
import javax.transaction.RollbackException;
import javax.transaction.Synchronization;
import javax.transaction.SystemException;
import javax.transaction.Transaction;
import javax.transaction.xa.XAResource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;

@RunWith(PowerMockRunner.class)
@PrepareForTest(JMSSender.class)
@PowerMockIgnore("javax.management.*")
public class JMSSenderTestCase extends TestCase {

    /**
     * Test case for EI-1244.
     * test transport.jms.TransactionCommand parameter in transport url when sending the message.
     * This will verify the fixes which prevent possible OOM issue when publishing messages to a broker using jms.
     *
     * @throws Exception
     */
    public void testTransactionCommandParameter() throws Exception {
        JMSSender jmsSender = PowerMockito.spy(new JMSSender());
        JMSOutTransportInfo jmsOutTransportInfo = Mockito.mock(JMSOutTransportInfo.class);
        JMSMessageSender jmsMessageSender = Mockito.mock(JMSMessageSender.class);
        Session session = Mockito.mock(Session.class);

        Mockito.doReturn(session).when(jmsMessageSender).getSession();
        PowerMockito.whenNew(JMSOutTransportInfo.class).withArguments(any(String.class))
                .thenReturn(jmsOutTransportInfo);
        Mockito.doReturn(jmsMessageSender).when(jmsOutTransportInfo).createJMSSender(any(MessageContext.class));
        PowerMockito.doNothing()
                .when(jmsSender, "sendOverJMS", ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                        ArgumentMatchers.any(), ArgumentMatchers.any());

        jmsSender.init(new ConfigurationContext(new AxisConfiguration()), new TransportOutDescription("jms"));
        MessageContext messageContext = new MessageContext();
        //append the transport.jms.TransactionCommand
        String targetAddress = "jms:/SimpleStockQuoteService?transport.jms.ConnectionFactoryJNDIName="
                + "QueueConnectionFactory&transport.jms.TransactionCommand=begin"
                + "&java.naming.factory.initial=org.apache.activemq.jndi.ActiveMQInitialContextFactory";
        Transaction transaction = new TestJMSTransaction();
        messageContext.setProperty(JMSConstants.JMS_XA_TRANSACTION, transaction);

        jmsSender.sendMessage(messageContext, targetAddress, null);
        Map<Transaction, ArrayList<JMSMessageSender>> jmsMessageSenderMap = Whitebox
                .getInternalState(JMSSender.class, "jmsMessageSenderMap");
        Assert.assertEquals("Transaction not added to map", 1, jmsMessageSenderMap.size());
        List senderList = jmsMessageSenderMap.get(transaction);
        Assert.assertNotNull("List is null", senderList);
        Assert.assertEquals("List is empty", 1, senderList.size());
    }

    /**
     * Test class which implement javax.Transaction for test transport.jms.TransactionCommand.
     */
    private class TestJMSTransaction implements Transaction {

        @Override
        public void commit()
                throws HeuristicMixedException, HeuristicRollbackException, RollbackException, SecurityException,
                SystemException {

        }

        @Override
        public boolean delistResource(XAResource xaResource, int i) throws IllegalStateException, SystemException {
            return false;
        }

        @Override
        public boolean enlistResource(XAResource xaResource)
                throws IllegalStateException, RollbackException, SystemException {
            return false;
        }

        @Override
        public int getStatus() throws SystemException {
            return 0;
        }

        @Override
        public void registerSynchronization(Synchronization synchronization)
                throws IllegalStateException, RollbackException, SystemException {

        }

        @Override
        public void rollback() throws IllegalStateException, SystemException {

        }

        @Override
        public void setRollbackOnly() throws IllegalStateException, SystemException {

        }
    }

}