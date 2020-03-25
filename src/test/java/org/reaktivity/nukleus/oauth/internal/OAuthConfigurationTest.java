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

import static org.junit.Assert.assertEquals;
import static org.reaktivity.nukleus.oauth.internal.OAuthConfiguration.AUTO_DISCOVER_REALMS;
import static org.reaktivity.nukleus.oauth.internal.OAuthConfiguration.AUTO_DISCOVER_REALMS_NAME;
import static org.reaktivity.nukleus.oauth.internal.OAuthConfiguration.CLAIM_NAMESPACE;
import static org.reaktivity.nukleus.oauth.internal.OAuthConfiguration.CLAIM_NAMESPACE_NAME;
import static org.reaktivity.nukleus.oauth.internal.OAuthConfiguration.CLAIM_NAME_CHALLENGE_TIMEOUT;
import static org.reaktivity.nukleus.oauth.internal.OAuthConfiguration.CLAIM_NAME_CHALLENGE_TIMEOUT_NAME;
import static org.reaktivity.nukleus.oauth.internal.OAuthConfiguration.KEYS;
import static org.reaktivity.nukleus.oauth.internal.OAuthConfiguration.KEYS_NAME;

import java.util.Properties;

import org.junit.Test;
import org.reaktivity.nukleus.Configuration;

public class OAuthConfigurationTest
{
    @Test
    public void shouldMatchKeysConfigName()
    {
        assertEquals(KEYS_NAME, KEYS.name());
    }

    @Test
    public void shouldMatchAutoDiscoverRealmsConfigName()
    {
        assertEquals(AUTO_DISCOVER_REALMS_NAME, AUTO_DISCOVER_REALMS.name());
    }

    @Test
    public void shouldMatchClaimNamespaceConfigName()
    {
        assertEquals(CLAIM_NAMESPACE_NAME, CLAIM_NAMESPACE.name());
    }

    @Test
    public void shouldMatchClaimNameChallengeTimeoutConfigName()
    {
        assertEquals(CLAIM_NAME_CHALLENGE_TIMEOUT_NAME, CLAIM_NAME_CHALLENGE_TIMEOUT.name());
    }

    @Test
    public void shouldCanonicalizeNamespaceWithTrailingSlash()
    {
        final Properties properties = new Properties();
        properties.setProperty(CLAIM_NAMESPACE_NAME, "http://example.com/");
        final OAuthConfiguration config = new OAuthConfiguration(new Configuration(properties));

        assertEquals("http://example.com/", config.getCanonicalClaimNamespace());
    }

    @Test
    public void shouldCanonicalizeNamespaceWithoutTrailingSlash()
    {
        final Properties properties = new Properties();
        properties.setProperty(CLAIM_NAMESPACE_NAME, "http://example.com");
        final OAuthConfiguration config = new OAuthConfiguration(new Configuration(properties));

        assertEquals("http://example.com/", config.getCanonicalClaimNamespace());
    }

    @Test
    public void shouldCanonicalizeNamespaceWithPathWithTrailingSlash()
    {
        final Properties properties = new Properties();
        properties.setProperty(CLAIM_NAMESPACE_NAME, "http://example.com/path/");
        final OAuthConfiguration config = new OAuthConfiguration(new Configuration(properties));

        assertEquals("http://example.com/path/", config.getCanonicalClaimNamespace());
    }

    @Test
    public void shouldCanonicalizeNamespaceWithPathWithoutTrailingSlash()
    {
        final Properties properties = new Properties();
        properties.setProperty(CLAIM_NAMESPACE_NAME, "http://example.com/path");
        final OAuthConfiguration config = new OAuthConfiguration(new Configuration(properties));

        assertEquals("http://example.com/path/", config.getCanonicalClaimNamespace());
    }
}
