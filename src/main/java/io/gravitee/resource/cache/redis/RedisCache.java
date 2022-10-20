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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gravitee.gateway.api.ExecutionContext;
import io.gravitee.resource.cache.api.Cache;
import io.gravitee.resource.cache.api.Element;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.Request;
import io.vertx.redis.client.Response;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Guillaume CUSNIEUX (guillaume.cusnieux at graviteesource.com)
 * @author GraviteeSource Team
 */
public class RedisCache implements Cache {

    private final Logger logger = LoggerFactory.getLogger(RedisCache.class);

    private final RedisClient redisClient;
    private final RedisSerializer serializer = new RedisSerializer();
    private final int timeToLiveSeconds;
    private final Map<String, Object> contextAttributes;
    private final boolean releaseCache;
    private final String name;

    public RedisCache(
        RedisClient redisClient,
        String name,
        Map<String, Object> contextAttributes,
        int timeToLiveSeconds,
        boolean releaseCache
    ) {
        this.redisClient = redisClient;
        this.name = name;
        this.contextAttributes = contextAttributes;
        this.timeToLiveSeconds = timeToLiveSeconds;
        this.releaseCache = releaseCache;
    }

    @Override
    public String getName() {
        return this.name;
    }

    @Override
    public Object getNativeCache() {
        return this;
    }

    @Override
    public Element get(Object key) {
        logger.debug("Find in cache {}", key);
        if (key == null) {
            return null;
        }

        Request req = Request.cmd(Command.GET).arg(buildKey(key));
        Response result = redisClient.getConnection().send(req).result();

        if (result != null) {
            Object value = this.serializer.deserialize(result.toBytes());

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

        logger.debug("Not found {}", key);
        return null;
    }

    @Override
    public void put(Element element) {
        logger.debug("Put in cache {}", element.key());
        int ttl = this.timeToLiveSeconds;
        if ((ttl == 0 && element.timeToLive() > 0) || (ttl > 0 && element.timeToLive() > 0 && ttl > element.timeToLive())) {
            ttl = element.timeToLive();
        }

        redisClient
            .getConnection()
            .send(Request.cmd(Command.SET).arg(buildKey(element.key())).arg(this.serializer.serialize(element.value())).arg("EX").arg(String.valueOf(ttl)))
            .onFailure(t -> logger.error("Unable to put element {} in the cache. {}", element.key(), t.getMessage()))
            .result();
    }

    private String buildKey(Object key) {
        String allKey = this.getName() + key;
        if (this.releaseCache) {
            allKey += ":" + contextAttributes.get(ExecutionContext.ATTR_API_DEPLOYED_AT);
        }
        return new String(this.serializer.serialize(allKey));
    }

    @Override
    public void evict(Object o) {
        this.redisClient.getConnection()
            .send(Request.cmd(Command.DEL).arg(buildKey(o)))
            .onFailure(t -> logger.error("Unable to put element {} in the cache. {}", o, t.getMessage()))
            .result();
    }

    @Override
    public void clear() {
        this.redisClient.getConnection()
            .send(Request.cmd(Command.KEYS).arg(String.format("\"%s*", this.getName())))
            .onSuccess(event -> event.stream().iterator().forEachRemaining(response -> this.evict(response.toString())));
    }

    private static class RedisSerializer {

        private final ObjectMapper mapper = new ObjectMapper();

        private byte[] serialize(Object src) throws SerializationException {
            if (src == null) {
                return new byte[0];
            }

            try {
                return mapper.writeValueAsBytes(src);
            } catch (JsonProcessingException e) {
                throw new SerializationException("Could not write JSON: " + e.getMessage(), e);
            }
        }

        private Object deserialize(byte[] src) throws SerializationException {
            if (src == null || src.length == 0) {
                return null;
            }

            try {
                return mapper.readValue(src, Object.class);
            } catch (Exception ex) {
                throw new SerializationException("Could not read JSON: " + ex.getMessage(), ex);
            }
        }
    }
}
