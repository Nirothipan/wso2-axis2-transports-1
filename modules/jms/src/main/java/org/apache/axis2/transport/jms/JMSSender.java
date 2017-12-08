/*
* Copyright 2004,2005 The Apache Software Foundation.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*      http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package org.apache.axis2.transport.jms;

import org.apache.axiom.om.OMElement;
import org.apache.axiom.om.OMNode;
import org.apache.axiom.om.OMOutputFormat;
import org.apache.axiom.om.OMText;
import org.apache.axis2.AxisFault;
import org.apache.axis2.Constants;
import org.apache.axis2.context.ConfigurationContext;
import org.apache.axis2.context.MessageContext;
import org.apache.axis2.description.TransportOutDescription;
import org.apache.axis2.transport.MessageFormatter;
import org.apache.axis2.transport.OutTransportInfo;
import org.apache.axis2.transport.base.AbstractTransportSender;
import org.apache.axis2.transport.base.BaseConstants;
import org.apache.axis2.transport.base.BaseUtils;
import org.apache.axis2.transport.base.ManagementSupport;
import org.apache.axis2.transport.http.HTTPConstants;
import org.apache.axis2.transport.jms.dualchannel.JMSReplyContainer;
import org.apache.axis2.transport.jms.dualchannel.JMSReplyHandler;
import org.apache.axis2.transport.jms.iowrappers.BytesMessageOutputStream;
import org.apache.axis2.util.MessageProcessorSelector;
import org.apache.commons.io.output.WriterOutputStream;
import org.apache.commons.lang.StringUtils;

import javax.activation.DataHandler;
import javax.jms.*;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.transaction.SystemException;
import javax.transaction.Transaction;
import javax.transaction.TransactionManager;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.nio.charset.UnsupportedCharsetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The TransportSender for JMS
 */
public class JMSSender extends AbstractTransportSender implements ManagementSupport {

    public static final String TRANSPORT_NAME = Constants.TRANSPORT_JMS;

    /**
     * Needed to read the service url from the message context properties.
     * Sample value e.g. - "/services/SMSSenderProxy"
     */
    public static final String SERVICE_PREFIX = "SERVICE_PREFIX";

    private static Map<Transaction, ArrayList<JMSMessageSender>> jmsMessageSenderMap = new HashMap<>();
    /** The JMS connection factory manager to be used when sending messages out */
    private JMSConnectionFactoryManager connFacManager;

    /**
     * Initialize the transport sender by reading pre-defined connection factories for
     * outgoing messages.
     *
     * @param cfgCtx the configuration context
     * @param transportOut the transport sender definition from axis2.xml
     * @throws AxisFault on error
     */
    @Override
    public void init(ConfigurationContext cfgCtx, TransportOutDescription transportOut) throws AxisFault {
        super.init(cfgCtx, transportOut);
        connFacManager = new JMSConnectionFactoryManager(transportOut);
        log.info("JMS Transport Sender initialized...");
    }
    
    @Override
    public void stop() {
        
        // clean up any shared JMS resources in this sender's connection factories
        connFacManager.stop();
        
        super.stop();
    }

    /**
     * Get corresponding JMS connection factory defined within the transport sender for the
     * transport-out information - usually constructed from a targetEPR
     *
     * @param trpInfo the transport-out information
     * @return the corresponding JMS connection factory, if any
     */
    private JMSConnectionFactory getJMSConnectionFactory(JMSOutTransportInfo trpInfo) {
        Map<String,String> props = trpInfo.getProperties();
        if (trpInfo.getProperties() != null) {
            String jmsConnectionFactoryName = props.get(JMSConstants.PARAM_JMS_CONFAC);
            if (jmsConnectionFactoryName != null) {
                return connFacManager.getJMSConnectionFactory(jmsConnectionFactoryName);
            } else {
                JMSConnectionFactory fac = connFacManager.getJMSConnectionFactory(props);
                if (fac == null) {
                    fac = connFacManager.getJMSConnectionFactory(JMSConstants.DEFAULT_CONFAC_NAME);
                }
                return  fac;
            }
        } else {
            return null;
        }
    }

