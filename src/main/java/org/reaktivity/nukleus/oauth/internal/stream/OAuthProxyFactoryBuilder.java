/**
 * Copyright 2016-2021 The Reaktivity Project
 *
 * The Reaktivity Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.reaktivity.nukleus.oauth.internal.stream;

import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.LongUnaryOperator;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;

import org.agrona.MutableDirectBuffer;
import org.jose4j.jwk.JsonWebKey;
import org.jose4j.jws.JsonWebSignature;
import org.reaktivity.nukleus.buffer.BufferPool;
import org.reaktivity.nukleus.concurrent.Signaler;
import org.reaktivity.nukleus.oauth.internal.OAuthConfiguration;
import org.reaktivity.nukleus.route.RouteManager;
import org.reaktivity.nukleus.stream.StreamFactory;
import org.reaktivity.nukleus.stream.StreamFactoryBuilder;

public class OAuthProxyFactoryBuilder implements StreamFactoryBuilder
{
    private final OAuthConfiguration config;
    private final Function<String, JsonWebKey> lookupKey;
    private final ToLongFunction<JsonWebSignature> lookupAuthorization;

    private RouteManager router;
    private MutableDirectBuffer writeBuffer;
    private LongUnaryOperator supplyInitialId;
    private LongUnaryOperator supplyReplyId;
    private ToIntFunction<String> supplyTypeId;
    private LongSupplier supplyTraceId;
    private Signaler signaler;

    public OAuthProxyFactoryBuilder(
        OAuthConfiguration config,
        ToLongFunction<JsonWebSignature> lookupAuthorization,
        Function<String, JsonWebKey> lookupKey)
    {
        this.config = config;
        this.lookupKey = lookupKey;
        this.lookupAuthorization = lookupAuthorization;
    }

    @Override
    public OAuthProxyFactoryBuilder setRouteManager(
        RouteManager router)
    {
        this.router = router;
        return this;
    }

    @Override
    public StreamFactoryBuilder setTraceIdSupplier(
        LongSupplier supplyTraceId)
    {
        this.supplyTraceId = supplyTraceId;
        return this;
    }

    @Override
    public StreamFactoryBuilder setTypeIdSupplier(
        ToIntFunction<String> supplyTypeId)
    {
        this.supplyTypeId = supplyTypeId;
        return this;
    }

    @Override
    public OAuthProxyFactoryBuilder setWriteBuffer(
        MutableDirectBuffer writeBuffer)
    {
        this.writeBuffer = writeBuffer;
        return this;
    }

    @Override
    public OAuthProxyFactoryBuilder setInitialIdSupplier(
        LongUnaryOperator supplyInitialId)
    {
        this.supplyInitialId = supplyInitialId;
        return this;
    }

    @Override
    public StreamFactoryBuilder setReplyIdSupplier(
        LongUnaryOperator supplyReplyId)
    {
        this.supplyReplyId = supplyReplyId;
        return this;
    }

    @Override
    public StreamFactoryBuilder setBufferPoolSupplier(
        Supplier<BufferPool> supplyBufferPool)
    {
        return this;
    }

    @Override
    public StreamFactoryBuilder setSignaler(
        Signaler signaler)
    {
        this.signaler = signaler;
        return this;
    }

    @Override
    public StreamFactory build()
    {
        return new OAuthProxyFactory(
            config,
            writeBuffer,
            supplyInitialId,
            supplyTraceId,
            supplyTypeId,
            supplyReplyId,
            lookupKey,
            lookupAuthorization,
            signaler,
            router
        );
    }
}
