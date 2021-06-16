/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.resource.cache.redis;

import io.gravitee.resource.cache.api.Cache;
import io.gravitee.resource.cache.api.Element;
import io.gravitee.resource.cache.redis.configuration.RedisCacheResourceConfiguration;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * @author Guillaume CUSNIEUX (guillaume.cusnieux at graviteesource.com)
 * @author GraviteeSource Team
 */
public class RedisDelegate implements Cache {

    private final Logger logger = LoggerFactory.getLogger(RedisDelegate.class);

    private final RedisTemplate<Object, Object> redisTemplate;
    private final RedisCacheResourceConfiguration configuration;

    public RedisDelegate(RedisTemplate<Object, Object> redisTemplate, RedisCacheResourceConfiguration configuration) {
        this.redisTemplate = redisTemplate;
        this.configuration = configuration;
    }

    @Override
    public String getName() {
        return this.configuration.getName();
    }

    @Override
    public Object getNativeCache() {
        return redisTemplate;
    }

    @Override
    public Element get(Object key) {
        logger.debug("Find in cache {}", key);
        try {
            Object o = redisTemplate.opsForValue().get(key);
            logger.debug("Found {}", o);
            return o == null
                ? null
                : new Element() {
                    @Override
                    public Object key() {
                        return key;
                    }

                    @Override
                    public Object value() {
                        return o;
                    }
                };
        } catch (Throwable e) {
            logger.error("Cannot get element in cache", e);
        }
        logger.debug("Not found {}", key);
        return null;
    }

    @Override
    public void put(Element element) {
        logger.debug("Put in cache {}", element.key());
        try {
            long ttl = this.configuration.getTimeToLiveSeconds();
            if ((ttl == 0 && element.timeToLive() > 0) || (ttl > 0 && element.timeToLive() > 0 && ttl > element.timeToLive())) {
                ttl = element.timeToLive();
            }
            redisTemplate.opsForValue().set(element.key(), element.value(), ttl, TimeUnit.SECONDS);
        } catch (Throwable e) {
            logger.error("Cannot put element in cache", e);
        }
    }

    @Override
    public void evict(Object o) {
        redisTemplate.delete(o);
    }

    @Override
    public void clear() {
        redisTemplate.getConnectionFactory().getConnection().flushAll();
    }
}