    /**
     * Performs the actual sending of the JMS message
     */
    @Override
    public void sendMessage(MessageContext msgCtx, String targetAddress,
        OutTransportInfo outTransportInfo) throws AxisFault {

        JMSOutTransportInfo jmsOut = null;
        JMSMessageSender messageSender = null;
        JMSConnectionFactory jmsConnectionFactory = null;

        if (targetAddress != null) {

            jmsOut = new JMSOutTransportInfo(targetAddress);
            // do we have a definition for a connection factory to use for this address?
            jmsConnectionFactory = getJMSConnectionFactory(jmsOut);
            
            if (jmsConnectionFactory != null) {
                messageSender = new JMSMessageSender(jmsConnectionFactory, targetAddress);

            } else {
                try {
                    messageSender = jmsOut.createJMSSender(msgCtx);

                    // messageSender only needed to add if the scenario involved with XA transaction. It can be
                    // identified by looking at target url's transport.jms.TransactionCommand parameter.
                    if ((targetAddress.contains(JMSConstants.JMS_TRANSACTION_COMMAND))) {
                        Transaction transaction = (Transaction) msgCtx.getProperty(JMSConstants.JMS_XA_TRANSACTION);
                        if (jmsMessageSenderMap.get(transaction) == null) {
                            ArrayList list = new ArrayList();
                            list.add(messageSender);
                            jmsMessageSenderMap.put(transaction, list);
                        } else {
                            ArrayList list = jmsMessageSenderMap.get(transaction);
                            list.add(messageSender);
                            jmsMessageSenderMap.put(transaction, list);
                        }
                    }

                } catch (JMSException e) {
                    Transaction transaction = null;
                    try {
                        if (msgCtx.getProperty(JMSConstants.JMS_XA_TRANSACTION_MANAGER) != null) {
                            transaction =
                                    ((TransactionManager) msgCtx.getProperty(JMSConstants.JMS_XA_TRANSACTION_MANAGER))
                                            .getTransaction();
                            rollbackXATransaction(transaction);
                        }
                    } catch (SystemException e1) {
                        handleException("Error occurred during obtaining  transaction", e1);
                    }
                    handleException("Unable to create a JMSMessageSender for : " + outTransportInfo, e);
                }
            }

        } else if (outTransportInfo != null && outTransportInfo instanceof JMSOutTransportInfo) {

            jmsOut = (JMSOutTransportInfo) outTransportInfo;
            try {
                messageSender = jmsOut.createJMSSender(msgCtx);
            } catch (JMSException e) {
                handleException("Unable to create a JMSMessageSender for : " + outTransportInfo, e);
            }
        }

        // The message property to be used to send the content type is determined by
        // the out transport info, i.e. either from the EPR if we are sending a request,
        // or, if we are sending a response, from the configuration of the service that
        // received the request). The property name can be overridden by a message
        // context property.
        String contentTypeProperty =
            (String) msgCtx.getProperty(JMSConstants.CONTENT_TYPE_PROPERTY_PARAM);
        if (contentTypeProperty == null) {
            contentTypeProperty = jmsOut.getContentTypeProperty();
        }

        // Fix for ESBJAVA-3687, retrieve JMS transport property transport.jms.MessagePropertyHyphens and set this
        // into the msgCtx
        String hyphenSupport = JMSConstants.DEFAULT_HYPHEN_SUPPORT;
        if (jmsOut.getProperties() != null && jmsOut.getProperties().get(JMSConstants.PARAM_JMS_HYPHEN_MODE) != null) {
            if (jmsOut.getProperties().get(JMSConstants.PARAM_JMS_HYPHEN_MODE).equals(JMSConstants.HYPHEN_MODE_REPLACE)) {
                hyphenSupport = JMSConstants.HYPHEN_MODE_REPLACE;
            } else if (jmsOut.getProperties().get(JMSConstants.PARAM_JMS_HYPHEN_MODE).equals(JMSConstants.HYPHEN_MODE_DELETE)) {
                hyphenSupport = JMSConstants.HYPHEN_MODE_DELETE;
            }
        }
        msgCtx.setProperty(JMSConstants.PARAM_JMS_HYPHEN_MODE, hyphenSupport);

        // need to synchronize as Sessions are not thread safe
        synchronized (messageSender.getSession()) {
            try {
                sendOverJMS(msgCtx, messageSender, contentTypeProperty, jmsConnectionFactory, jmsOut);
            } finally {
                if (msgCtx.getProperty(JMSConstants.JMS_XA_TRANSACTION_MANAGER) == null) {
                    messageSender.close();
                }
            }
        }
    }

