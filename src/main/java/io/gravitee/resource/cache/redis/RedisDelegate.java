/*
 * Copyright © 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.resource.cache.redis;

import io.gravitee.gateway.api.ExecutionContext;
import io.gravitee.resource.cache.api.Cache;
import io.gravitee.resource.cache.api.Element;
import java.time.Duration;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.serializer.RedisSerializer;

/**
 * @author Guillaume CUSNIEUX (guillaume.cusnieux at graviteesource.com)
 * @author GraviteeSource Team
 */
@Slf4j
@RequiredArgsConstructor
public class RedisDelegate implements Cache {

    private final org.springframework.cache.Cache cache;
    private final Map<String, Object> contextAttributes;
    private final RedisSerializer<String> serializer;
    private final int timeToLiveSeconds;
    private final boolean releaseCache;

    @Override
    public String getName() {
        return this.cache.getName();
    }

    @Override
    public Object getNativeCache() {
        return cache.getNativeCache();
    }

    @Override
    public Element get(Object key) {
        log.debug("Find in cache {}", key);
        try {
            RedisCacheWriter redisCacheWriter = (RedisCacheWriter) this.getNativeCache();
            byte[] bytes = redisCacheWriter.get(this.getName(), buildKey(key));
            if (bytes != null) {
                Object value = this.serializer.deserialize(bytes);
                return new Element() {
                    @Override
                    public Object key() {
                        return key;
                    }

                    @Override
                    public Object value() {
                        return value;
                    }
                };
            }
            return null;
        } catch (Exception e) {
            log.error("Cannot get element in cache", e);
        }
        log.debug("Not found {}", key);
        return null;
    }

    @Override
    public void put(Element element) {
        log.debug("Put in cache {}", element.key());
        try {
            int ttl = this.timeToLiveSeconds;
            if ((ttl == 0 && element.timeToLive() > 0) || (ttl > 0 && element.timeToLive() > 0 && ttl > element.timeToLive())) {
                ttl = element.timeToLive();
            }
            RedisCacheWriter redisCacheWriter = (RedisCacheWriter) this.getNativeCache();

            redisCacheWriter.put(
                getName(),
                buildKey(element.key()),
                this.serializer.serialize((String) element.value()),
                Duration.ofSeconds(ttl)
            );
        } catch (Exception e) {
            log.error("Cannot put element in cache", e);
        }
    }

    private byte[] buildKey(Object key) {
        String allKey = this.getName() + key;
        if (this.releaseCache) {
            allKey += ":" + contextAttributes.get(ExecutionContext.ATTR_API_DEPLOYED_AT);
        }
        return this.serializer.serialize(allKey);
    }

    @Override
    public void evict(Object o) {
        cache.evict(o);
    }

    @Override
    public void clear() {
        cache.clear();
    }
}
