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

import io.gravitee.el.TemplateEngine;
import io.gravitee.gateway.api.buffer.Buffer;
import io.gravitee.gateway.reactive.api.ExecutionFailure;
import io.gravitee.gateway.reactive.api.context.*;
import io.gravitee.gateway.reactive.api.message.Message;
import io.gravitee.gateway.reactive.api.tracing.Tracer;
import io.gravitee.reporter.api.v4.metric.Metrics;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;

/**
 * @author Benoit BORDIGONI (benoit.bordigoni at graviteesource.com)
 * @author GraviteeSource Team
 */
@RequiredArgsConstructor
public class ExecutionContextAdapter implements ExecutionContext {

    final DeploymentContext deploymentContext;

    @Override
    public TemplateEngine getTemplateEngine() {
        return deploymentContext.getTemplateEngine();
    }

    @Override
    public Request request() {
        return null;
    }

    @Override
    public Response response() {
        return null;
    }

    @Override
    public Completable interrupt() {
        return null;
    }

    @Override
    public Completable interruptWith(ExecutionFailure failure) {
        return Completable.error(
            new AssertionError("should not interrupt: key=".concat(failure.key()).concat(", message: ").concat(failure.message()))
        );
    }

    @Override
    public Maybe<Buffer> interruptBody() {
        return null;
    }

    @Override
    public Maybe<Buffer> interruptBodyWith(ExecutionFailure failure) {
        return null;
    }

    @Override
    public Flowable<Message> interruptMessagesWith(ExecutionFailure failure) {
        return null;
    }

    @Override
    public Maybe<Message> interruptMessageWith(ExecutionFailure failure) {
        return null;
    }

    @Override
    public Metrics metrics() {
        return null;
    }

    @Override
    public <T> T getComponent(Class<T> componentClass) {
        return null;
    }

    @Override
    public void setAttribute(String name, Object value) {}

    @Override
    public void putAttribute(String name, Object value) {}

    @Override
    public void removeAttribute(String name) {}

    @Override
    public <T> T getAttribute(String name) {
        return null;
    }

    @Override
    public <T> List<T> getAttributeAsList(String name) {
        return null;
    }

    @Override
    public Set<String> getAttributeNames() {
        return null;
    }

    @Override
    public <T> Map<String, T> getAttributes() {
        return null;
    }

    @Override
    public void setInternalAttribute(String name, Object value) {}

    @Override
    public void putInternalAttribute(String name, Object value) {}

    @Override
    public void removeInternalAttribute(String name) {}

    @Override
    public <T> T getInternalAttribute(String name) {
        return null;
    }

    @Override
    public <T> Map<String, T> getInternalAttributes() {
        return null;
    }

    @Override
    public Tracer getTracer() {
        return null;
    }

    @Override
    public long timestamp() {
        return 0;
    }

    @Override
    public String remoteAddress() {
        return null;
    }

    @Override
    public String localAddress() {
        return null;
    }

    @Override
    public TlsSession tlsSession() {
        return null;
    }

    @Override
    public Flowable<Message> interruptMessages() {
        return null;
    }

    @Override
    public Maybe<Message> interruptMessage() {
        return null;
    }

    @Override
    public TemplateEngine getTemplateEngine(Message message) {
        return getTemplateEngine();
    }
}
