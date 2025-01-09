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

import io.gravitee.el.TemplateEngine;
import io.gravitee.el.spel.context.SecuredResolver;
import io.gravitee.resource.api.AbstractConfigurableResource;
import io.gravitee.resource.cache.redis.configuration.RedisCacheResourceConfiguration;
import io.gravitee.secrets.api.el.DelegatingEvaluatedSecretsMethods;
import io.gravitee.secrets.api.el.EvaluatedSecretsMethods;
import io.gravitee.secrets.api.el.FieldKind;
import io.gravitee.secrets.api.el.SecretFieldAccessControl;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.*;

/**
 * @author Benoit BORDIGONI (benoit.bordigoni at graviteesource.com)
 * @author GraviteeSource Team
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class RedisCacheResourceTest {

    private static TemplateEngine templateEngine;
    private List<SecretFieldAccessControl> recordedSecretFieldAccessControls = new ArrayList<>();

    @BeforeAll
    static void init() {
        SecuredResolver.initialize(null);
        templateEngine = TemplateEngine.templateEngine();
    }

    @BeforeEach
    void before() {
        EvaluatedSecretsMethods delegate = new EvaluatedSecretsMethods() {
            @Override
            public String fromGrant(String secretValue, SecretFieldAccessControl secretFieldAccessControl) {
                recordedSecretFieldAccessControls.add(secretFieldAccessControl);
                return secretValue;
            }

            @Override
            public String fromGrant(String contextId, String secretKey, SecretFieldAccessControl secretFieldAccessControl) {
                return fromGrant(contextId, secretFieldAccessControl);
            }

            @Override
            public String fromEL(String contextId, String uriOrName, SecretFieldAccessControl secretFieldAccessControl) {
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

        assertThat(recordedSecretFieldAccessControls)
            .containsExactlyInAnyOrder(
                new SecretFieldAccessControl(true, FieldKind.PASSWORD, "password"),
                new SecretFieldAccessControl(true, FieldKind.PASSWORD, "sentinel.password")
            );
    }

    @Test
    void should_not_be_able_to_resolve_secret_on_non_sensitive_field() throws Exception {
        RedisCacheResourceConfiguration redisCacheResourceConfiguration = new RedisCacheResourceConfiguration();
        redisCacheResourceConfiguration.getStandalone().setHost(asSecretEL("acme.com"));
        RedisCacheResource redisCacheResource = underTest(redisCacheResourceConfiguration);
        redisCacheResource.start();
        assertThat(recordedSecretFieldAccessControls).containsExactlyInAnyOrder(new SecretFieldAccessControl(false, null, null));
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
        return redisCacheResource;
    }
}
