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

import javax.cache.Cache;
import javax.cache.CacheConfiguration;
import javax.cache.CacheManager;
import javax.cache.Caching;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Class to act as a single reference point for the Cache containing JMS subscriptions listening for dual channel
 * replies.
 */
class JMSReplySubscriptionCache {

    private static final int CACHE_EXPIRATION_INTERVAL = 900; //seconds

    /**
     * Is set to true if the cache is already initialized.
     */
    private static AtomicBoolean isCacheInitialized = new AtomicBoolean(false);

    private static final Log log = LogFactory.getLog(JMSReplySubscriptionCache.class);

    /**
     * Cache Name
     */
    private static final String CACHE_KEY = "JMSReplySubscriptionCache";

    /**
     * Name of CacheManager holding the cache
     */
    private static final String CACHE_MANAGER_KEY = CACHE_KEY + "Manager";

    /**
     * Listener to handle removal/expiration of a cached entry
     */
    private final static JMSReplySubscriptionCacheExpiredListener<String, JMSReplySubscription> entryExpiredListener = new
            JMSReplySubscriptionCacheExpiredListener<>();

    /**
     * Get the cache which holds all sessions created for publishing to topics using this mediator.
     *
     * @return Cache with key PublisherCache
     */
    static Cache<String, JMSReplySubscription> getJMSReplySubscriptionCache() {

        if (isCacheInitialized.get()) {
            return Caching.getCacheManagerFactory().getCacheManager(CACHE_MANAGER_KEY).getCache(CACHE_KEY);
        } else {

            String cacheName = CACHE_KEY;

            if (log.isDebugEnabled()) {
                log.debug("Using cacheName : " + cacheName);
            }

            CacheManager cacheManager = Caching.getCacheManagerFactory().getCacheManager(CACHE_MANAGER_KEY);
            isCacheInitialized.getAndSet(true);

            Cache<String, JMSReplySubscription> cache = cacheManager.<String, JMSReplySubscription>createCacheBuilder(cacheName)
                    .setExpiry(CacheConfiguration.ExpiryType.MODIFIED,
                            new CacheConfiguration.Duration(TimeUnit.SECONDS, CACHE_EXPIRATION_INTERVAL))
                    .setExpiry(CacheConfiguration.ExpiryType.ACCESSED,
                            new CacheConfiguration.Duration(TimeUnit.SECONDS, CACHE_EXPIRATION_INTERVAL))
                    .setStoreByValue(false).build();

            cache.registerCacheEntryListener(entryExpiredListener);

            return cache;
        }
    }
}
