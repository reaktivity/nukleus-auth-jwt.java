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
package org.reaktivity.nukleus.oauth.internal;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.singletonList;
import static java.util.Collections.unmodifiableMap;
import static org.agrona.LangUtil.rethrowUnchecked;
import static org.jose4j.jwt.ReservedClaimNames.AUDIENCE;
import static org.jose4j.jwt.ReservedClaimNames.ISSUER;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import org.jose4j.jwk.JsonWebKey;
import org.jose4j.jwk.JsonWebKeySet;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.consumer.InvalidJwtException;
import org.jose4j.lang.JoseException;
import org.reaktivity.nukleus.oauth.internal.util.CopyOnWriteHashMap;

public class OAuthRealms
{
    private static final List<String> EMPTY_STRING_LIST = Collections.emptyList();
    private static final String SCOPE_CLAIM = "scope";
    private static final Long NO_AUTHORIZATION = 0L;

    // To optimize authorization checks we use a single distinct bit per realm and per scope
    private static final int MAX_REALMS = Short.SIZE;

    private static final long REALM_MASK = 0xFFFF_000000000000L;

    private final Map<String, OAuthRealm> realmsByName = new CopyOnWriteHashMap<>();

    private int nextRealmBit = 0;

    private final Map<String, JsonWebKey> keysByKid;

    public OAuthRealms()
    {
        this(Collections.emptyMap());
    }

    public OAuthRealms(
        Map<String, JsonWebKey> keysByKid)
    {
        this.keysByKid = keysByKid;
    }

    public long resolve(
        String realmName,
        String issuerName,
        String audienceName,
        List<String> scopeNames)
    {
        long authorization = NO_AUTHORIZATION;
        if (nextRealmBit < MAX_REALMS)
        {
            final OAuthRealm realm = realmsByName.computeIfAbsent(realmName, OAuthRealm::new);
            authorization = realm.resolve(issuerName, audienceName, scopeNames);
        }
        return authorization;
    }

    public long resolve(
        String realmName)
    {
        return resolve(realmName, null, null, EMPTY_STRING_LIST);
    }

    @SuppressWarnings("unchecked")
    public long lookup(
        JsonWebSignature verified)
    {
        final OAuthRealm realm = realmsByName.get(verified.getKeyIdHeaderValue());
        long authorization = NO_AUTHORIZATION;
        if (realm != null)
        {
            try
            {
                final JwtClaims claims = JwtClaims.parse(verified.getPayload());
                final Object issuerClaim = claims.getClaimValue(ISSUER);
                final Object audienceClaim = claims.getClaimValue(AUDIENCE);
                final Object scopeClaim = claims.getClaimValue(SCOPE_CLAIM);

                final String issuerName = issuerClaim != null ? issuerClaim.toString() : null;

                List<String> audienceNames = EMPTY_STRING_LIST;
                if (audienceClaim instanceof List)
                {
                    audienceNames = (List<String>) audienceClaim;
                }
                else if (audienceClaim instanceof String)
                {
                    audienceNames = singletonList((String) audienceClaim);
                }

                List<String> scopeNames = EMPTY_STRING_LIST;
                if (scopeClaim != null)
                {
                    scopeNames = Arrays.asList(scopeClaim.toString().split("\\s+"));
                }

                authorization = realm.lookup(issuerName, audienceNames, scopeNames);
            }
            catch (JoseException | InvalidJwtException e)
            {
                // TODO: diagnostics?
            }
        }
        return authorization;
    }

    public boolean unresolve(
        long authorization)
    {
        final long realmId = authorization & REALM_MASK;
        final Collection<OAuthRealm> realms = realmsByName.values();
        final OAuthRealm realm = realms.stream()
                                       .filter(rs -> rs.unresolve(realmId))
                                       .findFirst()
                                       .orElse(null);
        realms.removeIf(OAuthRealm::isEmpty);
        return Long.bitCount(realmId) <= 1 && realm != null;
    }

    public JsonWebKey lookupKey(
        String kid)
    {
        return keysByKid.get(kid);
    }

    static Map<String, JsonWebKey> parseKeyMap(
        Path keyFile)
    {
        Map<String, JsonWebKey> keysByKid = Collections.emptyMap();

        if (Files.exists(keyFile))
        {
            try
            {
                byte[] rawKeys = Files.readAllBytes(keyFile);
                String keysAsJwkSet = new String(rawKeys, UTF_8);
                keysByKid = toKeyMap(keysAsJwkSet);
            }
            catch (IOException ex)
            {
                rethrowUnchecked(ex);
            }
        }

        return keysByKid;
    }

    private static Map<String, JsonWebKey> toKeyMap(
        String keysAsJwkSet)
    {
        Map<String, JsonWebKey> keysByKid = Collections.emptyMap();

        try
        {
            JsonWebKeySet keys = new JsonWebKeySet(keysAsJwkSet);
            keysByKid = new LinkedHashMap<>();
            for (JsonWebKey key : keys.getJsonWebKeys())
            {
                String kid = key.getKeyId();
                if (kid == null)
                {
                    throw new IllegalArgumentException("Key without kid");
                }

                if (key.getAlgorithm() == null)
                {
                    throw new IllegalArgumentException("Key without alg");
                }

                final JsonWebKey existingKey = keysByKid.putIfAbsent(kid, key);
                if (existingKey != null)
                {
                    throw new IllegalArgumentException("Key with duplicate kid");
                }
            }
            keysByKid = unmodifiableMap(keysByKid);
        }
        catch (JoseException ex)
        {
            rethrowUnchecked(ex);
        }

        return keysByKid;
    }

