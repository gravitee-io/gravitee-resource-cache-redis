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
import io.gravitee.el.spel.context.SecuredResolver;
import io.gravitee.resource.api.AbstractConfigurableResource;
import io.gravitee.resource.cache.redis.configuration.HostAndPort;
import io.gravitee.resource.cache.redis.configuration.RedisCacheResourceConfiguration;
import io.gravitee.secrets.api.el.DelegatingEvaluatedSecretsMethods;
import io.gravitee.secrets.api.el.EvaluatedSecretsMethods;
import io.gravitee.secrets.api.el.FieldKind;
import io.gravitee.secrets.api.el.SecretFieldAccessControl;
import io.reactivex.rxjava3.core.Single;
import io.vertx.core.Vertx;
import io.vertx.redis.client.Redis;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.context.ApplicationContext;

/**
 * @author Benoit BORDIGONI (benoit.bordigoni at graviteesource.com)
 * @author GraviteeSource Team
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class RedisCacheResourceTest {

    private static TemplateEngine templateEngine;
    private static Vertx vertx;
    private List<SecretFieldAccessControl> recordedSecretFieldAccessControls = new ArrayList<>();

    @BeforeAll
    static void init() {
        SecuredResolver.initialize(null);
        templateEngine = TemplateEngine.templateEngine();
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void tearDown() {
        vertx.close();
    }

    @BeforeEach
    void before() {
        EvaluatedSecretsMethods delegate = new EvaluatedSecretsMethods() {
            @Override
            public Single<String> fromGrant(String secretValue, SecretFieldAccessControl secretFieldAccessControl) {
                recordedSecretFieldAccessControls.add(secretFieldAccessControl);
                return Single.just(secretValue);
            }

            @Override
            public Single<String> fromGrant(String contextId, String secretKey, SecretFieldAccessControl secretFieldAccessControl) {
                return fromGrant(contextId, secretFieldAccessControl);
            }

            @Override
            public Single<String> fromEL(String contextId, String uriOrName, SecretFieldAccessControl secretFieldAccessControl) {
                return fromGrant(contextId, secretFieldAccessControl);
            }
        };
        templateEngine.getTemplateContext().setVariable("secrets", new DelegatingEvaluatedSecretsMethods(delegate));
        templateEngine.getTemplateContext().setVariable("host", "acme.com");
        templateEngine.getTemplateContext().setVariable("masterId", "r2d2");
    }

    @Test
    void should_have_defaults_in_config() {
        RedisCacheResourceConfiguration configuration = new RedisCacheResourceConfiguration();
        assertThat(configuration.getSentinel()).isNotNull();
        assertThat(configuration.getSentinel().isEnabled()).isFalse();
        assertThat(configuration.getStandalone()).isNotNull();
        assertThat(configuration.getStandalone().isEnabled()).isTrue();
        assertThat(configuration.getStandalone().getHost()).isEqualTo("localhost");
        assertThat(configuration.getStandalone().getPort()).isEqualTo(6379);
        assertThat(configuration.getTimeout()).isEqualTo(2000);
        assertThat(configuration.isUseSsl()).isTrue();
        assertThat(configuration.getMaxTotal()).isEqualTo(8);
    }

    @Test
    void should_start_and_eval_config() throws Exception {
        RedisCacheResourceConfiguration redisCacheResourceConfiguration = new RedisCacheResourceConfiguration();
        redisCacheResourceConfiguration.setPassword(asSecretEL("greenis"));
        redisCacheResourceConfiguration.getStandalone().setHost("{#host}");
        redisCacheResourceConfiguration.getSentinel().setMasterId("{#masterId}");
        redisCacheResourceConfiguration.getSentinel().setPassword(asSecretEL("greenisguard"));
        RedisCacheResource redisCacheResource = underTest(redisCacheResourceConfiguration);
        redisCacheResource.start();
        RedisCacheResourceConfiguration configuration = redisCacheResource.configuration();

        assertThat(configuration.getSentinel().getMasterId()).isEqualTo("r2d2");
        assertThat(configuration.getStandalone().getHost()).isEqualTo("acme.com");
        assertThat(configuration.getPassword()).isEqualTo("greenis");
        assertThat(configuration.getSentinel().getPassword()).isEqualTo("greenisguard");

        assertThat(recordedSecretFieldAccessControls).containsExactlyInAnyOrder(
            new SecretFieldAccessControl(true, FieldKind.PASSWORD, "password"),
            new SecretFieldAccessControl(true, FieldKind.PASSWORD, "sentinel.password")
        );

        redisCacheResource.stop();
    }

    @Test
    void should_not_be_able_to_resolve_secret_on_non_sensitive_field() throws Exception {
        RedisCacheResourceConfiguration redisCacheResourceConfiguration = new RedisCacheResourceConfiguration();
        redisCacheResourceConfiguration.getStandalone().setHost(asSecretEL("acme.com"));
        RedisCacheResource redisCacheResource = underTest(redisCacheResourceConfiguration);
        redisCacheResource.start();
        assertThat(recordedSecretFieldAccessControls).containsExactlyInAnyOrder(new SecretFieldAccessControl(false, null, null));
        redisCacheResource.stop();
    }

    @Test
    void should_release_shared_client_on_stop() throws Exception {
        RedisCacheResourceConfiguration config = new RedisCacheResourceConfiguration();
        RedisCacheResource resource = underTest(config);

        resource.start();

        // Verify redisClient field is populated after start
        Field clientField = RedisCacheResource.class.getDeclaredField("redisClient");
        clientField.setAccessible(true);
        assertThat(clientField.get(resource)).isNotNull();

        // Verify registryKey is set
        Field keyField = RedisCacheResource.class.getDeclaredField("registryKey");
        keyField.setAccessible(true);
        assertThat(keyField.get(resource)).isNotNull();

        // Registry should have 1 entry
        assertThat(SharedRedisClientRegistry.INSTANCE.registrySize()).isEqualTo(1);

        resource.stop();

        // Fields should be cleaned up after stop
        assertThat(clientField.get(resource)).isNull();
        assertThat(keyField.get(resource)).isNull();

        // Registry should be empty (last reference released)
        assertThat(SharedRedisClientRegistry.INSTANCE.registrySize()).isZero();
    }

    @Test
    void should_share_client_between_two_resources_with_same_config() throws Exception {
        RedisCacheResourceConfiguration config1 = new RedisCacheResourceConfiguration();
        RedisCacheResourceConfiguration config2 = new RedisCacheResourceConfiguration();

        RedisCacheResource resource1 = underTest(config1);
        RedisCacheResource resource2 = underTest(config2);

        resource1.start();
        resource2.start();

        // Both should share the same Redis client instance
        Field clientField = RedisCacheResource.class.getDeclaredField("redisClient");
        clientField.setAccessible(true);
        assertThat(clientField.get(resource1)).isSameAs(clientField.get(resource2));

        // Registry should have 1 entry (shared)
        assertThat(SharedRedisClientRegistry.INSTANCE.registrySize()).isEqualTo(1);

        // Stop first resource — client should stay (still referenced by resource2)
        resource1.stop();
        assertThat(SharedRedisClientRegistry.INSTANCE.registrySize()).isEqualTo(1);

        // Stop second resource — client should be closed
        resource2.stop();
        assertThat(SharedRedisClientRegistry.INSTANCE.registrySize()).isZero();
    }

    @Test
    void should_build_connection_string_standalone() {
        assertThat(RedisCacheResource.buildConnectionString("localhost", 6379, false)).isEqualTo("redis://localhost:6379");
    }

    @Test
    void should_build_connection_string_with_ssl() {
        assertThat(RedisCacheResource.buildConnectionString("redis.example.com", 6380, true)).isEqualTo("rediss://redis.example.com:6380");
    }

    @Test
    void should_start_with_ssl_enabled() throws Exception {
        RedisCacheResourceConfiguration config = new RedisCacheResourceConfiguration();
        config.setUseSsl(true);
        config.getStandalone().setHost("redis.example.com");
        config.getStandalone().setPort(6380);

        RedisCacheResource resource = underTest(config);
        resource.start();

        Field keyField = RedisCacheResource.class.getDeclaredField("registryKey");
        keyField.setAccessible(true);
        String key = (String) keyField.get(resource);
        assertThat(key).contains("ssl=true").contains("redis.example.com").contains("6380");

        resource.stop();
        assertThat(SharedRedisClientRegistry.INSTANCE.registrySize()).isZero();
    }

    @Test
    void should_start_with_sentinel_config() throws Exception {
        RedisCacheResourceConfiguration config = new RedisCacheResourceConfiguration();
        config.getSentinel().setEnabled(true);
        config.getSentinel().setMasterId("mymaster");
        config.getSentinel().setPassword("sentinel-pass");
        config.getSentinel().getNodes().add(hostAndPort("sentinel1", 26379));
        config.getSentinel().getNodes().add(hostAndPort("sentinel2", 26380));
        config.setPassword("redis-pass");
        config.setUseSsl(false);

        RedisCacheResource resource = underTest(config);
        resource.start();

        Field keyField = RedisCacheResource.class.getDeclaredField("registryKey");
        keyField.setAccessible(true);
        String key = (String) keyField.get(resource);
        assertThat(key)
            .startsWith("sentinel|")
            .contains("mymaster")
            .contains("sentinel1:26379")
            .contains("sentinel2:26380")
            .contains("sentinel-pass")
            .contains("redis-pass");

        resource.stop();
        assertThat(SharedRedisClientRegistry.INSTANCE.registrySize()).isZero();
    }

    @Test
    void should_not_share_clients_with_different_ssl_settings() throws Exception {
        RedisCacheResourceConfiguration config1 = new RedisCacheResourceConfiguration();
        config1.setUseSsl(false);

        RedisCacheResourceConfiguration config2 = new RedisCacheResourceConfiguration();
        config2.setUseSsl(true);

        RedisCacheResource resource1 = underTest(config1);
        RedisCacheResource resource2 = underTest(config2);

        resource1.start();
        resource2.start();

        // Different SSL settings = different clients
        Field clientField = RedisCacheResource.class.getDeclaredField("redisClient");
        clientField.setAccessible(true);
        assertThat(clientField.get(resource1)).isNotSameAs(clientField.get(resource2));
        assertThat(SharedRedisClientRegistry.INSTANCE.registrySize()).isEqualTo(2);

        resource1.stop();
        resource2.stop();
        assertThat(SharedRedisClientRegistry.INSTANCE.registrySize()).isZero();
    }

    private static HostAndPort hostAndPort(String host, int port) {
        HostAndPort hp = new HostAndPort();
        hp.setHost(host);
        hp.setPort(port);
        return hp;
    }

    private static String asSecretEL(String password) {
        return "{#secrets.fromGrant('%s', #%s)}".formatted(password, SecretFieldAccessControl.EL_VARIABLE);
    }

    RedisCacheResource underTest(RedisCacheResourceConfiguration config) throws IllegalAccessException, NoSuchFieldException {
        RedisCacheResource redisCacheResource = new RedisCacheResource();
        Field configurationField = AbstractConfigurableResource.class.getDeclaredField("configuration");
        configurationField.setAccessible(true);
        configurationField.set(redisCacheResource, config);
        redisCacheResource.setDeploymentContext(new TestDeploymentContext(templateEngine));
        redisCacheResource.setApplicationContext(mockApplicationContext());
        return redisCacheResource;
    }

    private ApplicationContext mockApplicationContext() {
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBean(Vertx.class)).thenReturn(vertx);
        return ctx;
    }
}
