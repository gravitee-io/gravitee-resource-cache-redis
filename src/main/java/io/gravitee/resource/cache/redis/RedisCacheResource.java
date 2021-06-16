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

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gravitee.gateway.api.ExecutionContext;
import io.gravitee.resource.cache.api.Cache;
import io.gravitee.resource.cache.api.CacheResource;
import io.gravitee.resource.cache.redis.configuration.HostAndPort;
import io.gravitee.resource.cache.redis.configuration.RedisCacheResourceConfiguration;
import java.time.Duration;
import java.util.List;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * @author Guillaume CUSNIEUX (guillaume.cusnieux at graviteesource.com)
 * @author GraviteeSource Team
 */
public class RedisCacheResource extends CacheResource<RedisCacheResourceConfiguration> {

    private final Logger logger = LoggerFactory.getLogger(RedisCacheResource.class);

    @Override
    public Cache getCache(ExecutionContext executionContext) {
        logger.debug("Get cache");
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        ObjectMapper mapper = new ObjectMapper();
        mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        RedisSerializer jacksonSerializer = new GenericJackson2JsonRedisSerializer(mapper);
        try {
            RedisTemplate<Object, Object> redisTemplate = new RedisTemplate();
            redisTemplate.setConnectionFactory(getConnectionFactory());
            redisTemplate.setKeySerializer(stringSerializer);
            redisTemplate.setValueSerializer(jacksonSerializer);
            redisTemplate.afterPropertiesSet();
            return new RedisDelegate(redisTemplate, configuration());
        } catch (Throwable e) {
            logger.error("Cannot get cache", e);
        }
        return null;
    }

    private LettucePoolingClientConfiguration buildLettuceClientConfiguration() {
        final LettucePoolingClientConfiguration.LettucePoolingClientConfigurationBuilder builder = LettucePoolingClientConfiguration.builder();
        builder.commandTimeout(Duration.ofMillis(configuration().getTimeout()));
        if (configuration().isUseSsl()) {
            builder.useSsl();
        }
        GenericObjectPoolConfig poolConfig = new GenericObjectPoolConfig();
        poolConfig.setMaxTotal(configuration().getMaxTotal());
        poolConfig.setBlockWhenExhausted(false);
        builder.poolConfig(poolConfig);
        return builder.build();
    }

    public RedisConnectionFactory getConnectionFactory() {
        final LettuceConnectionFactory lettuceConnectionFactory;

        if (configuration().isSentinelMode()) {
            // Sentinels + Redis master / replicas
            List<HostAndPort> sentinelNodes = configuration().getSentinel().getNodes();

            RedisSentinelConfiguration sentinelConfiguration = new RedisSentinelConfiguration();
            sentinelConfiguration.master(configuration().getSentinel().getMasterId());
            // Parsing and registering nodes
            sentinelNodes.forEach(hostAndPort -> sentinelConfiguration.sentinel(hostAndPort.getHost(), hostAndPort.getPort()));
            // Sentinel Password
            sentinelConfiguration.setSentinelPassword(configuration().getSentinel().getPassword());
            // Redis Password
            sentinelConfiguration.setPassword(configuration().getPassword());

            lettuceConnectionFactory = new LettuceConnectionFactory(sentinelConfiguration, buildLettuceClientConfiguration());
        } else {
            // Standalone Redis
            RedisStandaloneConfiguration standaloneConfiguration = new RedisStandaloneConfiguration();
            standaloneConfiguration.setHostName(configuration().getStandalone().getHost());
            standaloneConfiguration.setPort(configuration().getStandalone().getPort());
            standaloneConfiguration.setPassword(configuration().getPassword());

            lettuceConnectionFactory = new LettuceConnectionFactory(standaloneConfiguration, buildLettuceClientConfiguration());
        }
        lettuceConnectionFactory.afterPropertiesSet();

        return lettuceConnectionFactory;
    }
}
