/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sshd.client.config.hosts;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

import org.apache.sshd.client.ClientFactoryManager;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.config.keys.ClientIdentityLoader;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.FactoryManagerUtils;
import org.apache.sshd.common.SshdSocketAddress;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.io.IoSession;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.util.ValidateUtils;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.password.RejectAllPasswordAuthenticator;
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator;
import org.apache.sshd.server.session.ServerSession;
import org.apache.sshd.util.BaseTestSupport;
import org.apache.sshd.util.Utils;
import org.junit.After;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * @author <a href="mailto:dev@mina.apache.org">Apache MINA SSHD Project</a>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class HostConfigEntryResolverTest extends BaseTestSupport {
    private SshServer sshd;
    private SshClient client;
    private int port;

    public HostConfigEntryResolverTest() {
        super();
    }

    @Before
    public void setUp() throws Exception {
        sshd = Utils.setupTestServer();
        sshd.start();
        port = sshd.getPort();

        client = Utils.setupTestClient();
    }

    @After
    public void tearDown() throws Exception {
        if (sshd != null) {
            sshd.stop(true);
        }
        if (client != null) {
            client.stop();
        }
    }

    @Test
    public void testEffectiveHostConfigResolution() throws Exception {
        final HostConfigEntry entry = new HostConfigEntry(getCurrentTestName(), "localhost", port, getCurrentTestName());
        client.setHostConfigEntryResolver(new HostConfigEntryResolver() {
            @Override
            public HostConfigEntry resolveEffectiveHost(String host, int portValue, String username) throws IOException {
                return entry;
            }
        });
        client.start();

        try(ClientSession session = client.connect(getClass().getSimpleName(), getClass().getPackage().getName(), getMovedPortNumber(port)).verify(7L, TimeUnit.SECONDS).getSession()) {
            session.addPasswordIdentity(getCurrentTestName());
            session.auth().verify(5L, TimeUnit.SECONDS);
            assertEffectiveRemoteAddress(session, entry);
        } finally {
            client.stop();
        }
    }

    @Test
    public void testPreloadedIdentities() throws Exception {
        KeyPairProvider provider = ValidateUtils.checkNotNull(sshd.getKeyPairProvider(), "No key pair provider");
        Iterable<? extends KeyPair> pairs = ValidateUtils.checkNotNull(provider.loadKeys(), "No loaded keys");
        Iterator<? extends KeyPair> iter = ValidateUtils.checkNotNull(pairs.iterator(), "No keys iterator");
        assertTrue("Empty loaded kyes iterator", iter.hasNext());

        final KeyPair identity = iter.next();
        final String USER = getCurrentTestName();
        // make sure authentication is achieved only via the identity public key
        sshd.setPublickeyAuthenticator(new PublickeyAuthenticator() {
            @Override
            public boolean authenticate(String username, PublicKey key, ServerSession session) {
                if (USER.equals(username)) {
                    return KeyUtils.compareKeys(identity.getPublic(), key);
                }

                return false;
            }
        });
        sshd.setPasswordAuthenticator(RejectAllPasswordAuthenticator.INSTANCE);

        final String IDENTITY = getCurrentTestName();
        client.setClientIdentityLoader(new ClientIdentityLoader() {
            @Override
            public boolean isValidLocation(String location) throws IOException {
                return IDENTITY.equals(location);
            }

            @Override
            public KeyPair loadClientIdentity(String location, FilePasswordProvider provider) throws IOException, GeneralSecurityException {
                if (isValidLocation(location)) {
                    return identity;
                }

                throw new FileNotFoundException("Unknown location: " + location);
            }
        });
        FactoryManagerUtils.updateProperty(client, ClientFactoryManager.IGNORE_INVALID_IDENTITIES, false);

        final String HOST = getClass().getSimpleName();
        final HostConfigEntry entry = new HostConfigEntry(HOST, "localhost", port, USER);
        entry.addIdentity(IDENTITY);
        client.setHostConfigEntryResolver(new HostConfigEntryResolver() {
            @Override
            public HostConfigEntry resolveEffectiveHost(String host, int portValue, String username) throws IOException {
                return entry;
            }
        });

        client.start();
        try(ClientSession session = client.connect(USER, HOST, getMovedPortNumber(port)).verify(7L, TimeUnit.SECONDS).getSession()) {
            session.auth().verify(5L, TimeUnit.SECONDS);
            assertEffectiveRemoteAddress(session, entry);
        } finally {
            client.stop();
        }

    }

    private static int getMovedPortNumber(int port) {
        return (port > Short.MAX_VALUE) ? (port - Short.MAX_VALUE) : (1 + Short.MAX_VALUE - port);
    }

    private static <S extends Session> S assertEffectiveRemoteAddress(S session, HostConfigEntry entry) {
        IoSession ioSession = session.getIoSession();
        SocketAddress remoteAddress = ioSession.getRemoteAddress();
        InetSocketAddress inetAddress;
        if (remoteAddress instanceof InetSocketAddress) {
            inetAddress = (InetSocketAddress) remoteAddress;
        } else if (remoteAddress instanceof SshdSocketAddress) {
            inetAddress = ((SshdSocketAddress) remoteAddress).toInetSocketAddress();
        } else {
            throw new ClassCastException("Unknown remote address type: " + remoteAddress);
        }

        assertEquals("Mismatched effective port", entry.getPort(), inetAddress.getPort());
        assertEquals("Mismatched effective user", entry.getUsername(), session.getUsername());
        return session;
    }
}
