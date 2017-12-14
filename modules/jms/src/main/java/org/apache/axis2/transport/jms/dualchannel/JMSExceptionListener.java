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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import javax.jms.ExceptionListener;
import javax.jms.JMSException;

/**
 * Custom exception listener to handle failure of a JMS subscriber connection in the Dual-Channel scenario.
 */
class JMSExceptionListener implements ExceptionListener {

    private static final Log log = LogFactory.getLog(JMSExceptionListener.class);

    private String uniqueReplyQueueName;

    JMSExceptionListener(String uniqueReplyQueueName) {
        this.uniqueReplyQueueName = uniqueReplyQueueName;
    }

    @Override
    public void onException(JMSException e) {

        synchronized (uniqueReplyQueueName.intern()) {
            log.error("Cache will be cleared due to JMSException for subscription on : " + uniqueReplyQueueName, e);

            JMSReplySubscription jmsReplySubscription = JMSReplySubscriptionCache.getJMSReplySubscriptionCache()
                    .getAndRemove(uniqueReplyQueueName);

            try {
                jmsReplySubscription.cleanupTask();
            } catch (JMSException e1) {
                log.error("Error while closing JMSReplySubscription for key : " + uniqueReplyQueueName, e1);
            }

            log.error("Cache has been cleared due to a JMSException for subscription on : " + uniqueReplyQueueName, e);
        }
    }
}