    /**
     * Perform actual sending of the JMS message
     */
    private void sendOverJMS(MessageContext msgCtx, JMSMessageSender messageSender,
        String contentTypeProperty, JMSConnectionFactory jmsConnectionFactory,
        JMSOutTransportInfo jmsOut) throws AxisFault {

        // convert the axis message context into a JMS Message that we can send over JMS
        Message message = null;
        String correlationId = null;

        // These variables are evaluated before sending the message, and are required after, to start a consumer for
        // the response (for Dual channel scenario).
        String uniqueReplyQueueName = null;
        InitialContext initialContextForReplySubscription = null;
        String connectionFactoryName = null;

        try {
            message = createJMSMessage(msgCtx, messageSender.getSession(), contentTypeProperty);
        } catch (JMSException e) {
            Transaction transaction = null;
            try {
                transaction = ((TransactionManager) msgCtx.getProperty(JMSConstants.JMS_XA_TRANSACTION_MANAGER)).getTransaction();
                rollbackXATransaction(transaction);
            } catch (SystemException e1) {
                handleException("Error occurred during obtaining  transaction", e1);
            }
            handleException("Error creating a JMS message from the message context", e);
        }

        // should we wait for a synchronous response on this same thread?
        boolean waitForResponse = waitForSynchronousResponse(msgCtx);

        // Set the unique reply destination into the message before sending.
        if (waitForResponse) {
            try {

                Hashtable<String,String> jndiProperties = null;
                String proxyServicePath = msgCtx.getProperty(Constants.Configuration.TRANSPORT_IN_URL).toString();

                if (!StringUtils.isBlank(jmsOut.getTargetEPR())  && BaseUtils.getEPRProperties(jmsOut.getTargetEPR())
                        .containsKey(JMSConstants.PARAM_REPLY_DESTINATION)) {
                    // Endpoint is configured to use a sender connection factory from jndi.properties file
                    // based on inline URL.
                    // We give priority to the inline URL over any cached JMS connection factories.
                    jndiProperties = BaseUtils.getEPRProperties(jmsOut.getTargetEPR());

                } else if (null != jmsConnectionFactory) {
                    // Endpoint is configured to use an explicitly configured sender connection factory from axis2.xml.
                    jndiProperties = jmsConnectionFactory.getParameters();
                } else {
                    throw new JMSException("Unable to locate JMS connection parameters from endpoint at service : "
                            + proxyServicePath);
                }

                connectionFactoryName = jndiProperties.get(JMSConstants.PARAM_CONFAC_JNDI_NAME);

                uniqueReplyQueueName = JMSReplyHandler.generateUniqueReplyQueueName(
                        jndiProperties.get(JMSConstants.PARAM_REPLY_DESTINATION), proxyServicePath,
                        msgCtx.getProperty(SERVICE_PREFIX).toString());

                // We need to create a mock JNDI initial context here to inject the unique reply destination and
                // retrieve a JMS Destination object.
                initialContextForReplySubscription = createContextForReplySubscription(jndiProperties, uniqueReplyQueueName);

                Destination replyDestination = JMSUtils.lookup(initialContextForReplySubscription, Destination.class,
                        uniqueReplyQueueName);

                message.setJMSReplyTo(replyDestination);

            } catch (NamingException | JMSException e) {
                Transaction transaction = null;
                try {
                    transaction = ((TransactionManager) msgCtx.getProperty(JMSConstants.JMS_XA_TRANSACTION_MANAGER)).getTransaction();
                    rollbackXATransaction(transaction);
                } catch (SystemException e1) {
                    handleException("Error occurred during obtaining  transaction", e1);
                }
                handleException("Error setting the JMSReplyTo Header", e);
            }
        }

        try {

            messageSender.send(message, msgCtx);
            Transaction transaction = (Transaction) msgCtx.getProperty(JMSConstants.JMS_XA_TRANSACTION);
            if (msgCtx.getTo().toString().contains("transport.jms.TransactionCommand=end")) {
                commitXATransaction(transaction);
            } else if (msgCtx.getTo().toString().contains("transport.jms.TransactionCommand=rollback")) {
                rollbackXATransaction(transaction);
            }
            metrics.incrementMessagesSent(msgCtx);

        } catch (AxisJMSException e) {
            metrics.incrementFaultsSending();
            Transaction transaction = null;
            try {
                transaction = ((TransactionManager) msgCtx.getProperty(JMSConstants.JMS_XA_TRANSACTION_MANAGER)).getTransaction();
                rollbackXATransaction(transaction);
            } catch (SystemException e1) {
                handleException("Error occurred during obtaining  transaction", e1);
            }
            handleException("Error sending JMS message", e);
        }

        try {
            metrics.incrementBytesSent(msgCtx, JMSUtils.getMessageSize(message));
        } catch (JMSException e) {
            log.warn("Error reading JMS message size to update transport metrics", e);
        }

        // if we are expecting a synchronous response back for the message sent out
        if (waitForResponse) {
            try {
                String jmsCorrelationID = message.getJMSCorrelationID();
                if (jmsCorrelationID != null && jmsCorrelationID.length() > 0) {
                    correlationId = jmsCorrelationID;
                } else {
                    correlationId = message.getJMSMessageID();
                }
            } catch(JMSException ignore) {}

            // We assume here that the response uses the same message property to specify the content type of the
            // message.
            waitForResponseAndProcess(msgCtx, correlationId, contentTypeProperty, initialContextForReplySubscription,
                    uniqueReplyQueueName, connectionFactoryName);
        }
    }

