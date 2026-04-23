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
package io.gravitee.resource.cache.redis.configuration;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;
import io.gravitee.plugin.annotation.ConfigurationEvaluator;
import io.gravitee.plugin.configurations.ssl.SslOptions;
import io.gravitee.resource.api.ResourceConfiguration;
import io.gravitee.secrets.api.annotation.Secret;
import io.gravitee.secrets.api.el.FieldKind;
import java.util.Map;
import lombok.CustomLog;
import lombok.Getter;
import lombok.Setter;

/**
 * @author Guillaume CUSNIEUX (guillaume.cusnieux at graviteesource.com)
 * @author GraviteeSource Team
 */
@CustomLog
@ConfigurationEvaluator
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class RedisCacheResourceConfiguration implements ResourceConfiguration {

    // Redis connection fields (aligned with RedisClientOptions shape)
    private String host = "localhost";
    private int port = 6379;

    @Secret(FieldKind.PASSWORD)
    private String password;

    private boolean useSsl = true;
    private SslOptions ssl;
    private RedisSentinelConfiguration sentinel = new RedisSentinelConfiguration();

    // Cache-specific fields
    private long timeToLiveSeconds;
    private long timeout = 2000;
    private boolean releaseCache;

    /**
     * Legacy top-level {@code sentinelMode} flag. Not a persistent field: captured on
     * deserialization so {@link #isSentinelEnabled()} can honor it regardless of
     * property order in the incoming JSON.
     */
    @JsonIgnore
    private transient boolean legacySentinelMode;

    /**
     * Backward compatibility: old API definitions store host/port under a nested
     * {@code "standalone"} object. Extract them into the flat fields.
     */
    @JsonSetter("standalone")
    public void setStandalone(Map<String, Object> standalone) {
        if (standalone == null) {
            return;
        }
        if (standalone.get("host") instanceof String s) {
            this.host = s;
        }
        if (standalone.get("port") instanceof Number n) {
            this.port = n.intValue();
        }
    }

    /**
     * Backward compatibility: serialize the {@code standalone} object so that old
     * gateways (pre-migration) can still read the config on API export/import or
     * rolling upgrades. {@code enabled} is the inverse of sentinel mode so that
     * pre-migration gateways pick the correct mode.
     */
    @JsonGetter("standalone")
    public Map<String, Object> getStandalone() {
        return Map.of("enabled", !isSentinelEnabled(), "host", host, "port", port);
    }

    /**
     * Backward compatibility: old API definitions have a top-level {@code sentinelMode}
     * boolean. We record it in {@link #legacySentinelMode} so {@link #isSentinelEnabled()}
     * treats it as equivalent to {@code sentinel.enabled=true}, independent of the
     * JSON property order.
     */
    @JsonSetter("sentinelMode")
    public void setSentinelMode(boolean sentinelMode) {
        if (sentinelMode) {
            log.warn(
                "Legacy 'sentinelMode=true' detected on Redis cache resource config; treating as sentinel.enabled=true. " +
                    "Update your config to use the nested 'sentinel' object."
            );
            this.legacySentinelMode = true;
        }
    }

    /**
     * Backward compatibility: emit a top-level {@code sentinelMode} so that old gateways
     * reading a new export still see the sentinel signal they expect.
     */
    @JsonGetter("sentinelMode")
    public boolean getSentinelMode() {
        return isSentinelEnabled();
    }

    /**
     * Backward compatibility: old API definitions have {@code maxTotal} for the
     * (unused) Lettuce pool. Ignored — pool sizing is now on the resource form
     * (maxPoolSize, maxPoolWaiting, ...).
     */
    @JsonSetter("maxTotal")
    public void setMaxTotal(int maxTotal) {
        if (maxTotal != 0) {
            log.warn(
                "Legacy 'maxTotal={}' detected on Redis cache resource config; ignored. " +
                    "Configure pool sizing via 'maxPoolSize' and 'maxPoolWaiting' on the resource form.",
                maxTotal
            );
        }
    }

    /**
     * Sentinel mode is active when the sentinel config is both explicitly enabled
     * (either via {@code sentinel.enabled=true} or the legacy {@code sentinelMode=true}
     * top-level flag) AND at least one sentinel node is declared. Both are required —
     * sentinel without nodes is nonsensical.
     */
    public boolean isSentinelEnabled() {
        if (sentinel == null || sentinel.getNodes() == null || sentinel.getNodes().isEmpty()) {
            return false;
        }
        return sentinel.isEnabled() || legacySentinelMode;
    }
}
