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

import io.gravitee.resource.cache.redis.configuration.RedisCacheResourceConfiguration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/**
 * Shares a single {@link LettuceConnectionFactory} across all {@link RedisCacheResource} instances
 * that connect to the same Redis endpoint. This avoids creating one persistent connection per API
 * when thousands of APIs use the same Redis cache resource configuration.
 *
 * <p>Thread-safe. Keyed by connection parameters (host, port, password, ssl, sentinel config).
 * Uses reference counting: factory is created on first acquire, destroyed when last reference is released.
 */
@Slf4j
class SharedConnectionFactoryRegistry {

    static final SharedConnectionFactoryRegistry INSTANCE = new SharedConnectionFactoryRegistry();

    private final ConcurrentHashMap<String, RefCountedFactory> factories = new ConcurrentHashMap<>();

    /**
     * Acquire a shared factory for the given key. If none exists, creates one using the supplier.
     */
    LettuceConnectionFactory acquire(String key, Supplier<LettuceConnectionFactory> factorySupplier) {
        RefCountedFactory ref = factories.compute(key, (k, existing) -> {
            if (existing != null) {
                existing.refCount.incrementAndGet();
                log.debug("Reusing shared connection factory for key [{}], refCount={}", k, existing.refCount.get());
                return existing;
            }
            LettuceConnectionFactory factory = factorySupplier.get();
            log.info("Created shared connection factory for key [{}]", k);
            return new RefCountedFactory(factory);
        });
        return ref.factory;
    }

    /**
     * Release a reference to the shared factory. When the last reference is released,
     * the factory is destroyed and removed from the registry.
     */
    void release(String key) {
        factories.compute(key, (k, existing) -> {
            if (existing == null) {
                log.warn("Attempted to release unknown connection factory key [{}]", k);
                return null;
            }
            int remaining = existing.refCount.decrementAndGet();
            if (remaining <= 0) {
                log.info("Destroying shared connection factory for key [{}] (last reference released)", k);
                try {
                    existing.factory.destroy();
                } catch (Exception e) {
                    log.warn("Error destroying shared connection factory for key [{}]", k, e);
                }
                return null; // remove from map
            }
            log.debug("Released shared connection factory for key [{}], refCount={}", k, remaining);
            return existing;
        });
    }

    /**
     * Build a registry key from connection parameters.
     * All resources pointing to the same Redis share one factory.
     */
    static String buildKey(RedisCacheResourceConfiguration config) {
        StringBuilder sb = new StringBuilder();
        boolean isSentinel = config.getSentinel().isEnabled() || config.isSentinelMode();

        if (isSentinel) {
            sb.append("sentinel|");
            sb.append(Objects.toString(config.getSentinel().getMasterId(), ""));
            sb.append("|");
            config
                .getSentinel()
                .getNodes()
                .forEach(node -> {
                    sb.append(node.getHost()).append(":").append(node.getPort()).append(",");
                });
            sb.append("|");
            sb.append(Objects.toString(config.getSentinel().getPassword(), ""));
        } else {
            sb.append("standalone|");
            sb.append(Objects.toString(config.getStandalone().getHost(), ""));
            sb.append(":");
            sb.append(config.getStandalone().getPort());
        }

        sb.append("|");
        sb.append(Objects.toString(config.getPassword(), ""));
        sb.append("|ssl=");
        sb.append(config.isUseSsl());

        return sb.toString();
    }

    // Visible for testing
    int registrySize() {
        return factories.size();
    }

    private static class RefCountedFactory {

        final LettuceConnectionFactory factory;
        final AtomicInteger refCount;

        RefCountedFactory(LettuceConnectionFactory factory) {
            this.factory = factory;
            this.refCount = new AtomicInteger(1);
        }
    }
}
