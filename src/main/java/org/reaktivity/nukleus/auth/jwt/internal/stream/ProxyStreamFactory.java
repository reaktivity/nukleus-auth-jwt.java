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
package org.reaktivity.nukleus.auth.jwt.internal.stream;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.Objects.requireNonNull;

import java.util.function.LongSupplier;
import java.util.function.ToLongFunction;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.collections.Long2ObjectHashMap;
import org.reaktivity.nukleus.auth.jwt.internal.stream.ProxyStreamFactoryBuilder.Correlation;
import org.reaktivity.nukleus.auth.jwt.internal.types.OctetsFW;
import org.reaktivity.nukleus.auth.jwt.internal.types.String16FW;
import org.reaktivity.nukleus.auth.jwt.internal.types.control.RouteFW;
import org.reaktivity.nukleus.auth.jwt.internal.types.stream.AbortFW;
import org.reaktivity.nukleus.auth.jwt.internal.types.stream.BeginFW;
import org.reaktivity.nukleus.auth.jwt.internal.types.stream.DataFW;
import org.reaktivity.nukleus.auth.jwt.internal.types.stream.EndFW;
import org.reaktivity.nukleus.auth.jwt.internal.types.stream.HttpBeginExFW;
import org.reaktivity.nukleus.auth.jwt.internal.types.stream.ResetFW;
import org.reaktivity.nukleus.auth.jwt.internal.types.stream.WindowFW;
import org.reaktivity.nukleus.auth.jwt.internal.util.BufferUtil;
import org.reaktivity.nukleus.auth.jwt.internal.util.JwtValidator;
import org.reaktivity.nukleus.function.MessageConsumer;
import org.reaktivity.nukleus.function.MessagePredicate;
import org.reaktivity.nukleus.route.RouteManager;
import org.reaktivity.nukleus.stream.StreamFactory;

public class ProxyStreamFactory implements StreamFactory
{
    private static final byte[] BEARER_PREFIX = "Bearer ".getBytes(US_ASCII);
    private static final byte[] AUTHORIZATION = "authorization".getBytes(US_ASCII);
    private final BeginFW beginRO = new BeginFW();
    private final HttpBeginExFW httpBeginExRO = new HttpBeginExFW();
    private final DataFW dataRO = new DataFW();
    private final EndFW endRO = new EndFW();
    private final RouteFW routeRO = new RouteFW();

    private final WindowFW windowRO = new WindowFW();
    private final ResetFW resetRO = new ResetFW();
    private final AbortFW abortRO = new AbortFW();

    private final RouteManager router;

    private final LongSupplier supplyStreamId;
    private final LongSupplier supplyCorrelationId;
    private final ToLongFunction<String> supplyRealmId;

    private final Long2ObjectHashMap<Correlation> correlations;
    private final Writer writer;
    private final JwtValidator validator;

    public ProxyStreamFactory(
        RouteManager router,
        MutableDirectBuffer writeBuffer,
        LongSupplier supplyStreamId,
        LongSupplier supplyCorrelationId,
        Long2ObjectHashMap<Correlation> correlations,
        ToLongFunction<String> supplyRealmId,
        JwtValidator validator)
    {
        this.router = requireNonNull(router);
        this.writer = new Writer(writeBuffer);
        this.supplyStreamId = requireNonNull(supplyStreamId);
        this.supplyCorrelationId = requireNonNull(supplyCorrelationId);
        this.correlations = correlations;
        this.supplyRealmId = supplyRealmId;
        this.validator = validator;
    }

    @Override
    public MessageConsumer newStream(
            int msgTypeId,
            DirectBuffer buffer,
            int index,
            int length,
            MessageConsumer throttle)
    {
        final BeginFW begin = beginRO.wrap(buffer, index, index + length);
        final long sourceRef = begin.sourceRef();

        MessageConsumer newStream;

        if (sourceRef == 0L)
        {
            newStream = newConnectReplyStream(begin, throttle);
        }
        else
        {
            newStream = newAcceptStream(begin, throttle);
        }

        return newStream;
    }

    private MessageConsumer newAcceptStream(
            final BeginFW begin,
            final MessageConsumer networkThrottle)
    {
        final long sourceRef = begin.sourceRef();
        final String acceptName = begin.source().asString();
        long authorization = authorize(begin);

        final MessagePredicate filter = (t, b, o, l) ->
        {
            final RouteFW route = routeRO.wrap(b, o, l);
            return sourceRef == route.sourceRef() &&
                    acceptName.equals(route.source().asString());
        };

        final RouteFW route = router.resolve(authorization, filter, this::wrapRoute);

        MessageConsumer newStream = null;

        if (route != null)
        {
            final long networkId = begin.streamId();

            newStream = new ProxyAcceptStream(networkThrottle, networkId,
                    authorization, route.target().asString(), route.targetRef())::handleStream;
        }

        return newStream;
    }

    private long authorize(
        BeginFW begin)
    {
        long[] authorization = {0L};
        final HttpBeginExFW beginEx = begin.extension().get(httpBeginExRO::wrap);
        beginEx.headers().forEach(h ->
        {
            if (BufferUtil.equals(h.name(), AUTHORIZATION))
            {
                String16FW authorizationHeader = h.value();
                final DirectBuffer buffer = authorizationHeader.buffer();
                final int limit = authorizationHeader.limit();
                int offset = BufferUtil.limitOfBytes(buffer, authorizationHeader.offset(),
                        limit, BEARER_PREFIX);
                if (offset > 0)
                {
                    String token = buffer.getStringWithoutLengthUtf8(offset, limit - offset);
                    String realm = validator.validateAndGetRealm(token);
                    if (realm != null)
                    {
                        authorization[0] = supplyRealmId.applyAsLong(realm);
                    }
                }
            }
        });
         return authorization[0];
    }

