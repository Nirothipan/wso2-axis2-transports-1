/*
 * Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.axis2.transport.jms.dualchannel;

import org.apache.axis2.transport.jms.JMSConstants;
import org.apache.axis2.transport.jms.JMSUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.Session;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * Contains following additional information.
 * subscriptionID metadata - NodeID + Proxy
 * jms MessageConsumer reference.
 * Mappings to different requests and correlation ID.
 */
class JMSReplySubscription implements Runnable {

    private static final Log log = LogFactory.getLog(JMSReplySubscription.class);

    private MessageConsumer messageConsumer;

    private ConcurrentHashMap<String, JMSReplyContainer> listeningRequests;

    private ScheduledFuture taskReference;

    JMSReplySubscription(String uniqueReplyQueueName, InitialContext initialContext, String
            connectionFactoryName)
            throws NamingException, JMSException {

        listeningRequests = new ConcurrentHashMap<>();

        ConnectionFactory connectionFactory = JMSUtils.lookup(initialContext, ConnectionFactory.class, connectionFactoryName);

        Destination replyDestination = JMSUtils.lookup(initialContext, Destination.class, uniqueReplyQueueName);

        Connection connection = JMSUtils.createConnection(
                connectionFactory, null, null, JMSConstants.JMS_SPEC_VERSION_1_1, true, false, null, false);

        connection.setExceptionListener(new JMSExceptionListener(uniqueReplyQueueName));

        Session session = JMSUtils.createSession(connection, false, Session.AUTO_ACKNOWLEDGE, JMSConstants
                .JMS_SPEC_VERSION_1_1, true);

        messageConsumer = JMSUtils.createConsumer(session, replyDestination, "");

    }

    /**
     * After sending a JMS message with a ReplyTo header, the JMSSender can register a listener here to be notified
     * of the response.
     * @param jmsCorrelationId
     * @param replyContainer
     */
    void registerListener(String jmsCorrelationId, JMSReplyContainer replyContainer) {
        listeningRequests.put(jmsCorrelationId, replyContainer);
    }

    /**
     * Remove listener to a specific JMS request.
     * @param jmsCorrelationId
     */
    void unregisterListener(String jmsCorrelationId) {
        listeningRequests.remove(jmsCorrelationId);
    }

    @Override
    public void run() {
        try {
            Message message = messageConsumer.receive(1000);

            while (null != message) {

                String jmsCorrelationId = message.getJMSCorrelationID();

                if (listeningRequests.containsKey(jmsCorrelationId)) {
                    listeningRequests.get(jmsCorrelationId).setMessage(message);
                    listeningRequests.get(jmsCorrelationId).getCountDownLatch().countDown();
                }

                message = messageConsumer.receive(1000);
            }

        } catch (JMSException e) {
            log.error("Error while receiving message : ");
        }
    }


    void setTaskReference(ScheduledFuture taskReference) {
        this.taskReference = taskReference;
    }

    void cleanupTask() {
        // Announce closing of this subscription to any listeners still waiting for a reply.
        for (Map.Entry<String,JMSReplyContainer> request : listeningRequests.entrySet()) {
            request.getValue().getCountDownLatch().countDown();
        }
        taskReference.cancel(true);
    }

}
