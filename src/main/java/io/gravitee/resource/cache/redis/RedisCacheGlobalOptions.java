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

import io.gravitee.plugin.configurations.redis.RedisClientOptions;
import lombok.Getter;
import org.springframework.core.env.Environment;

/**
 * Gateway-wide Redis cache client settings, sourced from {@code gravitee.yml}
 * under the {@code resources.cacheRedis.*} prefix.
 *
 * <p>These fields are connection-pool and timeout settings that must be uniform
 * across every {@code cache-redis} resource targeting the same Redis endpoint.
 * The shared {@link io.gravitee.node.vertx.client.redis.VertxRedisClientFactory}
 * dedups by connection tuple; Vert.x Redis does not support runtime pool resize.
 * Placing the pool/timeout settings in {@code gravitee.yml} instead of per-resource
 * JSON means they really are global — there is no UI field that looks editable but
 * is silently ignored.
 *
 * <p>Example {@code gravitee.yml}:
 * <pre>
 * resources:
 *   cacheRedis:
 *     maxPoolSize: 60
 *     maxPoolWaiting: 1024
 *     poolCleanerInterval: 30000
 *     poolRecycleTimeout: 180000
 *     maxWaitingHandlers: 1024
 *     connectTimeout: 2000
 * </pre>
 */
@Getter
public final class RedisCacheGlobalOptions {

    private static final String PREFIX = "resources.cacheRedis.";

    private final int maxPoolSize;
    private final int maxPoolWaiting;
    private final int poolCleanerInterval;
    private final int poolRecycleTimeout;
    private final int maxWaitingHandlers;
    private final int connectTimeout;

    public RedisCacheGlobalOptions(Environment environment) {
        this.maxPoolSize = environment.getProperty(PREFIX + "maxPoolSize", Integer.class, RedisClientOptions.DEFAULT_MAX_POOL_SIZE);
        this.maxPoolWaiting = environment.getProperty(
            PREFIX + "maxPoolWaiting",
            Integer.class,
            RedisClientOptions.DEFAULT_MAX_POOL_WAITING
        );
        this.poolCleanerInterval = environment.getProperty(
            PREFIX + "poolCleanerInterval",
            Integer.class,
            RedisClientOptions.DEFAULT_POOL_CLEANER_INTERVAL
        );
        this.poolRecycleTimeout = environment.getProperty(
            PREFIX + "poolRecycleTimeout",
            Integer.class,
            RedisClientOptions.DEFAULT_POOL_RECYCLE_TIMEOUT
        );
        this.maxWaitingHandlers = environment.getProperty(
            PREFIX + "maxWaitingHandlers",
            Integer.class,
            RedisClientOptions.DEFAULT_MAX_WAITING_HANDLERS
        );
        this.connectTimeout = environment.getProperty(PREFIX + "connectTimeout", Integer.class, RedisClientOptions.DEFAULT_CONNECT_TIMEOUT);
    }
}
