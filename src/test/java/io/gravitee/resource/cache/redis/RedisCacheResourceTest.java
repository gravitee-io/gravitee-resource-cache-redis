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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gravitee.el.TemplateEngine;
import io.gravitee.el.spel.context.SecuredResolver;
import io.gravitee.plugin.configurations.ssl.SslOptions;
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
import java.util.Map;
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

    @AfterEach
    void assertRegistryDrained() {
        assertThat(TestFactoryAccess.sharedClientCount()).as("Registry should be empty after each test").isZero();
    }

    @BeforeEach
    void before() {
        RedisCacheResource.resetSharedFactory();
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
        assertThat(configuration.isSentinelEnabled()).isFalse();
        assertThat(configuration.getHost()).isEqualTo("localhost");
        assertThat(configuration.getPort()).isEqualTo(6379);
        assertThat(configuration.getTimeout()).isEqualTo(2000);
        assertThat(configuration.isUseSsl()).isTrue();
    }

    @Test
    void should_start_and_eval_config() throws Exception {
        RedisCacheResourceConfiguration redisCacheResourceConfiguration = new RedisCacheResourceConfiguration();
        redisCacheResourceConfiguration.setPassword(asSecretEL("greenis"));
        redisCacheResourceConfiguration.setHost("{#host}");
        redisCacheResourceConfiguration.getSentinel().setMasterId("{#masterId}");
        redisCacheResourceConfiguration.getSentinel().setPassword(asSecretEL("greenisguard"));
        RedisCacheResource redisCacheResource = underTest(redisCacheResourceConfiguration);
        redisCacheResource.start();
        RedisCacheResourceConfiguration configuration = redisCacheResource.configuration();

        assertThat(configuration.getSentinel().getMasterId()).isEqualTo("r2d2");
        assertThat(configuration.getHost()).isEqualTo("acme.com");
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
        redisCacheResourceConfiguration.setHost(asSecretEL("acme.com"));
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

        // Verify redisClientOptions is set
        Field keyField = RedisCacheResource.class.getDeclaredField("redisClientOptions");
        keyField.setAccessible(true);
        assertThat(keyField.get(resource)).isNotNull();

        // Registry should have 1 entry
        assertThat(TestFactoryAccess.sharedClientCount()).isEqualTo(1);

        resource.stop();

        // Fields should be cleaned up after stop
        assertThat(clientField.get(resource)).isNull();
        assertThat(keyField.get(resource)).isNull();

        // Registry should be empty (last reference released)
        assertThat(TestFactoryAccess.sharedClientCount()).isZero();
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
        assertThat(TestFactoryAccess.sharedClientCount()).isEqualTo(1);

        // Stop first resource — client should stay (still referenced by resource2)
        resource1.stop();
        assertThat(TestFactoryAccess.sharedClientCount()).isEqualTo(1);

        // Stop second resource — client should be closed
        resource2.stop();
        assertThat(TestFactoryAccess.sharedClientCount()).isZero();
    }

    @Test
    void should_build_redis_client_options_standalone() throws Exception {
        RedisCacheResourceConfiguration config = new RedisCacheResourceConfiguration();
        config.setHost("redis.example.com");
        config.setPort(6380);
        config.setUseSsl(false);

        RedisCacheResource resource = underTest(config);
        resource.start();

        Field optionsField = RedisCacheResource.class.getDeclaredField("redisClientOptions");
        optionsField.setAccessible(true);
        io.gravitee.plugin.configurations.redis.RedisClientOptions options =
            (io.gravitee.plugin.configurations.redis.RedisClientOptions) optionsField.get(resource);
        assertThat(options.getHost()).isEqualTo("redis.example.com");
        assertThat(options.getPort()).isEqualTo(6380);
        assertThat(options.isUseSsl()).isFalse();

        resource.stop();
    }

    @Test
    void should_start_with_ssl_enabled() throws Exception {
        RedisCacheResourceConfiguration config = new RedisCacheResourceConfiguration();
        config.setUseSsl(true);
        config.setHost("redis.example.com");
        config.setPort(6380);

        RedisCacheResource resource = underTest(config);
        resource.start();

        Field optionsField = RedisCacheResource.class.getDeclaredField("redisClientOptions");
        optionsField.setAccessible(true);
        io.gravitee.plugin.configurations.redis.RedisClientOptions options =
            (io.gravitee.plugin.configurations.redis.RedisClientOptions) optionsField.get(resource);
        assertThat(options.isUseSsl()).isTrue();
        assertThat(options.getHost()).isEqualTo("redis.example.com");
        assertThat(options.getPort()).isEqualTo(6380);

        resource.stop();
        assertThat(TestFactoryAccess.sharedClientCount()).isZero();
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

        Field optionsField = RedisCacheResource.class.getDeclaredField("redisClientOptions");
        optionsField.setAccessible(true);
        io.gravitee.plugin.configurations.redis.RedisClientOptions options =
            (io.gravitee.plugin.configurations.redis.RedisClientOptions) optionsField.get(resource);
        assertThat(options.getSentinel()).isNotNull();
        assertThat(options.getSentinel().isEnabled()).isTrue();
        assertThat(options.getSentinel().getMasterId()).isEqualTo("mymaster");
        assertThat(options.getSentinel().getPassword()).isEqualTo("sentinel-pass");
        assertThat(options.getSentinel().getNodes()).hasSize(2);
        assertThat(options.getPassword()).isEqualTo("redis-pass");

        resource.stop();
        assertThat(TestFactoryAccess.sharedClientCount()).isZero();
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
        assertThat(TestFactoryAccess.sharedClientCount()).isEqualTo(2);

        resource1.stop();
        resource2.stop();
        assertThat(TestFactoryAccess.sharedClientCount()).isZero();
    }

    @Test
    void should_propagate_global_pool_settings_to_client_options() throws Exception {
        RedisCacheResourceConfiguration config = new RedisCacheResourceConfiguration();
        RedisCacheResource resource = underTestWithGlobalOptions(
            config,
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
        resource.start();

        Field optionsField = RedisCacheResource.class.getDeclaredField("redisClientOptions");
        optionsField.setAccessible(true);
        io.gravitee.plugin.configurations.redis.RedisClientOptions options =
            (io.gravitee.plugin.configurations.redis.RedisClientOptions) optionsField.get(resource);
        assertThat(options.getMaxPoolSize()).isEqualTo(42);
        assertThat(options.getMaxPoolWaiting()).isEqualTo(43);
        assertThat(options.getPoolCleanerInterval()).isEqualTo(44);
        assertThat(options.getPoolRecycleTimeout()).isEqualTo(45);
        assertThat(options.getMaxWaitingHandlers()).isEqualTo(46);
        assertThat(options.getConnectTimeout()).isEqualTo(47);

        resource.stop();
    }

    @Test
    void should_default_to_trust_all_when_useSsl_and_no_ssl_options() throws Exception {
        RedisCacheResourceConfiguration config = new RedisCacheResourceConfiguration();
        config.setUseSsl(true);
        // ssl intentionally null — exercises the backward-compat fallback

        RedisCacheResource resource = underTest(config);
        resource.start();

        Field optionsField = RedisCacheResource.class.getDeclaredField("redisClientOptions");
        optionsField.setAccessible(true);
        io.gravitee.plugin.configurations.redis.RedisClientOptions options =
            (io.gravitee.plugin.configurations.redis.RedisClientOptions) optionsField.get(resource);
        assertThat(options.getSsl()).isNotNull();
        assertThat(options.getSsl().isTrustAll()).isTrue();
        assertThat(options.getSsl().isHostnameVerifier()).isFalse();

        resource.stop();
    }

    @Test
    void should_preserve_explicit_ssl_options_when_provided() throws Exception {
        RedisCacheResourceConfiguration config = new RedisCacheResourceConfiguration();
        config.setUseSsl(true);
        SslOptions explicitSsl = new SslOptions();
        explicitSsl.setTrustAll(false);
        explicitSsl.setHostnameVerifier(true);
        config.setSsl(explicitSsl);

        RedisCacheResource resource = underTest(config);
        resource.start();

        Field optionsField = RedisCacheResource.class.getDeclaredField("redisClientOptions");
        optionsField.setAccessible(true);
        io.gravitee.plugin.configurations.redis.RedisClientOptions options =
            (io.gravitee.plugin.configurations.redis.RedisClientOptions) optionsField.get(resource);
        assertThat(options.getSsl()).isNotNull();
        assertThat(options.getSsl().isTrustAll()).isFalse();
        assertThat(options.getSsl().isHostnameVerifier()).isTrue();

        resource.stop();
    }

    @Test
    void should_not_detect_sentinel_when_enabled_but_nodes_empty() {
        RedisCacheResourceConfiguration config = new RedisCacheResourceConfiguration();
        config.getSentinel().setEnabled(true);
        assertThat(config.isSentinelEnabled()).isFalse();
    }

    @Test
    void should_not_detect_sentinel_when_nodes_present_but_disabled() {
        RedisCacheResourceConfiguration config = new RedisCacheResourceConfiguration();
        config.getSentinel().setEnabled(false);
        config.getSentinel().getNodes().add(hostAndPort("s1", 26379));
        assertThat(config.isSentinelEnabled()).isFalse();
    }

    @Test
    void should_detect_sentinel_when_enabled_and_nodes_present() {
        RedisCacheResourceConfiguration config = new RedisCacheResourceConfiguration();
        config.getSentinel().setEnabled(true);
        config.getSentinel().getNodes().add(hostAndPort("s1", 26379));
        assertThat(config.isSentinelEnabled()).isTrue();
    }

    @Test
    void should_deserialize_legacy_nested_standalone_config() throws Exception {
        String json =
            "{\"standalone\":{\"enabled\":true,\"host\":\"r.example\",\"port\":6380}," +
            "\"maxTotal\":50,\"password\":\"pw\",\"useSsl\":false}";
        RedisCacheResourceConfiguration cfg = new ObjectMapper().readValue(json, RedisCacheResourceConfiguration.class);

        assertThat(cfg.getHost()).isEqualTo("r.example");
        assertThat(cfg.getPort()).isEqualTo(6380);
        assertThat(cfg.getPassword()).isEqualTo("pw");
        assertThat(cfg.isUseSsl()).isFalse();
        assertThat(cfg.isSentinelEnabled()).isFalse();
    }

    @Test
    void should_honor_legacy_sentinel_mode_flag_regardless_of_property_order() throws Exception {
        // sentinelMode BEFORE sentinel in the JSON — the transient legacy flag survives
        // even if Jackson later replaces the sentinel object.
        String jsonModeFirst =
            "{\"sentinelMode\":true,\"sentinel\":{\"masterId\":\"m1\"," + "\"nodes\":[{\"host\":\"s1\",\"port\":26379}]}}";
        RedisCacheResourceConfiguration cfgModeFirst = new ObjectMapper().readValue(jsonModeFirst, RedisCacheResourceConfiguration.class);
        assertThat(cfgModeFirst.isSentinelEnabled()).isTrue();

        // sentinelMode AFTER sentinel — also works.
        String jsonModeLast =
            "{\"sentinel\":{\"masterId\":\"m1\",\"nodes\":[{\"host\":\"s1\",\"port\":26379}]}," + "\"sentinelMode\":true}";
        RedisCacheResourceConfiguration cfgModeLast = new ObjectMapper().readValue(jsonModeLast, RedisCacheResourceConfiguration.class);
        assertThat(cfgModeLast.isSentinelEnabled()).isTrue();
    }

    @Test
    void should_serialize_standalone_and_sentinel_mode_for_old_gateways() throws Exception {
        RedisCacheResourceConfiguration cfg = new RedisCacheResourceConfiguration();
        cfg.setHost("r.example");
        cfg.setPort(6380);

        ObjectMapper mapper = new ObjectMapper();
        JsonNode node = mapper.readTree(mapper.writeValueAsString(cfg));

        assertThat(node.path("standalone").path("host").asText()).isEqualTo("r.example");
        assertThat(node.path("standalone").path("port").asInt()).isEqualTo(6380);
        assertThat(node.path("standalone").path("enabled").asBoolean()).isTrue();
        assertThat(node.path("sentinelMode").asBoolean()).isFalse();
    }

    @Test
    void should_serialize_sentinel_mode_true_and_standalone_enabled_false_when_sentinel_active() throws Exception {
        RedisCacheResourceConfiguration cfg = new RedisCacheResourceConfiguration();
        cfg.getSentinel().setEnabled(true);
        cfg.getSentinel().getNodes().add(hostAndPort("s1", 26379));

        ObjectMapper mapper = new ObjectMapper();
        JsonNode node = mapper.readTree(mapper.writeValueAsString(cfg));

        assertThat(node.path("sentinelMode").asBoolean()).isTrue();
        // When sentinel is active, the standalone.enabled flipper must output false
        // so old gateways pick sentinel instead of connecting to standalone defaults.
        assertThat(node.path("standalone").path("enabled").asBoolean()).isFalse();
    }

    @Test
    void should_silently_ignore_legacy_maxTotal_on_deserialization() throws Exception {
        // Deserialization must not fail with unknown/legacy fields; pool settings now live in
        // gravitee.yml, not on the per-resource config.
        String json = "{\"maxTotal\":200,\"maxPoolSize\":999,\"connectTimeout\":7777}";
        RedisCacheResourceConfiguration cfg = new ObjectMapper().readValue(json, RedisCacheResourceConfiguration.class);
        assertThat(cfg).isNotNull();
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
        return underTestWithGlobalOptions(config, java.util.Map.of());
    }

    RedisCacheResource underTestWithGlobalOptions(RedisCacheResourceConfiguration config, java.util.Map<String, Integer> globalOverrides)
        throws IllegalAccessException, NoSuchFieldException {
        RedisCacheResource redisCacheResource = new RedisCacheResource();
        Field configurationField = AbstractConfigurableResource.class.getDeclaredField("configuration");
        configurationField.setAccessible(true);
        configurationField.set(redisCacheResource, config);
        redisCacheResource.setDeploymentContext(new TestDeploymentContext(templateEngine));
        redisCacheResource.setApplicationContext(mockApplicationContext(globalOverrides));
        return redisCacheResource;
    }

    private ApplicationContext mockApplicationContext(java.util.Map<String, Integer> globalOverrides) {
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBean(Vertx.class)).thenReturn(vertx);
        org.springframework.core.env.Environment env = mock(org.springframework.core.env.Environment.class);
        when(
            env.getProperty(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(Integer.class),
                org.mockito.ArgumentMatchers.anyInt()
            )
        ).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            Integer override = globalOverrides.get(key);
            return override != null ? override : inv.getArgument(2);
        });
        when(ctx.getEnvironment()).thenReturn(env);
        return ctx;
    }
}
