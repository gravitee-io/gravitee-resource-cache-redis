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

import static java.lang.Boolean.TRUE;

import io.gravitee.resource.cache.redis.configuration.HostAndPort;
import io.gravitee.resource.cache.redis.configuration.RedisCacheResourceConfiguration;
import io.vertx.core.Vertx;
import io.vertx.redis.client.RedisClientType;
import io.vertx.redis.client.RedisOptions;
import io.vertx.redis.client.RedisRole;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public class RedisConnectionFactory {

    private final Logger logger = LoggerFactory.getLogger(RedisConnectionFactory.class);

    private final RedisCacheResourceConfiguration configuration;

    private final Vertx vertx;

    public RedisConnectionFactory(Vertx vertx, RedisCacheResourceConfiguration configuration) {
        this.vertx = vertx;
        this.configuration = configuration;
    }

    public RedisClient getRedisClient() {
        final RedisOptions options = new RedisOptions();

        Boolean hasSentinelEnabled = configuration.getSentinel().isEnabled();
        if (hasSentinelEnabled.equals(TRUE) || (hasSentinelEnabled == null && configuration.isSentinelMode())) {
            // Sentinels + Redis master / replicas
            logger.debug("Redis repository configured to use Sentinel connection");

            options.setType(RedisClientType.SENTINEL);
            List<HostAndPort> sentinelNodes = configuration.getSentinel().getNodes();
            sentinelNodes.forEach(hostAndPort -> options.addConnectionString(hostAndPort.toConnectionString()));
            options.setMasterName(configuration.getSentinel().getMasterId()).setRole(RedisRole.MASTER);

            // Sentinel Password
            options.setPassword(configuration.getSentinel().getPassword());
        } else {
            // Standalone Redis
            logger.debug("Redis repository configured to use standalone connection");

            options.setType(RedisClientType.STANDALONE);
            options.setConnectionString(configuration.getStandalone().toConnectionString());
        }

        // SSL
        boolean ssl = configuration.isUseSsl();
        if (ssl) {
            options.getNetClientOptions().setSsl(true).setTrustAll(true);
        }

        // Connection Pool
        options.setMaxPoolSize(configuration.getMaxTotal());
        options.setMaxWaitingHandlers(32);
        options.setPoolCleanerInterval(10000);

        return new RedisClient(vertx, options);
    }

}
