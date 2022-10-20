/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.resource.cache.redis.configuration;

import io.gravitee.resource.api.ResourceConfiguration;

/**
 * @author Guillaume CUSNIEUX (guillaume.cusnieux at graviteesource.com)
 * @author GraviteeSource Team
 */
public class RedisCacheResourceConfiguration implements ResourceConfiguration {

    private String name = "my-redis-cache";
    public static final String KEY_SEPARATOR = ":";
    private int maxTotal = 8;
    private String password;
    private long timeToLiveSeconds = 0;
    private long timeout = 2000;
    private boolean releaseCache = false;
    private boolean useSsl = true;

    @Deprecated
    private boolean sentinelMode = false;

    private RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration();
    private RedisSentinelConfiguration sentinel = new RedisSentinelConfiguration();

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getMaxTotal() {
        return maxTotal;
    }

    public void setMaxTotal(int maxTotal) {
        this.maxTotal = maxTotal;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public long getTimeToLiveSeconds() {
        return timeToLiveSeconds;
    }

    public void setTimeToLiveSeconds(long timeToLiveSeconds) {
        this.timeToLiveSeconds = timeToLiveSeconds;
    }

    public long getTimeout() {
        return timeout;
    }

    public void setTimeout(long timeout) {
        this.timeout = timeout;
    }

    public boolean isReleaseCache() {
        return releaseCache;
    }

    public void setReleaseCache(boolean releaseCache) {
        this.releaseCache = releaseCache;
    }

    public boolean isUseSsl() {
        return useSsl;
    }

    public void setUseSsl(boolean useSsl) {
        this.useSsl = useSsl;
    }

    @Deprecated
    public boolean isSentinelMode() {
        return sentinelMode;
    }

    @Deprecated
    public void setSentinelMode(boolean sentinelMode) {
        this.sentinelMode = sentinelMode;
    }

    public HostAndPort getStandalone() {
        return standalone;
    }

    public void setStandalone(RedisStandaloneConfiguration standalone) {
        this.standalone = standalone;
    }

    public RedisSentinelConfiguration getSentinel() {
        return sentinel;
    }

    public void setSentinel(RedisSentinelConfiguration sentinel) {
        this.sentinel = sentinel;
    }
}
