/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.wireguard.config;

import com.wireguard.util.NonNullForAll;

import androidx.annotation.Nullable;

/**
 * Represents a {@code TestPort} interface attribute: an optional local listen address plus port,
 * in the form {@code [listenIp:]port}.
 */
@NonNullForAll
public final class TestServerRule {
    @Nullable private final String listenIp;
    private final int port;

    TestServerRule(@Nullable final String listenIp, final int port) {
        this.listenIp = listenIp;
        this.port = port;
    }

    @Nullable
    public String getListenIp() {
        return listenIp;
    }

    public int getPort() {
        return port;
    }

    @Override
    public boolean equals(final Object obj) {
        if (!(obj instanceof TestServerRule))
            return false;
        final TestServerRule other = (TestServerRule) obj;
        return java.util.Objects.equals(listenIp, other.listenIp) && port == other.port;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(listenIp, port);
    }

    @Override
    public String toString() {
        return listenIp == null ? String.valueOf(port) : listenIp + ":" + port;
    }
}
