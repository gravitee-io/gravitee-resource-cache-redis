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
import static org.awaitility.Awaitility.await;

import io.gravitee.gateway.api.ExecutionContext;
import io.gravitee.resource.cache.api.Element;
import io.vertx.core.Vertx;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisAPI;
import io.vertx.redis.client.RedisOptions;
import io.vertx.redis.client.Request;
import io.vertx.redis.client.Response;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class RedisDelegateIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private static Vertx vertx;
    private static Redis redisClient;
    private static RedisAPI redisAPI;

    private RedisDelegate delegate;

    @BeforeAll
    static void bootstrapClient() {
        vertx = Vertx.vertx();
        RedisOptions options = new RedisOptions().setConnectionString("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        redisClient = Redis.createClient(vertx, options);
        redisAPI = RedisAPI.api(redisClient);
    }

    @AfterAll
    static void shutdownClient() {
        redisClient.close();
        vertx.close();
    }

    @BeforeEach
    void setUp() {
        delegate = new RedisDelegate(redisAPI, "gravitee:", Map.of(), 60, false, 2000L);
    }

    @AfterEach
    void flush() {
        redisAPI.flushall(java.util.List.of()).toCompletionStage().toCompletableFuture().join();
    }

    @Test
    void should_put_and_get_element() {
        delegate.put(element("k1", "v1", 0));

        Element retrieved = delegate.get("k1");

        assertThat(retrieved).isNotNull();
        assertThat(retrieved.value()).isEqualTo("v1");
    }

    @Test
    void should_return_null_when_key_missing() {
        assertThat(delegate.get("missing")).isNull();
    }

    @Test
    void should_evict_element() {
        delegate.put(element("k2", "v2", 0));
        delegate.evict("k2");

        assertThat(delegate.get("k2")).isNull();
    }

    @Test
    void should_honor_element_ttl_when_lower_than_default() {
        delegate.put(element("short", "v", 1));

        assertThat(delegate.get("short")).isNotNull();

        await()
            .atMost(Duration.ofSeconds(3))
            .pollInterval(Duration.ofMillis(200))
            .untilAsserted(() -> assertThat(delegate.get("short")).isNull());
    }

    @Test
    void should_use_default_ttl_when_element_ttl_higher() throws Exception {
        // default TTL = 60s (from constructor). Element TTL = 120s, so 60s wins.
        delegate.put(element("kttl", "v", 120));

        // Inspect TTL in Redis directly
        long ttl = redisAPI.ttl("gravitee:kttl").toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS).toLong();

        assertThat(ttl).isBetween(1L, 60L);
    }

    @Test
    void clear_should_be_noop_when_releaseCache_false() {
        delegate.put(element("a", "1", 0));
        delegate.put(element("b", "2", 0));

        delegate.clear();

        assertThat(delegate.get("a")).isNotNull();
        assertThat(delegate.get("b")).isNotNull();
    }

    @Test
    void clear_should_delete_only_current_deployment_keys_when_releaseCache_true() throws Exception {
        RedisDelegate scopedDelegate = new RedisDelegate(
            redisAPI,
            "gravitee:",
            Map.of(ExecutionContext.ATTR_API_DEPLOYED_AT, "deploy-A"),
            60,
            true,
            2000L
        );
        RedisDelegate otherDeployment = new RedisDelegate(
            redisAPI,
            "gravitee:",
            Map.of(ExecutionContext.ATTR_API_DEPLOYED_AT, "deploy-B"),
            60,
            true,
            2000L
        );

        scopedDelegate.put(element("x", "1", 0));
        otherDeployment.put(element("y", "2", 0));
        redisClient
            .send(Request.cmd(Command.SET).arg("unrelated:tenant:z").arg("kept"))
            .toCompletionStage()
            .toCompletableFuture()
            .get(2, TimeUnit.SECONDS);

        scopedDelegate.clear();

        assertThat(scopedDelegate.get("x")).isNull();
        assertThat(otherDeployment.get("y")).isNotNull();
        Response survivor = redisClient
            .send(Request.cmd(Command.GET).arg("unrelated:tenant:z"))
            .toCompletionStage()
            .toCompletableFuture()
            .get(2, TimeUnit.SECONDS);
        assertThat(survivor.toString()).isEqualTo("kept");
    }

    private static Element element(Object key, Object value, int ttl) {
        return new Element() {
            @Override
            public Object key() {
                return key;
            }

            @Override
            public Object value() {
                return value;
            }

            @Override
            public int timeToLive() {
                return ttl;
            }
        };
    }
}
