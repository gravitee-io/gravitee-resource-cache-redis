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
import io.gravitee.resource.cache.api.Cache;
import io.gravitee.resource.cache.api.Element;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.redis.client.RedisAPI;
import io.vertx.redis.client.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import lombok.CustomLog;
import lombok.RequiredArgsConstructor;

/**
 * @author Guillaume CUSNIEUX (guillaume.cusnieux at graviteesource.com)
 * @author GraviteeSource Team
 */
@CustomLog
@RequiredArgsConstructor
public class RedisDelegate implements Cache {

    private final RedisAPI redisAPI;
    private final String cacheName;
    private final Map<String, Object> contextAttributes;
    private final int timeToLiveSeconds;
    private final boolean releaseCache;
    private final long commandTimeoutMs;

    @Override
    public String getName() {
        return this.cacheName;
    }

    @Override
    public Object getNativeCache() {
        return redisAPI;
    }

    @Override
    public Element get(Object key) {
        log.debug("Find in cache {}", key);
        return awaitBlocking(getAsync(key), "get");
    }

    @Override
    public void put(Element element) {
        log.debug("Put in cache {}", element.key());
        awaitBlocking(putAsync(element), "put");
    }

    @Override
    public void evict(Object o) {
        log.debug("Evict from cache {}", o);
        awaitBlocking(evictAsync(o), "evict");
    }

    @Override
    public void clear() {
        log.debug("Clear cache {}", cacheName);
        awaitBlocking(clearAsync(), "clear");
    }

    @Override
    public Future<Element> getAsync(Object key) {
        String redisKey = buildKey(key);
        return redisAPI
            .get(redisKey)
            .timeout(commandTimeoutMs, TimeUnit.MILLISECONDS)
            .map(response -> response != null ? toElement(key, response.toString()) : null);
    }

    @Override
    public Future<Void> putAsync(Element element) {
        int ttl = resolveTtl(element);
        String redisKey = buildKey(element.key());
        String value = (String) element.value();
        Future<Response> future;
        if (ttl > 0) {
            future = redisAPI.setex(redisKey, String.valueOf(ttl), value);
        } else {
            future = redisAPI.set(List.of(redisKey, value));
        }
        return future.timeout(commandTimeoutMs, TimeUnit.MILLISECONDS).mapEmpty();
    }

    @Override
    public Future<Void> evictAsync(Object key) {
        String redisKey = buildKey(key);
        return redisAPI.del(List.of(redisKey)).timeout(commandTimeoutMs, TimeUnit.MILLISECONDS).mapEmpty();
    }

    /**
     * Clears only the current deployment's keys when {@code releaseCache=true}.
     * Keys are written as {@code "gravitee:<userkey>:<deployAt>"} under that flag, so the
     * SCAN pattern {@code "gravitee:*:<deployAt>"} isolates this deployment from other
     * APIs/deployments sharing the same Redis. When {@code releaseCache=false} keys have
     * no deployAt suffix and we intentionally no-op rather than wipe the full
     * {@code "gravitee:*"} namespace (which would delete other APIs' entries).
     */
    @Override
    public Future<Void> clearAsync() {
        if (!releaseCache) {
            log.debug("[redis-cache] clear() no-op: releaseCache=false — keys are not scoped by deployment");
            return Future.succeededFuture();
        }
        Object deployAt = contextAttributes != null ? contextAttributes.get(ExecutionContext.ATTR_API_DEPLOYED_AT) : null;
        if (deployAt == null) {
            log.warn("[redis-cache] clear() skipped: ATTR_API_DEPLOYED_AT missing from context");
            return Future.succeededFuture();
        }
        return scanAndDelete("0", cacheName + "*:" + deployAt);
    }

    private Future<Void> scanAndDelete(String cursor, String pattern) {
        return redisAPI
            .scan(List.of(cursor, "MATCH", pattern, "COUNT", "100"))
            .timeout(commandTimeoutMs, TimeUnit.MILLISECONDS)
            .compose(scanResult -> {
                String nextCursor = scanResult.get(0).toString();
                Response keys = scanResult.get(1);
                Future<Void> deleteFuture = Future.succeededFuture();
                if (keys != null && keys.size() > 0) {
                    List<String> keyList = new ArrayList<>(keys.size());
                    for (Response k : keys) {
                        keyList.add(k.toString());
                    }
                    deleteFuture = redisAPI.del(keyList).timeout(commandTimeoutMs, TimeUnit.MILLISECONDS).mapEmpty();
                }
                if ("0".equals(nextCursor)) {
                    return deleteFuture;
                }
                return deleteFuture.compose(v -> scanAndDelete(nextCursor, pattern));
            });
    }

    @SuppressWarnings("unchecked")
    private <T> T awaitBlocking(Future<T> future, String op) {
        // join() on an event-loop thread deadlocks: the same loop would complete the future.
        // Warn and bail so the request proceeds as a cache miss instead of hanging indefinitely.
        if (Context.isOnEventLoopThread()) {
            log.warn(
                "[redis-cache-{}] Sync {}() invoked from Vert.x event-loop thread — skipping to avoid deadlock. Use {}Async().",
                op,
                op,
                op
            );
            return null;
        }
        try {
            return (T) future.toCompletionStage().toCompletableFuture().join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.error("[redis-cache-{}] Redis {} operation failed: {}", op, op, cause.getMessage(), cause);
            return null;
        }
    }

    private String buildKey(Object key) {
        String fullKey = cacheName + key;
        if (releaseCache) {
            fullKey += ":" + contextAttributes.get(ExecutionContext.ATTR_API_DEPLOYED_AT);
        }
        return fullKey;
    }

    private int resolveTtl(Element element) {
        int ttl = timeToLiveSeconds;
        if ((ttl == 0 && element.timeToLive() > 0) || (ttl > 0 && element.timeToLive() > 0 && ttl > element.timeToLive())) {
            ttl = element.timeToLive();
        }
        return ttl;
    }

    private static Element toElement(Object key, String value) {
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
}
