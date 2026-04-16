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
import io.vertx.core.Vertx;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisAPI;
import io.vertx.redis.client.RedisClientType;
import io.vertx.redis.client.RedisOptions;
import io.vertx.redis.client.RedisRole;
import java.util.Map;
import javax.inject.Inject;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

/**
 * @author Guillaume CUSNIEUX (guillaume.cusnieux at graviteesource.com)
 * @author GraviteeSource Team
 */
@Slf4j
public class RedisCacheResource extends CacheResource<RedisCacheResourceConfiguration> implements ApplicationContextAware {

    @Setter
    private ApplicationContext applicationContext;

    @Inject
    @Setter
    private DeploymentContext deploymentContext;

    private RedisCacheResourceConfiguration configuration;
    private volatile Redis redisClient;
    private volatile RedisAPI redisAPI;
    private String registryKey;

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
        log.debug("Create redis cache resource");

        configuration = new RedisCacheResourceConfigurationEvaluator(configuration()).evalNow(deploymentContext);

        Vertx vertx = applicationContext.getBean(Vertx.class);

        registryKey = SharedRedisClientRegistry.buildKey(configuration);

        redisClient = SharedRedisClientRegistry.INSTANCE.acquire(registryKey, configuration, () -> {
            RedisOptions options = buildRedisOptions();
            return Redis.createClient(vertx, options);
        });

        try {
            redisAPI = RedisAPI.api(redisClient);
        } catch (Exception e) {
            // Ensure we don't leak a refcount if anything after acquire() fails
            SharedRedisClientRegistry.INSTANCE.release(registryKey);
            registryKey = null;
            redisClient = null;
            throw e;
        }
    }

    @Override
    protected void doStop() throws Exception {
        super.doStop();
        if (registryKey != null) {
            log.debug("Releasing shared Redis client for key [{}]", registryKey);
            SharedRedisClientRegistry.INSTANCE.release(registryKey);
            registryKey = null;
            redisClient = null;
            redisAPI = null;
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
            redisAPI,
            "gravitee:",
            contextAttributes,
            (int) configuration().getTimeToLiveSeconds(),
            configuration().isReleaseCache(),
            configuration().getTimeout()
        );
    }

    private RedisOptions buildRedisOptions() {
        RedisOptions options = new RedisOptions();
        boolean ssl = configuration.isUseSsl();
        boolean isSentinel = configuration.getSentinel().isEnabled() || configuration.isSentinelMode();

        if (isSentinel) {
            options.setType(RedisClientType.SENTINEL);
            for (HostAndPort node : configuration.getSentinel().getNodes()) {
                options.addConnectionString(buildConnectionString(node.getHost(), node.getPort(), ssl));
            }
            options.setMasterName(configuration.getSentinel().getMasterId());
            options.setRole(RedisRole.MASTER);
            String sentinelPassword = configuration.getSentinel().getPassword();
            if (sentinelPassword != null && !sentinelPassword.isEmpty()) {
                options.setPassword(sentinelPassword);
            }
        } else {
            options.setType(RedisClientType.STANDALONE);
            options.setConnectionString(
                buildConnectionString(configuration.getStandalone().getHost(), configuration.getStandalone().getPort(), ssl)
            );
            String password = configuration.getPassword();
            if (password != null && !password.isEmpty()) {
                options.setPassword(password);
            }
        }

        if (ssl) {
            options.getNetClientOptions().setSsl(true).setTrustAll(true);
            options.getNetClientOptions().setHostnameVerificationAlgorithm("");
        }

        options.setMaxPoolSize(configuration.getMaxPoolSize());
        options.setMaxPoolWaiting(configuration.getMaxPoolWaiting());
        options.setPoolCleanerInterval(configuration.getPoolCleanerInterval());
        options.setPoolRecycleTimeout(configuration.getPoolRecycleTimeout());
        options.setMaxWaitingHandlers(configuration.getMaxWaitingHandlers());
        options.getNetClientOptions().setConnectTimeout(configuration.getConnectTimeout());

        return options;
    }

    static String buildConnectionString(String host, int port, boolean ssl) {
        String scheme = ssl ? "rediss" : "redis";
        return scheme + "://" + host + ":" + port;
    }
}
