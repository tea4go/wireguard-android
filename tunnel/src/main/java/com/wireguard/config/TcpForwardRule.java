/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.wireguard.config;

import com.wireguard.util.NonNullForAll;

import androidx.annotation.Nullable;

/**
 * Represents a {@code TcpForward} interface attribute: an optional local listen address plus a
 * mandatory target address, in the form {@code [listenIp:]listenPort -> targetIp:targetPort}.
 */
@NonNullForAll
public final class TcpForwardRule {
    @Nullable private final String listenIp;
    private final int listenPort;
    private final String targetIp;
    private final int targetPort;

    TcpForwardRule(@Nullable final String listenIp, final int listenPort,
                   final String targetIp, final int targetPort) {
        this.listenIp = listenIp;
        this.listenPort = listenPort;
        this.targetIp = targetIp;
        this.targetPort = targetPort;
    }

    @Nullable
    public String getListenIp() {
        return listenIp;
    }

    public int getListenPort() {
        return listenPort;
    }

    public String getTargetIp() {
        return targetIp;
    }

    public int getTargetPort() {
        return targetPort;
    }

    @Override
    public boolean equals(final Object obj) {
        if (!(obj instanceof TcpForwardRule))
            return false;
        final TcpForwardRule other = (TcpForwardRule) obj;
        return java.util.Objects.equals(listenIp, other.listenIp)
                && listenPort == other.listenPort
                && targetIp.equals(other.targetIp)
                && targetPort == other.targetPort;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(listenIp, listenPort, targetIp, targetPort);
    }

    @Override
    public String toString() {
        final String listen = listenIp == null ? String.valueOf(listenPort) : listenIp + ":" + listenPort;
        return listen + " -> " + targetIp + ":" + targetPort;
    }
}
