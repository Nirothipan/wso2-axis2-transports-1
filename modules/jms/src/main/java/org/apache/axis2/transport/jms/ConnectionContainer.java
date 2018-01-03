/*
 * Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
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

package org.apache.axis2.transport.jms;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.MessageProducer;
import javax.jms.Session;
import java.util.Hashtable;

/**
 * Class to contain connection, session and producer/consumer objects. This is required to support different cache
 * levels within the correct connection object.
 */
class ConnectionContainer {

    private static final Log log = LogFactory.getLog(ConnectionContainer.class);

    private volatile Connection connection;
    private volatile Session session;
    private volatile MessageProducer messageProducer;

    private int cacheLevel = JMSConstants.CACHE_CONNECTION;
    private Hashtable<String, String> parameters;
    private ConnectionFactory connectionFactory;
    private String connectionFactoryName;

    ConnectionContainer(int cacheLevel, Hashtable<String, String> parameters, ConnectionFactory
            connectionFactory, String connectionFactoryName) {
        this.cacheLevel = cacheLevel;
        this.parameters = parameters;
        this.connectionFactory = connectionFactory;
        this.connectionFactoryName = connectionFactoryName;
    }

    /**
     * Get cached connection if cacheLevel = connection. If not, return a new connection.
     * @return JMS Connection
     * @throws JMSException
     */
    public Connection getConnection() throws JMSException {
        if (cacheLevel > JMSConstants.CACHE_NONE) {
            if (null == connection) {
                synchronized (this) {
                    if (null == connection) {
                        connection = createConnection();
                    }
                }
            }
            return connection;
        } else {
            return createConnection();
        }
    }

    /**
     * Get cached session if cacheLevel = session. If not, return a new session.
     * @return JMS Session
     */
    public Session getSession() {

        if (cacheLevel > JMSConstants.CACHE_CONNECTION) {
            if (null == session) {
                synchronized (this) {
                    if (null == session) {
                        session = createSession();
                    }
                }
            }
            return session;
        } else {
            return createSession();
        }
    }

    /**
     * Get cached message producer if cacheLevel > session. If not, return a new message producer.
     * @param destination destination to publish the message.
     * @return JMS message producer
     */
    MessageProducer getMessageProducer(Destination destination) {
        if (cacheLevel > JMSConstants.CACHE_SESSION) {
            if (null == messageProducer) {
                synchronized (this) {
                    if (null == messageProducer) {
                        messageProducer = createProducer(destination);
                    }
                }
            }
            return messageProducer;
        } else {
            return createProducer(destination);
        }
    }

    /**
     * Cleanup to ensure that all objects are closed.
     */
    public synchronized void close() {
        try {
            if (null != messageProducer)
                messageProducer.close();
            if (null != session) {
                session.close();
            }
            if (null != connection) {
                connection.close();
            }
        } catch (JMSException e) {
            log.error("Error when trying to close connection container in Connection Factory : " +
                    connectionFactoryName, e);
        } finally {
            messageProducer = null;
            session = null;
            connection = null;
        }
    }

    /**
     * Create a new JMS connection based on parameters specified in the Connection Factory as configured from
     * axis2.xml or inline proxies.
     * Create a new Connection
     * @return a new Connection
     */
    private Connection createConnection() {

        Connection connection = null;
        try {
            connection = JMSUtils.createConnection(
                    connectionFactory,
                    parameters.get(JMSConstants.PARAM_JMS_USERNAME),
                    parameters.get(JMSConstants.PARAM_JMS_PASSWORD),
                    JMSUtils.jmsSpecVersion(parameters), JMSUtils.isQueue(parameters, connectionFactoryName),
                    JMSUtils.isDurable(parameters), JMSUtils.getClientId(parameters),
                    JMSUtils.isSharedSubscription(parameters));

            if (log.isDebugEnabled()) {
                log.debug("New JMS Connection from JMS CF : " + connectionFactoryName + " created");
            }

        } catch (JMSException e) {
            JMSUtils.handleException("Error acquiring a Connection from the JMS CF : " + connectionFactoryName +
                    " using properties : " + parameters, e);
        }
        return connection;
    }

    /**
     * Create a new JMS session based on parameters specified in the Connection Factory as configured from
     * axis2.xml or inline proxies.
     * @return A new Session
     */
    private Session createSession() {
        try {
            if (log.isDebugEnabled()) {
                log.debug("Creating a new JMS Session from JMS CF : " + connectionFactoryName);
            }
            return JMSUtils.createSession(
                    getConnection(), JMSUtils.isSessionTransacted(parameters), Session.AUTO_ACKNOWLEDGE,
                    JMSUtils.jmsSpecVersion(parameters),
                    JMSUtils.isQueue(parameters, connectionFactoryName));

        } catch (JMSException e) {
            try {
                return JMSUtils.createSession(getConnection(), JMSUtils.isSessionTransacted(parameters), Session
                                .AUTO_ACKNOWLEDGE,
                        JMSUtils.jmsSpecVersion(parameters), JMSUtils.isQueue(parameters, connectionFactoryName));
            } catch (JMSException e1) {
                JMSUtils.handleException("Error creating JMS session from JMS CF : " + connectionFactoryName, e);
            }
        }
        return null;
    }

    /**
     * Create a new JMS message producer based on parameters specified in the Connection Factory as configured from
     * axis2.xml or inline proxies.
     * @param destination Destination to be used
     * @return a new MessageProducer
     */
    private MessageProducer createProducer(Destination destination) {
        try {
            if (log.isDebugEnabled()) {
                log.debug("Creating a new JMS MessageProducer from JMS CF : " + connectionFactoryName);
            }

            return JMSUtils.createProducer(
                    getSession(), destination, JMSUtils.isQueue(parameters, connectionFactoryName),
                    JMSUtils.jmsSpecVersion(parameters));

        } catch (JMSException e) {
            JMSUtils.handleException("Error creating JMS producer from JMS CF : " + connectionFactoryName,e);
        }
        return null;
    }
}
