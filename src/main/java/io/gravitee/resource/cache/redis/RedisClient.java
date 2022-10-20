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
package io.gravitee.resource.cache.redis;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.redis.client.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public class RedisClient {

    private final Logger logger = LoggerFactory.getLogger(RedisClient.class);

    private final long RECONNECT_INTERVAL = 5 * 1000;

    private final Vertx vertx;
    private final RedisOptions options;
    private RedisConnection redisConnection;

    public RedisClient(Vertx vertx, RedisOptions options) {
        this.vertx = vertx;
        this.options = options;

        createRedisClient();
    }

    private Future<RedisConnection> createRedisClient() {
        Promise<RedisConnection> promise = Promise.promise();

        Redis
            .createClient(vertx, options)
            .connect(event -> {
                if (event.succeeded()) {
                    RedisConnection connection = event.result();
                    this.redisConnection = connection;
                    long periodic = vertx.setPeriodic(
                        RECONNECT_INTERVAL,
                        event1 -> connection.send(Request.cmd(Command.PING), event12 -> {})
                    );

                    // make sure the client is reconnected on error
                    connection.exceptionHandler(e -> {
                        vertx.cancelTimer(periodic);
                        this.redisConnection.close();

                        // attempt to reconnect,
                        // if there is an unrecoverable error
                        attemptReconnect(0);
                    });

                    // allow further processing
                    promise.complete(connection);
                } else {
                    attemptReconnect(0);
                }
            });

        return promise.future();
    }

    /**
     * Attempt to reconnect
     */
    private void attemptReconnect(int retry) {
        // retry with backoff up to 10240 ms
        long backoff = (long) (Math.pow(2, Math.min(retry, 10)) * 10);
        vertx.setTimer(backoff, timer -> createRedisClient().onFailure(t -> attemptReconnect(retry + 1)));
    }

    public RedisConnection getConnection() {
        return redisConnection;
    }
}