    private final class OAuthRealm
    {
        private static final int MAX_SCOPES = 48;

        private final List<OAuthRealmInfo> realmInfos = new CopyOnWriteArrayList<>();

        private final String realmName;

        private int nextScopeBit;

        private OAuthRealm(
            String realmName)
        {
            assert nextRealmBit < MAX_REALMS;
            this.realmName = realmName;
        }

        private long resolve(
            String issuerName,
            String audienceName,
            List<String> scopeNames)
        {
            long authorization = NO_AUTHORIZATION;
            assert nextRealmBit < MAX_REALMS;
            final int scopeNamesSize = scopeNames != null ? scopeNames.size() : 0;
            if (nextScopeBit + scopeNamesSize < MAX_SCOPES)
            {
                final OAuthRealmInfo realmInfo = realmInfos.stream()
                                                           .filter(r -> r.containsClaims(issuerName, audienceName))
                                                           .findFirst()
                                                           .orElseGet(() -> newRealmInfo(issuerName, audienceName));
                authorization = realmInfo.realmId;
                for (int i = 0; i < scopeNamesSize; i++)
                {
                    authorization |= realmInfo.supplyScopeBit(scopeNames.get(i));
                }
            }
            return authorization;
        }

        private long lookup(
            String issuerName,
            List<String> audienceNames,
            List<String> scopeNames)
        {
            final OAuthRealmInfo realmInfo = realmInfos.stream()
                                                       .filter(r -> r.containsClaims(issuerName, audienceNames))
                                                       .findFirst()
                                                       .orElse(null);
            long authorization = NO_AUTHORIZATION;
            if (realmInfo != null)
            {
                authorization = realmInfo.realmId;
                for (int i = 0; i < scopeNames.size(); i++)
                {
                    authorization |= realmInfo.scopeBit(scopeNames.get(i));
                }
            }
            return authorization;
        }

        private boolean unresolve(
            long realmId)
        {
            return realmInfos.removeIf(i -> i.realmId == realmId);
        }

        private boolean isEmpty()
        {
            return realmInfos.isEmpty();
        }

        private OAuthRealmInfo newRealmInfo(
            String issuerName,
            String audienceName)
        {
            OAuthRealmInfo realmInfo =
                    new OAuthRealmInfo(1L << nextRealmBit++ << MAX_SCOPES, issuerName, audienceName);
            realmInfos.add(realmInfo);
            return realmInfo;
        }

        @Override
        public String toString()
        {
            return String.format("Realm name: \"%s\",\tRealm info: %s\n",
                    realmName, realmInfos);
        }

        private final class OAuthRealmInfo
        {
            private final Map<String, Long> scopeBitsByName = new CopyOnWriteHashMap<>();

            private final long realmId;
            private final Claims requiredClaims;

            private OAuthRealmInfo(
                long realmId,
                String issuerName,
                String audienceName)
            {
                this.realmId = realmId;
                this.requiredClaims = new Claims(issuerName, audienceName);
            }

            private long scopeBit(
                String scopeName)
            {
                return scopeBitsByName.getOrDefault(scopeName, 0L);
            }

            private long supplyScopeBit(
                String scopeName)
            {
                return scopeBitsByName.computeIfAbsent(scopeName, this::assignScopeBit);
            }

            private boolean containsClaims(
                String issuerName,
                String audienceName)
            {
                return requiredClaims.containsClaims(issuerName, audienceName);
            }

            private boolean containsClaims(
                String issuerName,
                List<String> audienceNames)
            {
                return requiredClaims.containsClaims(issuerName, audienceNames);
            }

            private long assignScopeBit(
                String scopeName)
            {
                assert nextScopeBit < MAX_SCOPES;
                return 1L << nextScopeBit++;
            }

            @Override
            public String toString()
            {
                return String.format("Info: realm id=%d, claims=[%s], scope bits=%s",
                        realmId, requiredClaims, this.scopeBitsByName);
            }

            private final class Claims
            {
                final String issuerName;
                final String audienceName;

                private Claims(
                    String issuerName,
                    String audienceName)
                {
                    this.issuerName = issuerName;
                    this.audienceName = audienceName;
                }

                private boolean containsClaims(
                    String issuerName,
                    List<String> audienceNames)
                {
                    return (this.issuerName == null || Objects.equals(this.issuerName, issuerName)) &&
                            (this.audienceName == null || audienceNames.contains(audienceName));
                }

                private boolean containsClaims(
                    String issuerName,
                    String audienceName)
                {
                    return (this.issuerName == null || Objects.equals(this.issuerName, issuerName)) &&
                            (this.audienceName == null || Objects.equals(this.audienceName, audienceName));
                }

                @Override
                public String toString()
                {
                    return String.format("issuer=\"%s\", audience=\"%s\"", issuerName, audienceName);
                }
            }
        }
    }
}
