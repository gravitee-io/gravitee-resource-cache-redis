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
import io.gravitee.node.vertx.client.redis.VertxRedisClientFactory;
import io.gravitee.plugin.configurations.redis.RedisClientOptions;
import io.gravitee.plugin.configurations.redis.RedisSentinelOptions;
import io.gravitee.plugin.configurations.ssl.SslOptions;
import io.gravitee.resource.cache.api.Cache;
import io.gravitee.resource.cache.api.CacheResource;
import io.gravitee.resource.cache.redis.configuration.RedisCacheResourceConfiguration;
import io.gravitee.resource.cache.redis.configuration.RedisCacheResourceConfigurationEvaluator;
import io.vertx.core.Vertx;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisAPI;
import java.util.Map;
import javax.inject.Inject;
import lombok.CustomLog;
import lombok.Setter;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

/**
 * @author Guillaume CUSNIEUX (guillaume.cusnieux at graviteesource.com)
 * @author GraviteeSource Team
 */
@CustomLog
public class RedisCacheResource extends CacheResource<RedisCacheResourceConfiguration> implements ApplicationContextAware {

    private static volatile VertxRedisClientFactory sharedFactory;

    @Setter
    private ApplicationContext applicationContext;

    @Inject
    @Setter
    private DeploymentContext deploymentContext;

    private RedisCacheResourceConfiguration configuration;
    private RedisCacheGlobalOptions globalOptions;
    private volatile Redis redisClient;
    private volatile RedisAPI redisAPI;
    private RedisClientOptions redisClientOptions;

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
        globalOptions = new RedisCacheGlobalOptions(applicationContext.getEnvironment());

        redisClientOptions = buildRedisClientOptions();
        redisClient = getOrCreateFactory().acquire(redisClientOptions);

        try {
            redisAPI = RedisAPI.api(redisClient);
        } catch (Exception e) {
            getOrCreateFactory().release(redisClientOptions);
            redisClientOptions = null;
            redisClient = null;
            throw e;
        }
    }

    @Override
    protected void doStop() throws Exception {
        super.doStop();
        if (redisClientOptions != null) {
            log.debug("Releasing shared Redis client");
            getOrCreateFactory().release(redisClientOptions);
            redisClientOptions = null;
            redisClient = null;
            redisAPI = null;
        }
    }

    @Override
    public String keySeparator() {
        return ":";
    }

    /**
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

    private RedisClientOptions buildRedisClientOptions() {
        RedisClientOptions.RedisClientOptionsBuilder builder = RedisClientOptions.builder();

        builder.host(configuration.getHost());
        builder.port(configuration.getPort());
        builder.password(configuration.getPassword());
        builder.useSsl(configuration.isUseSsl());

        if (configuration.isSentinelEnabled()) {
            var sentinelConfig = configuration.getSentinel();
            builder.sentinel(
                RedisSentinelOptions.builder()
                    .masterId(sentinelConfig.getMasterId())
                    .password(sentinelConfig.getPassword())
                    .nodes(
                        sentinelConfig
                            .getNodes()
                            .stream()
                            .map(n ->
                                io.gravitee.plugin.configurations.redis.HostAndPort.builder().host(n.getHost()).port(n.getPort()).build()
                            )
                            .toList()
                    )
                    .build()
            );
        }

        if (configuration.isUseSsl()) {
            SslOptions sslOptions = configuration.getSsl();
            if (sslOptions == null) {
                // Backward compat: old configs without an explicit ssl block fall back to
                // trustAll + hostnameVerifier=false. Silently permissive — warn loudly so
                // operators upgrading can opt in to real certificate validation.
                log.warn(
                    "Redis cache resource has useSsl=true but no 'ssl' options configured (host={}:{}). " +
                        "Falling back to trustAll=true and hostnameVerifier=false — this bypasses TLS certificate validation. " +
                        "Configure 'ssl.trustStore' and 'ssl.hostnameVerifier' on the resource to harden.",
                    configuration.getHost(),
                    configuration.getPort()
                );
                sslOptions = new SslOptions();
                sslOptions.setTrustAll(true);
                sslOptions.setHostnameVerifier(false);
            }
            builder.ssl(sslOptions);
        }

        builder.maxPoolSize(globalOptions.getMaxPoolSize());
        builder.maxPoolWaiting(globalOptions.getMaxPoolWaiting());
        builder.poolCleanerInterval(globalOptions.getPoolCleanerInterval());
        builder.poolRecycleTimeout(globalOptions.getPoolRecycleTimeout());
        builder.maxWaitingHandlers(globalOptions.getMaxWaitingHandlers());
        builder.connectTimeout(globalOptions.getConnectTimeout());

        return builder.build();
    }

    private VertxRedisClientFactory getOrCreateFactory() {
        if (sharedFactory == null) {
            synchronized (RedisCacheResource.class) {
                if (sharedFactory == null) {
                    sharedFactory = new VertxRedisClientFactory(applicationContext.getBean(Vertx.class));
                }
            }
        }
        return sharedFactory;
    }

    /**
     * Test-only. Drops the static factory reference so each test starts from a clean
     * ref-count baseline. Callers must ensure every preceding {@code acquire} was matched
     * by a {@code release} — resetting with live references in flight silently leaks the
     * underlying Vert.x clients. Pair with an {@code @AfterEach} drain assertion.
     */
    static void resetSharedFactory() {
        sharedFactory = null;
    }

    // Visible for testing
    static VertxRedisClientFactory getSharedFactory() {
        return sharedFactory;
    }
}