    private MessageConsumer newConnectReplyStream(
            final BeginFW begin,
            final MessageConsumer throttle)
    {
        final long throttleId = begin.streamId();

        return new ProxyConnectReplyStream(throttle, throttleId)::handleStream;
    }

    private RouteFW wrapRoute(
            int msgTypeId,
            DirectBuffer buffer,
            int index,
            int length)
    {
        return routeRO.wrap(buffer, index, index + length);
    }

    abstract class ProxyStream
    {
        private final MessageConsumer sourceThrottle;
        private final long sourceStreamId;

        MessageConsumer target;
        long targetStreamId;

        private MessageConsumer streamState;

        private ProxyStream(
                MessageConsumer sourceThrottle,
                long sourceStreamId)
        {
            this.sourceThrottle = sourceThrottle;
            this.sourceStreamId = sourceStreamId;
            this.streamState = this::beforeBegin;
        }

        void handleStream(
                int msgTypeId,
                DirectBuffer buffer,
                int index,
                int length)
        {
            streamState.accept(msgTypeId, buffer, index, length);
        }

        private void beforeBegin(
                int msgTypeId,
                DirectBuffer buffer,
                int index,
                int length)
        {
            if (msgTypeId == BeginFW.TYPE_ID)
            {
                final BeginFW begin = beginRO.wrap(buffer, index, index + length);
                handleBegin(begin);
            }
            else
            {
                writer.doReset(sourceThrottle, sourceStreamId);
            }
        }

        private void handleBegin(BeginFW begin)
        {
            doHandleBegin(begin);
            this.streamState = this::afterBegin;
        }

        abstract void doHandleBegin(
            BeginFW begin);

        private void afterBegin(
                int msgTypeId,
                DirectBuffer buffer,
                int index,
                int length)
        {
            switch (msgTypeId)
            {
            case DataFW.TYPE_ID:
                final DataFW data = dataRO.wrap(buffer, index, index + length);
                handleData(data);
                break;
            case EndFW.TYPE_ID:
                final EndFW end = endRO.wrap(buffer, index, index + length);
                handleEnd(end);
                break;
            case AbortFW.TYPE_ID:
                final AbortFW abort = abortRO.wrap(buffer, index, index + length);
                handleAbort(abort);
                break;
            default:
                writer.doReset(sourceThrottle, sourceStreamId);
                break;
            }
        }

        private void handleData(
                DataFW data)
        {
            final OctetsFW payload = data.payload();
            writer.doData(target, targetStreamId, payload.buffer(), payload.offset(), payload.sizeof(),
                    data.extension());
        }

        private void handleEnd(
                EndFW end)
        {
            writer.doEnd(target, targetStreamId, end.extension());
        }

        private void handleAbort(
                AbortFW abort)
        {
            writer.doAbort(target, targetStreamId);
        }

        private void handleTargetThrottle(
                int msgTypeId,
                DirectBuffer buffer,
                int index,
                int length)
        {
            switch (msgTypeId)
            {
                case WindowFW.TYPE_ID:
                    final WindowFW window = windowRO.wrap(buffer, index, index + length);
                    handleConnectWindow(window);
                    break;
                case ResetFW.TYPE_ID:
                    resetRO.wrap(buffer, index, index + length);
                    resetSource();
                    break;
                default:
                    // ignore
                    break;
            }
        }

        private void handleConnectWindow(
            WindowFW window)
        {
            final int bytes = windowRO.update();
            final int frames = windowRO.frames();

            writer.doWindow(sourceThrottle, sourceStreamId, bytes, frames);
        }

        void resetSource()
        {
            writer.doReset(sourceThrottle, sourceStreamId);
        }

    }

    private final class ProxyAcceptStream extends ProxyStream
    {
        private final long authorization;

        private final String connectName;
        private final long connectRef;

        private ProxyAcceptStream(
                MessageConsumer acceptThrottle,
                long acceptStreamId,
                long authorization,
                String connectName,
                long connectRef)
        {
            super(acceptThrottle, acceptStreamId);
            this.authorization = authorization;
            this.connectName = connectName;
            this.connectRef = connectRef;
        }

        @Override
        void doHandleBegin(
            BeginFW begin)
        {
            Correlation correlation = new Correlation();
            correlation.acceptName = begin.source().asString();
            correlation.acceptCorrelationId = begin.correlationId();
            long newCorrelationId = ProxyStreamFactory.this.supplyCorrelationId.getAsLong();
            correlations.put(newCorrelationId, correlation);

            target = router.supplyTarget(connectName);
            targetStreamId = supplyStreamId.getAsLong();

            writer.doBegin(target, targetStreamId, connectRef, newCorrelationId,
                    authorization, begin.extension());

            router.setThrottle(connectName, targetStreamId, super::handleTargetThrottle);
        }

    }

    private final class ProxyConnectReplyStream extends ProxyStream
    {
        private ProxyConnectReplyStream(
                MessageConsumer connectReplyThrottle,
                long connectReplyId)
        {
            super(connectReplyThrottle, connectReplyId);
        }

        @Override
        void doHandleBegin(
                BeginFW begin)
        {
            final long connectCorrelationId = begin.correlationId();

            Correlation correlation = correlations.remove(connectCorrelationId);

            if (correlation != null)
            {
                final String acceptName = correlation.acceptName;
                target = router.supplyTarget(acceptName);
                targetStreamId = supplyStreamId.getAsLong();
                writer.doBegin(target, targetStreamId, 0L,
                        correlation.acceptCorrelationId, begin.authorization(), begin.extension());
                router.setThrottle(acceptName, targetStreamId, super::handleTargetThrottle);
            }
            else
            {
                resetSource();
            }
        }
    }

}
