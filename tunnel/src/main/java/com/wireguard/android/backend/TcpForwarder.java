/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.wireguard.android.backend;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import androidx.annotation.Nullable;

/**
 * Forwards a local TCP port to a target reachable through the VPN tunnel,
 * mirroring the HarmonyOS client's TcpForwarder.
 */
public final class TcpForwarder {
    private static final String TAG = "WireGuard/TcpForwarder";
    private static final int CONNECT_TIMEOUT_MS = 5000;

    private final Set<Socket> activeSockets = Collections.newSetFromMap(new ConcurrentHashMap<>());
    @Nullable private volatile ServerSocket serverSocket;

    public boolean isRunning() {
        final ServerSocket socket = serverSocket;
        return socket != null && !socket.isClosed();
    }

    public synchronized void start(final String listenIp, final int listenPort,
                                   final String targetIp, final int targetPort) throws IOException {
        if (isRunning())
            return;
        final ServerSocket socket = new ServerSocket();
        try {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(listenIp, listenPort));
        } catch (final IOException e) {
            closeQuietly(socket);
            throw e;
        }
        serverSocket = socket;
        final Thread thread = new Thread(() -> acceptLoop(socket, targetIp, targetPort), "TcpForwarder");
        thread.start();
        Log.i(TAG, "listening on " + listenIp + ':' + listenPort + " -> " + targetIp + ':' + targetPort);
    }

    public synchronized void stop() {
        final ServerSocket socket = serverSocket;
        serverSocket = null;
        if (socket != null) {
            closeQuietly(socket);
            Log.i(TAG, "stopped");
        }
        for (final Socket active : activeSockets)
            closeQuietly(active);
        activeSockets.clear();
    }

    private void acceptLoop(final ServerSocket server, final String targetIp, final int targetPort) {
        while (!server.isClosed()) {
            final Socket client;
            try {
                client = server.accept();
            } catch (final IOException e) {
                if (!server.isClosed())
                    Log.w(TAG, "accept failed", e);
                break;
            }
            final Thread thread = new Thread(() -> forward(client, targetIp, targetPort), "TcpForwardConnection");
            thread.start();
        }
    }

    private void forward(final Socket client, final String targetIp, final int targetPort) {
        final Socket target = new Socket();
        try {
            target.connect(new InetSocketAddress(targetIp, targetPort), CONNECT_TIMEOUT_MS);
        } catch (final IOException e) {
            Log.w(TAG, "target connect failed", e);
            closeQuietly(client);
            return;
        }
        activeSockets.add(client);
        activeSockets.add(target);
        final Thread up = new Thread(() -> pump(client, target), "TcpForwardUpstream");
        final Thread down = new Thread(() -> pump(target, client), "TcpForwardDownstream");
        up.start();
        down.start();
    }

    private void pump(final Socket from, final Socket to) {
        final byte[] buffer = new byte[8192];
        try {
            final InputStream input = from.getInputStream();
            final OutputStream output = to.getOutputStream();
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
                output.flush();
            }
        } catch (final IOException ignored) {
            // Either side going away terminates the connection pair.
        } finally {
            closeConnection(from, to);
        }
    }

    private void closeConnection(final Socket first, final Socket second) {
        activeSockets.remove(first);
        activeSockets.remove(second);
        closeQuietly(first);
        closeQuietly(second);
    }

    private static void closeQuietly(final java.io.Closeable closeable) {
        try {
            closeable.close();
        } catch (final IOException ignored) {
        }
    }
}
