/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.wireguard.config;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class InterfaceExtensionTest {
    private static final String BASE = "[Interface]\n"
            + "PrivateKey = TFlmmEUC7V7VtiDYLKsbP5rySTKLIZq1yn8lMqK83wo=\n"
            + "Address = 192.0.2.2/32\n";

    private static Interface parse(final String extra) throws Exception {
        final String text = BASE + extra;
        return Config.parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))).getInterface();
    }

    @Test
    public void tcp_forward_without_listen_ip_parses() throws Exception {
        final Interface iface = parse("TcpForward = 8080 -> 10.0.0.1:80\n");
        final TcpForwardRule rule = iface.getTcpForward();
        assertEquals(null, rule.getListenIp());
        assertEquals(8080, rule.getListenPort());
        assertEquals("10.0.0.1", rule.getTargetIp());
        assertEquals(80, rule.getTargetPort());
    }

    @Test
    public void tcp_forward_with_listen_ip_and_arrow_character_parses() throws Exception {
        final Interface iface = parse("TcpForward = 127.0.0.1:9000 → 10.0.0.1:443\n");
        final TcpForwardRule rule = iface.getTcpForward();
        assertEquals("127.0.0.1", rule.getListenIp());
        assertEquals(9000, rule.getListenPort());
        assertEquals("10.0.0.1", rule.getTargetIp());
        assertEquals(443, rule.getTargetPort());
    }

    @Test
    public void test_port_parses_with_and_without_listen_ip() throws Exception {
        final Interface withIp = parse("TestPort = 127.0.0.1:8080\n");
        assertEquals("127.0.0.1", withIp.getTestPort().getListenIp());
        assertEquals(8080, withIp.getTestPort().getPort());
        final Interface withoutIp = parse("TestPort = 8080\n");
        assertNull(withoutIp.getTestPort().getListenIp());
        assertEquals(8080, withoutIp.getTestPort().getPort());
    }

    @Test
    public void enable_flags_default_false_and_parse_true() throws Exception {
        final Interface defaults = parse("");
        assertNull(defaults.getTcpForward());
        assertNull(defaults.getTestPort());
        assertFalse(defaults.getTcpForwardEnabled());
        assertFalse(defaults.getTestServerEnabled());
        final Interface enabled = parse("TcpForward = 8080 -> 10.0.0.1:80\n"
                + "TcpForwardEnabled = true\n"
                + "TestPort = 8081\n"
                + "TestServerEnabled = true\n");
        assertTrue(enabled.getTcpForwardEnabled());
        assertTrue(enabled.getTestServerEnabled());
    }

    @Test
    public void serialization_round_trips() throws Exception {
        final String text = BASE
                + "TestPort = 127.0.0.1:8081\n"
                + "TestServerEnabled = true\n"
                + "TcpForward = 8080 -> 10.0.0.1:80\n"
                + "TcpForwardEnabled = true\n"
                + "[Peer]\n"
                + "PublicKey = vBN7qyUTb5lJtWYJ8LhbPio1Z4RcyBPGnqFBGn6O6Qg=\n"
                + "Endpoint = 192.0.2.1:51820\n";
        final Config config = Config.parse(new ByteArrayInputStream(
                text.getBytes(StandardCharsets.UTF_8)));
        final Config reparsedConfig = Config.parse(new ByteArrayInputStream(
                config.toWgQuickString().getBytes(StandardCharsets.UTF_8)));
        final Interface iface = config.getInterface();
        final Interface reparsed = reparsedConfig.getInterface();
        assertEquals(iface.getAddresses(), reparsed.getAddresses());
        assertEquals(iface.getDnsServers(), reparsed.getDnsServers());
        assertEquals(iface.getDnsSearchDomains(), reparsed.getDnsSearchDomains());
        assertEquals(iface.getExcludedApplications(), reparsed.getExcludedApplications());
        assertEquals(iface.getIncludedApplications(), reparsed.getIncludedApplications());
        assertEquals(iface.getKeyPair().getPublicKey(), reparsed.getKeyPair().getPublicKey());
        assertEquals(iface.getKeyPair().getPrivateKey(), reparsed.getKeyPair().getPrivateKey());
        assertEquals(iface.getListenPort(), reparsed.getListenPort());
        assertEquals(iface.getMtu(), reparsed.getMtu());
        assertEquals(iface.getTcpForward(), reparsed.getTcpForward());
        assertEquals(iface.getTestPort(), reparsed.getTestPort());
        assertEquals(iface.getTcpForwardEnabled(), reparsed.getTcpForwardEnabled());
        assertEquals(iface.getTestServerEnabled(), reparsed.getTestServerEnabled());
        assertEquals("8080 -> 10.0.0.1:80", reparsed.getTcpForward().toString());
        assertEquals("127.0.0.1:8081", reparsed.getTestPort().toString());
        assertTrue(reparsed.getTcpForwardEnabled());
        assertTrue(reparsed.getTestServerEnabled());
    }

    @Test
    public void invalid_values_throw() {
        assertBadConfig("TcpForward = 8080 -> 10.0.0.256:80\n", BadConfigException.Location.TCP_FORWARD);
        assertBadConfig("TcpForward = 0 -> 10.0.0.1:80\n", BadConfigException.Location.TCP_FORWARD);
        assertBadConfig("TcpForward = 8080 -> 10.0.0.1:70000\n", BadConfigException.Location.TCP_FORWARD);
        assertBadConfig("TcpForward = nonsense\n", BadConfigException.Location.TCP_FORWARD);
        assertBadConfig("TestPort = 70000\n", BadConfigException.Location.TEST_PORT);
        assertBadConfig("TestPort = 999.0.0.1:8080\n", BadConfigException.Location.TEST_PORT);
        assertBadConfig("TestPort = nonsense\n", BadConfigException.Location.TEST_PORT);
    }

    private static void assertBadConfig(final String extra, final BadConfigException.Location location) {
        try {
            parse(extra);
        } catch (final BadConfigException e) {
            assertEquals(location, e.getLocation());
            return;
        } catch (final Exception e) {
            fail("Unexpected exception type: " + e);
            return;
        }
        fail("Expected BadConfigException for: " + extra);
    }
}
