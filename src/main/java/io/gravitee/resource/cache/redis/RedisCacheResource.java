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

import io.gravitee.common.utils.UUID;
import io.gravitee.gateway.jupiter.api.context.GenericExecutionContext;
import io.gravitee.resource.cache.api.Cache;
import io.gravitee.resource.cache.api.CacheResource;
import io.gravitee.resource.cache.redis.configuration.RedisCacheResourceConfiguration;
import io.vertx.core.Vertx;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Guillaume CUSNIEUX (guillaume.cusnieux at graviteesource.com)
 * @author GraviteeSource Team
 */
public class RedisCacheResource extends CacheResource<RedisCacheResourceConfiguration> {

    private final Logger logger = LoggerFactory.getLogger(RedisCacheResource.class);
    private RedisClient redisClient;
    private String cacheId;

    @Override
    protected void doStart() throws Exception {
        super.doStart();
        logger.debug("Create redis cache manager");
        RedisCacheResourceConfiguration configuration = configuration();
        this.cacheId = configuration.getName() + RedisCacheResourceConfiguration.KEY_SEPARATOR + UUID.random().toString();

        // TODO - Kamiel - 17/10/2022: Clarify how to get Vertx
        RedisConnectionFactory redisConnectionFactory = new RedisConnectionFactory(Vertx.vertx(), configuration);
        this.redisClient = redisConnectionFactory.getRedisClient();
    }

    @Override
    public String keySeparator() {
        return RedisCacheResourceConfiguration.KEY_SEPARATOR;
    }

    @Override
    public Cache getCache(GenericExecutionContext ctx) {
        return getCache(ctx.getAttributes());
    }

    @Override
    public Cache getCache(io.gravitee.gateway.api.ExecutionContext executionContext) {
        return getCache(executionContext.getAttributes());
    }

    private Cache getCache(Map<String, Object> contextAttributes) {
        return new RedisCache(
            this.redisClient,
            this.cacheId,
            contextAttributes,
            (int) configuration().getTimeToLiveSeconds(),
            configuration().isReleaseCache()
        );
    }
}
