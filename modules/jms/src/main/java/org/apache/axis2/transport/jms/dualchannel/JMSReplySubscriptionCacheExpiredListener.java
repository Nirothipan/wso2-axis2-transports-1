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

import javax.cache.event.CacheEntryEvent;
import javax.cache.event.CacheEntryExpiredListener;
import javax.cache.event.CacheEntryListenerException;
import javax.jms.JMSException;

/**
 * Class used to properly close an active subscription once the cache expiration is triggered.
 * @param <K> String uniqueReplyQueueName
 * @param <V> @{@link JMSReplySubscription}
 */
class JMSReplySubscriptionCacheExpiredListener<K, V> implements CacheEntryExpiredListener<K, V> {

    private static final Log log = LogFactory.getLog(JMSReplySubscriptionCacheExpiredListener.class);

    @Override
    public void entryExpired(CacheEntryEvent<? extends K, ? extends V> cacheEntryEvent) throws CacheEntryListenerException {

        if (cacheEntryEvent.getValue() instanceof JMSReplySubscription) {
            log.info("Clearing JMS subscription for queue : " + cacheEntryEvent.getKey());
            try {
                ((JMSReplySubscription) cacheEntryEvent.getValue()).cleanupTask();
            } catch (JMSException e) {
                throw new CacheEntryListenerException("Error while clearing JMSReplySubscriptionCache for key : " +
                        cacheEntryEvent.getKey(), e);
            }

        } else {
            log.warn("Expired entry is not a JMSReplySubscription for key : " + cacheEntryEvent.getKey());
        }
    }
}
