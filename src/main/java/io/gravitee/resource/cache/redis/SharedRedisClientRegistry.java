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

import io.gravitee.resource.cache.redis.configuration.HostAndPort;
import io.gravitee.resource.cache.redis.configuration.RedisCacheResourceConfiguration;
import io.vertx.redis.client.Redis;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * Shares a single Vert.x {@link Redis} client across all {@link RedisCacheResource} instances
 * that connect to the same Redis endpoint. This avoids creating one connection per API
 * when thousands of APIs use the same Redis cache resource configuration.
 *
 * <p>Thread-safe. Keyed by connection parameters (host, port, password, ssl, sentinel config).
 * Uses reference counting: client is created on first acquire, closed when last reference is released.
 */
@Slf4j
class SharedRedisClientRegistry {

    static final SharedRedisClientRegistry INSTANCE = new SharedRedisClientRegistry();

    private final ConcurrentHashMap<String, RefCountedClient> clients = new ConcurrentHashMap<>();

    /**
     * Acquire a shared Redis client for the given key. If none exists, creates one using the supplier.
     * Logs a warning if the new consumer's pool/timeout config differs from the first acquire.
     */
    Redis acquire(String key, RedisCacheResourceConfiguration config, Supplier<Redis> clientSupplier) {
        RefCountedClient ref = clients.compute(key, (k, existing) -> {
            if (existing != null) {
                existing.refCount.incrementAndGet();
                warnOnPoolMismatch(config, existing.originalConfig, existing.refCount.get());
                log.debug("Reusing shared Redis client, refCount={}, registrySize={}", existing.refCount.get(), clients.size());
                return existing;
            }
            Redis client = clientSupplier.get();
            log.info("Created new shared Redis client, registrySize={}", clients.size() + 1);
            return new RefCountedClient(client, config);
        });
        return ref.client;
    }

    /**
     * Release a reference to the shared client. When the last reference is released,
     * the client is closed and removed from the registry.
     */
    void release(String key) {
        clients.compute(key, (k, existing) -> {
            if (existing == null) {
                log.error("Attempted to release unknown Redis client for key [{}] (double-release bug)", key);
                return null;
            }
            int remaining = existing.refCount.decrementAndGet();
            if (remaining <= 0) {
                log.info("Closing shared Redis client (last reference released), registrySize={}", clients.size() - 1);
                try {
                    existing.client.close();
                } catch (Exception e) {
                    log.warn("Error closing shared Redis client", e);
                }
                return null; // remove from map
            }
            log.debug("Released shared Redis client, refCount={}, registrySize={}", remaining, clients.size());
            return existing;
        });
    }

    /**
     * Build a registry key from connection parameters.
     * All resources pointing to the same Redis share one client.
     */
    static String buildKey(RedisCacheResourceConfiguration config) {
        StringBuilder sb = new StringBuilder();
        boolean isSentinel = config.getSentinel().isEnabled() || config.isSentinelMode();

        if (isSentinel) {
            sb.append("sentinel|");
            sb.append(Objects.toString(config.getSentinel().getMasterId(), ""));
            sb.append("|");
            List<HostAndPort> sortedNodes = new ArrayList<>(config.getSentinel().getNodes());
            sortedNodes.sort(Comparator.comparing(HostAndPort::getHost).thenComparingInt(HostAndPort::getPort));
            for (HostAndPort node : sortedNodes) {
                sb.append(node.getHost()).append(":").append(node.getPort()).append(",");
            }
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

    private static void warnOnPoolMismatch(
        RedisCacheResourceConfiguration requested,
        RedisCacheResourceConfiguration original,
        int refCount
    ) {
        var mismatches = new ArrayList<String>();
        if (requested.getMaxPoolSize() != original.getMaxPoolSize()) {
            mismatches.add("maxPoolSize: requested=" + requested.getMaxPoolSize() + " vs active=" + original.getMaxPoolSize());
        }
        if (requested.getMaxPoolWaiting() != original.getMaxPoolWaiting()) {
            mismatches.add("maxPoolWaiting: requested=" + requested.getMaxPoolWaiting() + " vs active=" + original.getMaxPoolWaiting());
        }
        if (requested.getPoolCleanerInterval() != original.getPoolCleanerInterval()) {
            mismatches.add(
                "poolCleanerInterval: requested=" + requested.getPoolCleanerInterval() + " vs active=" + original.getPoolCleanerInterval()
            );
        }
        if (requested.getPoolRecycleTimeout() != original.getPoolRecycleTimeout()) {
            mismatches.add(
                "poolRecycleTimeout: requested=" + requested.getPoolRecycleTimeout() + " vs active=" + original.getPoolRecycleTimeout()
            );
        }
        if (requested.getMaxWaitingHandlers() != original.getMaxWaitingHandlers()) {
            mismatches.add(
                "maxWaitingHandlers: requested=" + requested.getMaxWaitingHandlers() + " vs active=" + original.getMaxWaitingHandlers()
            );
        }
        if (requested.getConnectTimeout() != original.getConnectTimeout()) {
            mismatches.add("connectTimeout: requested=" + requested.getConnectTimeout() + " vs active=" + original.getConnectTimeout());
        }
        if (!mismatches.isEmpty()) {
            String connection = original.getSentinel().isEnabled() || original.isSentinelMode()
                ? "sentinel:" + original.getSentinel().getMasterId()
                : original.getStandalone().getHost() + ":" + original.getStandalone().getPort();
            log.warn(
                "Shared Redis client for {} has pool/timeout settings mismatch. " +
                    "First-acquire wins, these values from the new consumer are ignored: [{}]. " +
                    "To align, ensure all resources pointing to this Redis use the same pool configuration. " +
                    "Current shared client has {} active references.",
                connection,
                String.join(", ", mismatches),
                refCount
            );
        }
    }

    // Visible for testing
    int registrySize() {
        return clients.size();
    }

    private static class RefCountedClient {

        final Redis client;
        final AtomicInteger refCount;
        final RedisCacheResourceConfiguration originalConfig;

        RefCountedClient(Redis client, RedisCacheResourceConfiguration originalConfig) {
            this.client = client;
            this.refCount = new AtomicInteger(1);
            this.originalConfig = originalConfig;
        }
    }
}
