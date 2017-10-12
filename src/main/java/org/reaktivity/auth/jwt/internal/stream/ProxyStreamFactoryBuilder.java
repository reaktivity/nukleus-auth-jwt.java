/**
 * Copyright 2016-2017 The Reaktivity Project
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
package org.reaktivity.auth.jwt.internal.stream;

import java.nio.file.Path;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

import org.agrona.MutableDirectBuffer;
import org.reaktivity.auth.jwt.internal.AuthJwtConfiguration;
import org.reaktivity.auth.jwt.internal.util.JwtValidator;
import org.reaktivity.nukleus.buffer.BufferPool;
import org.reaktivity.nukleus.route.RouteManager;
import org.reaktivity.nukleus.stream.StreamFactory;
import org.reaktivity.nukleus.stream.StreamFactoryBuilder;

public class ProxyStreamFactoryBuilder implements StreamFactoryBuilder
{

    private final AuthJwtConfiguration config;

    private RouteManager router;
    private MutableDirectBuffer writeBuffer;
    private LongSupplier supplyStreamId;
    private ToLongFunction<String> supplyRealmId;

    // TODO: inject from reactor
    private LongSupplier supplyCurrentTimeMillis = () -> System.currentTimeMillis();

    public ProxyStreamFactoryBuilder(
            AuthJwtConfiguration config)
    {
        this.config = config;
    }

    @Override
    public ProxyStreamFactoryBuilder setRouteManager(
        RouteManager router)
    {
        this.router = router;
        return this;
    }

    @Override
    public ProxyStreamFactoryBuilder setWriteBuffer(
        MutableDirectBuffer writeBuffer)
    {
        this.writeBuffer = writeBuffer;
        return this;
    }

    @Override
    public ProxyStreamFactoryBuilder setStreamIdSupplier(
        LongSupplier supplyStreamId)
    {
        this.supplyStreamId = supplyStreamId;
        return this;
    }

    @Override
    public ProxyStreamFactoryBuilder setCorrelationIdSupplier(
        LongSupplier supplyCorrelationId)
    {
        return this;
    }

    @Override
    public StreamFactoryBuilder setBufferPoolSupplier(
        Supplier<BufferPool> supplyBufferPool)
    {
        return this;
    }

    @Override
    public StreamFactoryBuilder setRealmIdSupplier(
        ToLongFunction<String> supplyRealmId)
    {
        this.supplyRealmId = supplyRealmId;
        return this;
    }

    @Override
    public StreamFactory build()
    {
        Path keyFile = config.directory().resolve(config.keyFileName());
        JwtValidator validator = new JwtValidator(keyFile, supplyCurrentTimeMillis);

        return new ProxyStreamFactory(
                router,
                writeBuffer,
                supplyStreamId,
                supplyRealmId,
                validator);
    }
}