    /**
     * Create a Consumer for the reply destination and wait for the response JMS message
     * synchronously. If a message arrives within the specified time interval, process it
     * through Axis2 engine.
     * @param msgCtx the outgoing message for which we are expecting the response
     * @param contentTypeProperty the message property used to determine the content type
     *                            of the response message
     * @param correlationId Correlation ID used to relate the message to its original request.
     * @param initialContext JNDI context required to initialize a consumer.
     * @param uniqueReplyQueueName Modified reply destination to be distinct across multiple ESB nodes serving the
     *                             same reply destination.
     * @throws AxisFault on error
     */
    private void waitForResponseAndProcess(MessageContext msgCtx, String correlationId,
            String contentTypeProperty, InitialContext initialContext, String uniqueReplyQueueName, String
                                                   connectionFactoryName) throws AxisFault {

        try {

            // how long are we willing to wait for the sync response
            long timeout = JMSConstants.DEFAULT_JMS_TIMEOUT;
            String waitReply = (String) msgCtx.getProperty(JMSConstants.JMS_WAIT_REPLY);
            if (waitReply != null) {
                timeout = Long.valueOf(waitReply);
            }

            if (log.isDebugEnabled()) {
                log.debug("Waiting for a maximum of " + timeout +
                        "ms for a response message to destination : " + uniqueReplyQueueName +
                        " with JMS correlation ID : " + correlationId);
            }

            CountDownLatch replyLatch = new CountDownLatch(1);

            // Create an object to pass to the actual JMS subscription expecting a notification when the matching
            // message arrives.
            JMSReplyContainer jmsReplyContainer = new JMSReplyContainer(replyLatch);
            JMSReplyHandler.getInstance().addNewReplyToSubscription(uniqueReplyQueueName,
                    initialContext, jmsReplyContainer, correlationId, connectionFactoryName);

            try {
                replyLatch.await(timeout, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                log.error("Thread interrupted while waiting for message with correlation ID : " + correlationId);
                Thread.currentThread().interrupt();
            }

            Message responseMessage = jmsReplyContainer.getMessage();
            JMSReplyHandler.getInstance().stopReplyListener(uniqueReplyQueueName, correlationId);

            if (null != responseMessage) {
                // update transport level metrics
                metrics.incrementMessagesReceived();
                try {
                    metrics.incrementBytesReceived(JMSUtils.getMessageSize(jmsReplyContainer.getMessage()));
                } catch (JMSException e) {
                    log.warn("Error reading JMS message size to update transport metrics", e);
                }

                try {
                    processSyncResponse(msgCtx, jmsReplyContainer.getMessage(), contentTypeProperty);
                    metrics.incrementMessagesReceived();
                } catch (AxisFault e) {
                    metrics.incrementFaultsReceiving();
                    throw e;
                }

            } else {
                metrics.incrementFaultsReceiving();
                handleException("Did not receive a JMS response within " + timeout +
                        " ms to destination : " + uniqueReplyQueueName +
                        " with JMS correlation ID : " + correlationId);
            }

        } catch (JMSException | NamingException e) {
            metrics.incrementFaultsReceiving();
            handleException("Error creating a consumer, or receiving a synchronous reply " +
                "for outgoing MessageContext ID : " + msgCtx.getMessageID() +
                " and reply Destination : " + uniqueReplyQueueName, e);
        }
    }

    /**
     * Create a JMS Message from the given MessageContext and using the given
     * session
     *
     * @param msgContext the MessageContext
     * @param session    the JMS session
     * @param contentTypeProperty the message property to be used to store the
     *                            content type
     * @return a JMS message from the context and session
     * @throws JMSException on exception
     * @throws AxisFault on exception
     */
    private Message createJMSMessage(MessageContext msgContext, Session session,
            String contentTypeProperty) throws JMSException, AxisFault {

        Message message = null;
        String msgType = getProperty(msgContext, JMSConstants.JMS_MESSAGE_TYPE);

        // check the first element of the SOAP body, do we have content wrapped using the
        // default wrapper elements for binary (BaseConstants.DEFAULT_BINARY_WRAPPER) or
        // text (BaseConstants.DEFAULT_TEXT_WRAPPER) ? If so, do not create SOAP messages
        // for JMS but just get the payload in its native format
        String jmsPayloadType = guessMessageType(msgContext);

        if (jmsPayloadType == null) {

            OMOutputFormat format = BaseUtils.getOMOutputFormat(msgContext);
            MessageFormatter messageFormatter = null;
            try {
                messageFormatter = MessageProcessorSelector.getMessageFormatter(msgContext);
            } catch (AxisFault axisFault) {
                throw new JMSException("Unable to get the message formatter to use");
            }

            String contentType = messageFormatter.getContentType(
                msgContext, format, msgContext.getSoapAction());

            boolean useBytesMessage =
                msgType != null && JMSConstants.JMS_BYTE_MESSAGE.equals(msgType) ||
                    contentType.indexOf(HTTPConstants.HEADER_ACCEPT_MULTIPART_RELATED) > -1;

            OutputStream out;
            StringWriter sw;
            if (useBytesMessage) {
                BytesMessage bytesMsg = session.createBytesMessage();
                sw = null;
                out = new BytesMessageOutputStream(bytesMsg);
                message = bytesMsg;
            } else {
                sw = new StringWriter();
                try {
                    out = new WriterOutputStream(sw, format.getCharSetEncoding());
                } catch (UnsupportedCharsetException ex) {
                    handleException("Unsupported encoding " + format.getCharSetEncoding(), ex);
                    return null;
                }
            }
            
            try {
                messageFormatter.writeTo(msgContext, format, out, true);
                out.close();
            } catch (IOException e) {
                Transaction transaction = null;
                try {
                    transaction = ((TransactionManager) msgContext.getProperty(JMSConstants.JMS_XA_TRANSACTION_MANAGER)).getTransaction();
                    rollbackXATransaction(transaction);
                } catch (SystemException e1) {
                    handleException("Error occurred during obtaining  transaction", e1);
                }
                handleException("IO Error while creating BytesMessage", e);
            }

            if (!useBytesMessage) {
                TextMessage txtMsg = session.createTextMessage();
                txtMsg.setText(sw.toString());
                message = txtMsg;
            }
            
            if (contentTypeProperty != null) {
                message.setStringProperty(contentTypeProperty, contentType);
            }

        } else if (JMSConstants.JMS_BYTE_MESSAGE.equals(jmsPayloadType)) {
            message = session.createBytesMessage();
            BytesMessage bytesMsg = (BytesMessage) message;
            OMElement wrapper = msgContext.getEnvelope().getBody().
                getFirstChildWithName(BaseConstants.DEFAULT_BINARY_WRAPPER);
            OMNode omNode = wrapper.getFirstOMChild();
            if (omNode != null && omNode instanceof OMText) {
                Object dh = ((OMText) omNode).getDataHandler();
                if (dh != null && dh instanceof DataHandler) {
                    try {
                        ((DataHandler) dh).writeTo(new BytesMessageOutputStream(bytesMsg));
                    } catch (IOException e) {
                        Transaction transaction = null;
                        try {
                            transaction = ((TransactionManager) msgContext.getProperty(JMSConstants.JMS_XA_TRANSACTION_MANAGER)).getTransaction();
                            rollbackXATransaction(transaction);
                        } catch (SystemException e1) {
                            handleException("Error occurred during obtaining  transaction", e1);
                        }
                        handleException("Error serializing binary content of element : " +
                            BaseConstants.DEFAULT_BINARY_WRAPPER, e);
                    }
                }
            }

        } else if (JMSConstants.JMS_TEXT_MESSAGE.equals(jmsPayloadType)) {
            message = session.createTextMessage();
            TextMessage txtMsg = (TextMessage) message;
            txtMsg.setText(msgContext.getEnvelope().getBody().
                getFirstChildWithName(BaseConstants.DEFAULT_TEXT_WRAPPER).getText());
        } else if (JMSConstants.JMS_MAP_MESSAGE.equalsIgnoreCase(jmsPayloadType)){
            message = session.createMapMessage();
            JMSUtils.convertXMLtoJMSMap(msgContext.getEnvelope().getBody().getFirstChildWithName(
                    JMSConstants.JMS_MAP_QNAME),(MapMessage)message);
        }

        // set the JMS correlation ID if specified
        String correlationId = getProperty(msgContext, JMSConstants.JMS_COORELATION_ID);
        if (correlationId == null && msgContext.getRelatesTo() != null) {
            correlationId = msgContext.getRelatesTo().getValue();
        }

        if (correlationId != null) {
            message.setJMSCorrelationID(correlationId);
        }

        if (msgContext.isServerSide()) {
            // set SOAP Action as a property on the JMS message
            setProperty(message, msgContext, BaseConstants.SOAPACTION);
        } else {
            String action = msgContext.getOptions().getAction();
            if (action != null) {
                message.setStringProperty(BaseConstants.SOAPACTION, action);
            }
        }

        JMSUtils.setTransportHeaders(msgContext, message);
        return message;
    }

    /**
     * Guess the message type to use for JMS looking at the message contexts' envelope
     * @param msgContext the message context
     * @return JMSConstants.JMS_BYTE_MESSAGE or JMSConstants.JMS_TEXT_MESSAGE or null
     */
    private String guessMessageType(MessageContext msgContext) {
        OMElement firstChild = msgContext.getEnvelope().getBody().getFirstElement();
        if (firstChild != null) {
            if (BaseConstants.DEFAULT_BINARY_WRAPPER.equals(firstChild.getQName())) {
                return JMSConstants.JMS_BYTE_MESSAGE;
            } else if (BaseConstants.DEFAULT_TEXT_WRAPPER.equals(firstChild.getQName())) {
                return JMSConstants.JMS_TEXT_MESSAGE;
            } else if (JMSConstants.JMS_MAP_QNAME.equals(firstChild.getQName())){
                return  JMSConstants.JMS_MAP_MESSAGE;
            }
        }
        return null;
    }

    /**
     * Creates an Axis MessageContext for the received JMS message and
     * sets up the transports and various properties
     *
     * @param outMsgCtx the outgoing message for which we are expecting the response
     * @param message the JMS response message received
     * @param contentTypeProperty the message property used to determine the content type
     *                            of the response message
     * @throws AxisFault on error
     */
    private void processSyncResponse(MessageContext outMsgCtx, Message message,
            String contentTypeProperty) throws AxisFault {

        MessageContext responseMsgCtx = createResponseMessageContext(outMsgCtx);

        // load any transport headers from received message
        JMSUtils.loadTransportHeaders(message, responseMsgCtx);

        String contentType = contentTypeProperty == null ? null
                : JMSUtils.getProperty(message, contentTypeProperty);

        try {
            JMSUtils.setSOAPEnvelope(message, responseMsgCtx, contentType);
        } catch (JMSException ex) {
            throw AxisFault.makeFault(ex);
        }

        handleIncomingMessage(responseMsgCtx, JMSUtils.getTransportHeaders(message, responseMsgCtx),
                              JMSUtils.getProperty(message, BaseConstants.SOAPACTION), contentType);
    }

    private void setProperty(Message message, MessageContext msgCtx, String key) {

        String value = getProperty(msgCtx, key);
        if (value != null) {
            try {
                message.setStringProperty(key, value);
            } catch (JMSException e) {
                log.warn("Couldn't set message property : " + key + " = " + value, e);
            }
        }
    }

    private String getProperty(MessageContext mc, String key) {
        return (String) mc.getProperty(key);
    }
    
    public void clearActiveConnections(){
    	log.error("Not Implemented.");
    }

    private void commitXATransaction(Transaction transaction) throws AxisFault {
        ArrayList<JMSMessageSender> msgSenderList = (ArrayList) jmsMessageSenderMap.get(transaction);
        if (msgSenderList.size() > 0) {
            for (JMSMessageSender msgSender : msgSenderList) {
                try {
                    msgSender.getJmsXaResource().end(msgSender.getJmsXAXid(), XAResource.TMSUCCESS);
                    msgSender.getJmsXaResource().prepare(msgSender.getJmsXAXid());
                    msgSender.getJmsXaResource().commit(msgSender.getJmsXAXid(), false);
                    msgSender.close();
                } catch (XAException e) {
                    handleException("Error occurred during rolling back transaction", e);
                    msgSender.closeOnException();
                }

            }
            jmsMessageSenderMap.remove(transaction);
        }
    }

    private void rollbackXATransaction(Transaction transaction) throws AxisFault {
        ArrayList<JMSMessageSender> msgSenderList = (ArrayList) jmsMessageSenderMap.get(transaction);
        if (msgSenderList.size() > 0) {
            for (JMSMessageSender msgSender : msgSenderList) {
                try {
                    msgSender.getJmsXaResource().end(msgSender.getJmsXAXid(), XAResource.TMSUCCESS);
                    msgSender.getJmsXaResource().prepare(msgSender.getJmsXAXid());
                    msgSender.getJmsXaResource().commit(msgSender.getJmsXAXid(), false);
                    msgSender.close();
                } catch (XAException e) {
                    handleException("Error occurred during rolling back transaction", e);
                    msgSender.closeOnException();
                }
            }
            jmsMessageSenderMap.remove(transaction);
        }
    }

    /**
     * Create a Mock JNDI Initial Context in order to generate a Destination object for the JMS Reply To Header of a
     * message.
     * @param jndiProperties JMS configurations needed to initialize a context.
     * @param uniqueReplyQueueName Modified Reply Destination to be distinct across multiple ESB nodes serving the
     *                             same reply destination.
     * @return @{@link InitialContext}
     * @throws NamingException if an issue occurs while creating the context.
     */
    private InitialContext createContextForReplySubscription(Hashtable<String, String> jndiProperties, String
            uniqueReplyQueueName) throws NamingException {

        Properties jmsProperties = new Properties();

        for (Map.Entry<String, String> param : jndiProperties.entrySet()) {
            jmsProperties.put(param.getKey(), param.getValue());
        }

        if (!jmsProperties.containsKey(uniqueReplyQueueName)) {
            jmsProperties.put(JMSConstants.QUEUE_PREFIX + uniqueReplyQueueName, uniqueReplyQueueName);
        }

        return new InitialContext(jmsProperties);
    }
}
