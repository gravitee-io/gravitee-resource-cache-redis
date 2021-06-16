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

    private String name = "redis";
    private int maxTotal = 256;
    private String password;
    private long timeToLiveSeconds = 0;
    private long timeout = 2000;
    private boolean useSsl = true;
    private boolean sentinelMode = false;
    private HostAndPort standalone = new HostAndPort();
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

    public boolean isUseSsl() {
        return useSsl;
    }

    public void setUseSsl(boolean useSsl) {
        this.useSsl = useSsl;
    }

    public boolean isSentinelMode() {
        return sentinelMode;
    }

    public void setSentinelMode(boolean sentinelMode) {
        this.sentinelMode = sentinelMode;
    }

    public HostAndPort getStandalone() {
        return standalone;
    }

    public void setStandalone(HostAndPort standalone) {
        this.standalone = standalone;
    }

    public RedisSentinelConfiguration getSentinel() {
        return sentinel;
    }

    public void setSentinel(RedisSentinelConfiguration sentinel) {
        this.sentinel = sentinel;
    }
}
