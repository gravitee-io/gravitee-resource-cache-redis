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
import io.gravitee.gateway.reactive.api.context.DeploymentContext;
import io.gravitee.gateway.reactive.api.context.GenericExecutionContext;
import io.gravitee.gateway.reactive.api.context.base.BaseExecutionContext;
import io.gravitee.resource.cache.api.Cache;
import io.gravitee.resource.cache.api.CacheResource;
import io.gravitee.resource.cache.redis.configuration.HostAndPort;
import io.gravitee.resource.cache.redis.configuration.RedisCacheResourceConfiguration;
import io.gravitee.resource.cache.redis.configuration.RedisCacheResourceConfigurationEvaluator;
import io.lettuce.core.api.StatefulConnection;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import lombok.Setter;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * @author Guillaume CUSNIEUX (guillaume.cusnieux at graviteesource.com)
 * @author GraviteeSource Team
 */
public class RedisCacheResource extends CacheResource<RedisCacheResourceConfiguration> {

    private final Logger logger = LoggerFactory.getLogger(RedisCacheResource.class);
    private final StringRedisSerializer stringSerializer = new StringRedisSerializer();
    private RedisCacheManager redisCacheManager;

    @Inject
    @Setter
    private DeploymentContext deploymentContext;

    private RedisCacheResourceConfiguration configuration;

    @Override
    public RedisCacheResourceConfiguration configuration() {
        if (configuration == null) {
            return super.configuration();
        }
        return configuration;
    }

    @Override
    protected void doStart() throws Exception {
        super.doStart();
        logger.debug("Create redis cache manager");

        configuration = new RedisCacheResourceConfigurationEvaluator(configuration()).evalNow(deploymentContext);

        try {
            RedisCacheConfiguration conf = RedisCacheConfiguration
                .defaultCacheConfig()
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(stringSerializer))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(stringSerializer))
                .entryTtl(Duration.ofSeconds(configuration().getTimeToLiveSeconds()));

            this.redisCacheManager = RedisCacheManager.builder(getConnectionFactory()).cacheDefaults(conf).build();
        } catch (Exception e) {
            logger.error("Cannot create redis cache manager", e);
        }
    }

    @Override
    public String keySeparator() {
        return ":";
    }

    /**
     * Gets a cache
     * @param ctx the context
     * @return a cache
     * @deprecated use {@link #getCache(BaseExecutionContext)} instead
     */
    @Override
    @Deprecated(since = "3.0", forRemoval = true)
    public Cache getCache(GenericExecutionContext ctx) {
        return getCache((BaseExecutionContext) ctx);
    }

    @Override
    public Cache getCache(BaseExecutionContext ctx) {
        return getCache(ctx.getAttributes());
    }

    @Override
    public Cache getCache(ExecutionContext executionContext) {
        return getCache(executionContext.getAttributes());
    }

    private Cache getCache(Map<String, Object> contextAttributes) {
        return new RedisDelegate(
            this.redisCacheManager.getCache("gravitee:"),
            contextAttributes,
            stringSerializer,
            (int) configuration().getTimeToLiveSeconds(),
            configuration().isReleaseCache()
        );
    }

    private LettucePoolingClientConfiguration buildLettuceClientConfiguration() {
        final LettucePoolingClientConfiguration.LettucePoolingClientConfigurationBuilder builder =
            LettucePoolingClientConfiguration.builder();
        builder.commandTimeout(Duration.ofMillis(configuration().getTimeout()));
        if (configuration().isUseSsl()) {
            builder.useSsl();
        }
        GenericObjectPoolConfig<StatefulConnection<?, ?>> poolConfig = new GenericObjectPoolConfig<>();
        poolConfig.setMaxTotal(configuration().getMaxTotal());
        poolConfig.setBlockWhenExhausted(false);
        builder.poolConfig(poolConfig);
        return builder.build();
    }

    public RedisConnectionFactory getConnectionFactory() {
        final LettuceConnectionFactory lettuceConnectionFactory;
        boolean hasSentinelEnabled = configuration().getSentinel().isEnabled();
        if (hasSentinelEnabled || configuration().isSentinelMode()) {
            // Sentinels + Redis master / replicas
            List<HostAndPort> sentinelNodes = configuration().getSentinel().getNodes();

            RedisSentinelConfiguration sentinelConfiguration = new RedisSentinelConfiguration();
            sentinelConfiguration.master(configuration().getSentinel().getMasterId());
            // Parsing and registering nodes
            sentinelNodes.forEach(hostAndPort -> sentinelConfiguration.sentinel(hostAndPort.getHost(), hostAndPort.getPort()));
            // Sentinel Password
            sentinelConfiguration.setSentinelPassword(RedisPassword.of(configuration().getSentinel().getPassword()));
            // Redis Password
            sentinelConfiguration.setPassword(RedisPassword.of(configuration().getPassword()));

            lettuceConnectionFactory = new LettuceConnectionFactory(sentinelConfiguration, buildLettuceClientConfiguration());
        } else {
            // Standalone Redis
            RedisStandaloneConfiguration standaloneConfiguration = new RedisStandaloneConfiguration();
            standaloneConfiguration.setHostName(configuration().getStandalone().getHost());
            standaloneConfiguration.setPort(configuration().getStandalone().getPort());
            standaloneConfiguration.setPassword(RedisPassword.of(configuration().getPassword()));

            lettuceConnectionFactory = new LettuceConnectionFactory(standaloneConfiguration, buildLettuceClientConfiguration());
        }

        lettuceConnectionFactory.afterPropertiesSet();

        return lettuceConnectionFactory;
    }
}
