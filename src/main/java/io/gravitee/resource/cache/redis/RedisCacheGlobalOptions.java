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

import lombok.Getter;
import org.springframework.core.env.Environment;

/**
 * Gateway-wide Redis cache client settings, sourced from {@code gravitee.yml}
 * under the {@code resources.cacheRedis.*} prefix.
 *
 * <p>These fields are connection-pool and timeout settings that must be uniform
 * across every {@code cache-redis} resource targeting the same Redis endpoint,
 * because a single shared Vert.x {@link io.vertx.redis.client.Redis} client is
 * used for all of them (see {@link SharedRedisClientRegistry}) and Vert.x does
 * not support runtime pool resize. Placing them in {@code gravitee.yml} instead
 * of per-resource JSON means they really are global — there is no UI field that
 * looks editable but is silently ignored.
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
        this.maxPoolSize = environment.getProperty(PREFIX + "maxPoolSize", Integer.class, 6);
        this.maxPoolWaiting = environment.getProperty(PREFIX + "maxPoolWaiting", Integer.class, 1024);
        this.poolCleanerInterval = environment.getProperty(PREFIX + "poolCleanerInterval", Integer.class, 30000);
        this.poolRecycleTimeout = environment.getProperty(PREFIX + "poolRecycleTimeout", Integer.class, 180000);
        this.maxWaitingHandlers = environment.getProperty(PREFIX + "maxWaitingHandlers", Integer.class, 1024);
        this.connectTimeout = environment.getProperty(PREFIX + "connectTimeout", Integer.class, 2000);
    }
}
