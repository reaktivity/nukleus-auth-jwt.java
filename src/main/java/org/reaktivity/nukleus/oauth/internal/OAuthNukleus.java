/**
 * Copyright 2016-2020 The Reaktivity Project
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
package org.reaktivity.nukleus.oauth.internal;

import java.nio.file.Path;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.collections.Int2ObjectHashMap;
import org.jose4j.jwk.JsonWebKey;
import org.reaktivity.nukleus.Nukleus;
import org.reaktivity.nukleus.function.CommandHandler;
import org.reaktivity.nukleus.function.MessageConsumer;
import org.reaktivity.nukleus.oauth.internal.types.Array32FW;
import org.reaktivity.nukleus.oauth.internal.types.String8FW;
import org.reaktivity.nukleus.oauth.internal.types.control.ErrorFW;
import org.reaktivity.nukleus.oauth.internal.types.control.OAuthResolveExFW;
import org.reaktivity.nukleus.oauth.internal.types.control.ResolveFW;
import org.reaktivity.nukleus.oauth.internal.types.control.ResolvedFW;
import org.reaktivity.nukleus.oauth.internal.types.control.UnresolveFW;
import org.reaktivity.nukleus.oauth.internal.types.control.UnresolvedFW;

final class OAuthNukleus implements Nukleus
{
    static final String NAME = "oauth";

    private final ResolveFW resolveRO = new ResolveFW();
    private final OAuthResolveExFW resolveExRO = new OAuthResolveExFW();
    private final ResolvedFW.Builder resolvedRW = new ResolvedFW.Builder();
    private final UnresolveFW unresolveRO = new UnresolveFW();
    private final UnresolvedFW.Builder unresolvedRW = new UnresolvedFW.Builder();
    private final ErrorFW.Builder errorRW = new ErrorFW.Builder();

    private final OAuthConfiguration config;
    private final OAuthRealms realms;
    private final Int2ObjectHashMap<CommandHandler> commandHandlers;

    OAuthNukleus(
        OAuthConfiguration config)
    {
        this.config = config;
        final Path keyFile = config.directory().resolve(name()).resolve(config.keyFileName());
        final Map<String, JsonWebKey> keysByKid = OAuthRealms.parseKeyMap(keyFile);
        final OAuthRealms realms = new OAuthRealms(keysByKid);

        if (config.autoDiscoverRealms())
        {
            keysByKid.keySet().forEach(realms::resolve);
        }

        final Int2ObjectHashMap<CommandHandler> commandHandlers = new Int2ObjectHashMap<>();
        commandHandlers.put(ResolveFW.TYPE_ID, this::onResolve);
        commandHandlers.put(UnresolveFW.TYPE_ID, this::onUnresolve);

        this.realms = realms;
        this.commandHandlers = commandHandlers;
    }

    @Override
    public String name()
    {
        return OAuthNukleus.NAME;
    }

    @Override
    public OAuthConfiguration config()
    {
        return config;
    }

    @Override
    public CommandHandler commandHandler(
        int msgTypeId)
    {
        return commandHandlers.get(msgTypeId);
    }

    @Override
    public OAuthElektron supplyElektron()
    {
        return new OAuthElektron(config, realms::lookup, realms::lookupKey);
    }

    private void onResolve(
        DirectBuffer buffer,
        int index,
        int length,
        MessageConsumer reply,
        MutableDirectBuffer replyBuffer)
    {
        final ResolveFW resolve = resolveRO.wrap(buffer, index, index + length);
        final long correlationId = resolve.correlationId();
        final String realm = resolve.realm().asString();

        String issuer = null;
        String audience = null;
        if (resolve.extension().sizeof() > 0)
        {
            final OAuthResolveExFW resolveEx =
                    resolveExRO.tryWrap(buffer, resolve.extension().offset(), resolve.extension().limit());
            if (resolveEx != null)
            {
                issuer = resolveEx.issuer().asString();
                audience = resolveEx.audience().asString();
            }
        }

        final Array32FW<String8FW> roles = resolve.roles();
        final List<String> collectedRoles = new LinkedList<>();
        roles.forEach(r -> collectedRoles.add(r.asString()));
        final long authorization = realms.resolve(realm, issuer, audience, collectedRoles);
        if (authorization != 0L)
        {
            final ResolvedFW resolved = resolvedRW.wrap(replyBuffer, 0, replyBuffer.capacity())
                    .correlationId(correlationId)
                    .authorization(authorization)
                    .build();

            reply.accept(resolved.typeId(), resolved.buffer(), resolved.offset(), resolved.sizeof());
        }
        else
        {
            final ErrorFW error = errorRW.wrap(replyBuffer, 0, replyBuffer.capacity())
                    .correlationId(correlationId)
                    .build();

            reply.accept(error.typeId(), error.buffer(), error.offset(), error.sizeof());
        }
    }

    private void onUnresolve(
        DirectBuffer buffer,
        int index,
        int length,
        MessageConsumer reply,
        MutableDirectBuffer replyBuffer)
    {
        final UnresolveFW unresolve = unresolveRO.wrap(buffer, index, index + length);
        final long correlationId = unresolve.correlationId();
        final long authorization = unresolve.authorization();

        if (realms.unresolve(authorization))
        {
            final UnresolvedFW unresolved = unresolvedRW.wrap(replyBuffer, 0,  replyBuffer.capacity())
                    .correlationId(correlationId)
                    .build();

            reply.accept(unresolved.typeId(), unresolved.buffer(), unresolved.offset(), unresolved.sizeof());
        }
        else
        {
            final ErrorFW error = errorRW.wrap(replyBuffer, 0,  replyBuffer.capacity())
                    .correlationId(correlationId)
                    .build();

            reply.accept(error.typeId(), error.buffer(), error.offset(), error.sizeof());
        }
    }
}
