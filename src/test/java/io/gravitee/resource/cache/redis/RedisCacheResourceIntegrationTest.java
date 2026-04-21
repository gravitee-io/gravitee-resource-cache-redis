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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.gravitee.el.TemplateEngine;
import io.gravitee.gateway.reactive.api.context.base.BaseExecutionContext;
import io.gravitee.resource.api.AbstractConfigurableResource;
import io.gravitee.resource.cache.api.Cache;
import io.gravitee.resource.cache.api.Element;
import io.gravitee.resource.cache.redis.configuration.RedisCacheResourceConfiguration;
import io.vertx.core.Vertx;
import java.lang.reflect.Field;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end integration test for {@link RedisCacheResource}. Exercises the full lifecycle
 * (doStart/doStop, shared client registry, password-protected auth) against a real Redis
 * instance started by Testcontainers.
 */
@Testcontainers
class RedisCacheResourceIntegrationTest {

    private static final String REDIS_PASSWORD = "s3cret";

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
        .withExposedPorts(6379)
        .withCommand("redis-server", "--requirepass", REDIS_PASSWORD);

    private static Vertx vertx;

    @BeforeAll
    static void init() {
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void tearDown() {
        vertx.close();
    }

    @BeforeEach
    void resetFactory() {
        RedisCacheResource.resetSharedFactory();
    }

    @AfterEach
    void assertRegistryDrained() {
        assertThat(TestFactoryAccess.sharedClientCount()).as("Registry should be empty after each test").isZero();
    }

    @Test
    void should_authenticate_with_password_and_roundtrip() throws Exception {
        RedisCacheResource resource = underTest(configWithPassword(REDIS_PASSWORD));
        resource.start();

        Cache cache = resource.getCache(emptyCtx());
        cache.put(element("k1", "v1"));
        Element retrieved = cache.get("k1");

        assertThat(retrieved).isNotNull();
        assertThat(retrieved.value()).isEqualTo("v1");

        resource.stop();
    }

    @Test
    void should_return_null_when_password_is_wrong() throws Exception {
        RedisCacheResource resource = underTest(configWithPassword("wrong-password"));
        resource.start();

        // Auth fails -> sync API swallows error and returns null (see RedisDelegate.awaitBlocking)
        Cache cache = resource.getCache(emptyCtx());
        assertThat(cache.get("anything")).isNull();

        resource.stop();
    }

    @Test
    void should_share_client_between_resources_with_same_config() throws Exception {
        RedisCacheResource resource1 = underTest(configWithPassword(REDIS_PASSWORD));
        RedisCacheResource resource2 = underTest(configWithPassword(REDIS_PASSWORD));

        resource1.start();
        resource2.start();

        assertThat(TestFactoryAccess.sharedClientCount()).as("Both resources should share one client").isEqualTo(1);

        // Put via resource1, read via resource2 — proves they share the same backend
        resource1.getCache(emptyCtx()).put(element("shared-key", "shared-value"));
        Element retrieved = resource2.getCache(emptyCtx()).get("shared-key");

        assertThat(retrieved).isNotNull();
        assertThat(retrieved.value()).isEqualTo("shared-value");

        // Stop one -> client still alive (refcount=1)
        resource1.stop();
        assertThat(TestFactoryAccess.sharedClientCount()).isEqualTo(1);

        // resource2 still works
        assertThat(resource2.getCache(emptyCtx()).get("shared-key")).isNotNull();

        // Stop the last holder -> client closed, registry empty
        resource2.stop();
    }

    @Test
    void should_not_leak_registry_refcount_across_start_stop_cycles() throws Exception {
        int cycles = 5;
        for (int i = 0; i < cycles; i++) {
            RedisCacheResource resource = underTest(configWithPassword(REDIS_PASSWORD));
            resource.start();
            resource.stop();
        }

        assertThat(TestFactoryAccess.sharedClientCount()).isZero();
    }

    private RedisCacheResourceConfiguration configWithPassword(String password) {
        RedisCacheResourceConfiguration config = new RedisCacheResourceConfiguration();
        config.setHost(REDIS.getHost());
        config.setPort(REDIS.getMappedPort(6379));
        config.setPassword(password);
        // Testcontainers Redis is plain TCP — override the SSL default
        config.setUseSsl(false);
        return config;
    }

    private static BaseExecutionContext emptyCtx() {
        BaseExecutionContext ctx = mock(BaseExecutionContext.class);
        when(ctx.getAttributes()).thenReturn(Map.of());
        return ctx;
    }

    private static Element element(Object key, Object value) {
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

    private RedisCacheResource underTest(RedisCacheResourceConfiguration config) throws Exception {
        RedisCacheResource resource = new RedisCacheResource();
        Field configurationField = AbstractConfigurableResource.class.getDeclaredField("configuration");
        configurationField.setAccessible(true);
        configurationField.set(resource, config);
        resource.setDeploymentContext(new TestDeploymentContext(TemplateEngine.templateEngine()));
        resource.setApplicationContext(mockApplicationContext());
        return resource;
    }

    private ApplicationContext mockApplicationContext() {
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBean(Vertx.class)).thenReturn(vertx);
        return ctx;
    }
}
