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

import org.apache.axis2.util.threadpool.DefaultThreadFactory;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import javax.jms.JMSException;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import java.net.SocketException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Provide access to cached Subscriptions per proxy to the JMSSender for waiting for the response.
 * Create cached subscriptions to a queueName local to the ESB node + proxy.
 *
 * When the first JMS request asks for a subscription, the handler will
 * 1. create a JMS subscription (@{@link JMSReplySubscription}) for that specific reply queue, and schedule it to run
 *    every X seconds (@SUBSCRIPTION_POLL_INTERVAL).
 * 2. Add a listener to the correlationId of the JMS request, so that the Sender is notified of the response.
 * 3. Put the subscription to a cache for re-use. The cache will expire after X minutes of inactivity on the
 * subscription.
 */
public class JMSReplyHandler {

    private static final Log log;

    private static final long SUBSCRIPTION_POLL_INTERVAL = 1;

    private ScheduledThreadPoolExecutor scheduledThreadPoolExecutor;

    private static JMSReplyHandler jmsReplyHandler;

    private static String servicePort;
    private static String ipAddress;

    static {
        log = LogFactory.getLog(JMSReplyHandler.class);
        jmsReplyHandler = new JMSReplyHandler();

        // Evaluate the IP address at initialization for use when generating a unique reply queue name.
        try {
            ipAddress = org.apache.axis2.util.Utils.getIpAddress().replace(".", "");
        } catch (SocketException e) {
            log.error("Could not resolve the IP address", e);
        }
    }

    public static JMSReplyHandler getInstance() {
        return jmsReplyHandler;
    }

    private JMSReplyHandler() {

        scheduledThreadPoolExecutor = (ScheduledThreadPoolExecutor) Executors.newScheduledThreadPool(10, new
                JMSReplyThreadFactory("jms-reply-handler"));
        scheduledThreadPoolExecutor.setRemoveOnCancelPolicy(true);
    }

    /**
     * Add a new message listener to an already existing / new JMS subscription based on the input Reply Queue.
     * @param uniqueReplyQueueName Modified Reply Destination to be distinct across multiple ESB nodes serving the
     *                             same reply destination.
     * @param initialContext Initial JMS Context
     * @param jmsReplyContainer DataHolder for storing the reply message when received.
     * @param jmsCorrelationId correlation ID of this JMS request.
     * @param connectionFactoryName connection factory to be used for the consumer (in case the cache is empty).
     * @throws NamingException if an error occurs while looking up the reply destination.
     * @throws JMSException if an error occurs while creating the connection / consumer.
     */
    public void addNewReplyToSubscription(String uniqueReplyQueueName, InitialContext initialContext,
                                   JMSReplyContainer jmsReplyContainer, String jmsCorrelationId,
                                   String connectionFactoryName)
            throws JMSException, NamingException {

        JMSReplySubscription jmsReplySubscription = JMSReplySubscriptionCache.getJMSReplySubscriptionCache().get
                (uniqueReplyQueueName);

        if (null == jmsReplySubscription) {
            if (log.isDebugEnabled()) {
                log.debug("Active subscription NOT found for : " + uniqueReplyQueueName);
            }

            jmsReplySubscription = new JMSReplySubscription(uniqueReplyQueueName, initialContext, connectionFactoryName);
            jmsReplySubscription.registerListener(jmsCorrelationId, jmsReplyContainer);

            ScheduledFuture<?> scheduledFuture = scheduledThreadPoolExecutor.scheduleWithFixedDelay
                    (jmsReplySubscription, 0, SUBSCRIPTION_POLL_INTERVAL, TimeUnit.SECONDS);

            jmsReplySubscription.setTaskReference(scheduledFuture);

            JMSReplySubscriptionCache.getJMSReplySubscriptionCache().put(uniqueReplyQueueName, jmsReplySubscription);
        } else {
            if (log.isDebugEnabled()) {
                log.debug("Active subscription already found for : " + uniqueReplyQueueName);
            }
            jmsReplySubscription.registerListener(jmsCorrelationId, jmsReplyContainer);
        }
    }

    /**
     * Remove the listener for the specific JMS request from the active subscription for given queue.
     * @param uniqueReplyQueueName Modified Reply Destination to be distinct across multiple ESB nodes serving the
     *                             same reply destination.
     * @param jmsCorrelationId correlation ID of this JMS request.
     */
    public void stopReplyListener(String uniqueReplyQueueName, String jmsCorrelationId) {

        JMSReplySubscription jmsReplySubscription = JMSReplySubscriptionCache.getJMSReplySubscriptionCache().get
                (uniqueReplyQueueName);

        if (null != jmsReplySubscription) {
            jmsReplySubscription.unregisterListener(jmsCorrelationId);
        }
    }

    /**
     *
     * @param replyQueueName original reply queue name as configured in the proxy / axis2.xml by user.
     * @param servicePath Service path from message context.
     * @param servicePrefix to infer an open port within the ESB node
     * @return a unique queue name
     */
    public static String generateUniqueReplyQueueName(String replyQueueName, String servicePath, String servicePrefix) {

        // if set once, we do not need to re-evaluate the port.
        if (StringUtils.isBlank(servicePort)) {
            servicePort = servicePrefix.split(":")[2];
        }

        String proxyName = retrieveServiceName(servicePath);

        return  replyQueueName + "__" + proxyName + ipAddress + servicePort;
    }

    /**
     * Retrieve service name given the path from message context.
     * @param servicePath (e.g. /services/SMSSenderProxy.SOAP11Endpoint)
     * @return proxy service name
     */
    private static String retrieveServiceName(String servicePath) {

        String serviceName = servicePath.split("/")[2];

        if (serviceName.contains(".")) {
            return serviceName.split(".")[0];
        }
        return serviceName;
    }

    private class JMSReplyThreadFactory implements ThreadFactory {

        private final String name;
        private final AtomicInteger integer = new AtomicInteger(1);

        public JMSReplyThreadFactory(String name) {
            this.name = name;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public Thread newThread(Runnable r) {
            return new Thread(r, name + integer.getAndIncrement());
        }
    }
}
