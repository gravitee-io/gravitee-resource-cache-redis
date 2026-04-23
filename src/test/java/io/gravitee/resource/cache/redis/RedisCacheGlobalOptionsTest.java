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

import static org.assertj.core.api.Assertions.assertThat;

import io.gravitee.plugin.configurations.redis.RedisClientOptions;
import java.util.Map;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * Guards the exact property keys read by {@link RedisCacheGlobalOptions}. A typo in any
 * {@code resources.cacheRedis.*} key would otherwise be silently invisible: the class
 * falls back to its hard-coded defaults, operators set a value in {@code gravitee.yml},
 * and the gateway would keep running on the old default with no error.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class RedisCacheGlobalOptionsTest {

    @Test
    void should_use_upstream_defaults_when_no_properties_are_set() {
        var options = new RedisCacheGlobalOptions(new StandardEnvironment());

        assertThat(options.getMaxPoolSize()).isEqualTo(RedisClientOptions.DEFAULT_MAX_POOL_SIZE);
        assertThat(options.getMaxPoolWaiting()).isEqualTo(RedisClientOptions.DEFAULT_MAX_POOL_WAITING);
        assertThat(options.getPoolCleanerInterval()).isEqualTo(RedisClientOptions.DEFAULT_POOL_CLEANER_INTERVAL);
        assertThat(options.getPoolRecycleTimeout()).isEqualTo(RedisClientOptions.DEFAULT_POOL_RECYCLE_TIMEOUT);
        assertThat(options.getMaxWaitingHandlers()).isEqualTo(RedisClientOptions.DEFAULT_MAX_WAITING_HANDLERS);
        assertThat(options.getConnectTimeout()).isEqualTo(RedisClientOptions.DEFAULT_CONNECT_TIMEOUT);
    }

    @Test
    void should_read_every_property_from_environment_using_documented_keys() {
        var env = environmentWith(
            Map.of(
                "resources.cacheRedis.maxPoolSize",
                42,
                "resources.cacheRedis.maxPoolWaiting",
                43,
                "resources.cacheRedis.poolCleanerInterval",
                44,
                "resources.cacheRedis.poolRecycleTimeout",
                45,
                "resources.cacheRedis.maxWaitingHandlers",
                46,
                "resources.cacheRedis.connectTimeout",
                47
            )
        );

        var options = new RedisCacheGlobalOptions(env);

        assertThat(options.getMaxPoolSize()).isEqualTo(42);
        assertThat(options.getMaxPoolWaiting()).isEqualTo(43);
        assertThat(options.getPoolCleanerInterval()).isEqualTo(44);
        assertThat(options.getPoolRecycleTimeout()).isEqualTo(45);
        assertThat(options.getMaxWaitingHandlers()).isEqualTo(46);
        assertThat(options.getConnectTimeout()).isEqualTo(47);
    }

    private static StandardEnvironment environmentWith(Map<String, Object> properties) {
        var env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test", properties));
        return env;
    }
}
