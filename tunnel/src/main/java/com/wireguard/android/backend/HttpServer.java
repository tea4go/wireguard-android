/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.wireguard.android.backend;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import androidx.annotation.Nullable;

/**
 * Minimal HTTP server used for tunnel connectivity tests: `/autotest` answers
 * `200 OK`, every other path answers `404 Not Found`. Mirrors the HarmonyOS
 * client's HttpServer.
 */
public final class HttpServer {
    private static final String TAG = "WireGuard/HttpServer";
    private static final int MAX_REQUEST_BYTES = 8192;
    private static final int CLIENT_READ_TIMEOUT_MS = 10000;

    @Nullable private volatile ServerSocket serverSocket;

    public boolean isServerRunning() {
        final ServerSocket socket = serverSocket;
        return socket != null && !socket.isClosed();
    }

    public synchronized void start(final int port, final String listenIp) throws IOException {
        if (isServerRunning()) {
            Log.w(TAG, "HTTP server already running");
            return;
        }
        final ServerSocket socket = new ServerSocket();
        try {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(listenIp, port));
        } catch (final IOException e) {
            closeQuietly(socket);
            throw e;
        }
        serverSocket = socket;
        final Thread thread = new Thread(() -> acceptLoop(socket), "HttpServer");
        thread.start();
        Log.i(TAG, "HTTP server listening on " + listenIp + ':' + port);
    }

    public synchronized void stop() {
        final ServerSocket socket = serverSocket;
        serverSocket = null;
        if (socket == null)
            return;
        closeQuietly(socket);
        Log.i(TAG, "stopped");
    }

    private void acceptLoop(final ServerSocket server) {
        while (!server.isClosed()) {
            final Socket client;
            try {
                client = server.accept();
            } catch (final IOException e) {
                if (!server.isClosed())
                    Log.w(TAG, "accept failed", e);
                break;
            }
            final Thread thread = new Thread(() -> handleClient(client), "HttpServerClient");
            thread.start();
        }
    }

    private void handleClient(final Socket client) {
        try {
            client.setSoTimeout(CLIENT_READ_TIMEOUT_MS);
            final String request = readRequest(client.getInputStream());
            final String requestLine = request.split("\r\n", 2)[0];
            final String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                sendResponse(client, 400, "Bad Request");
            } else if (parts[1].equals("/autotest") || parts[1].startsWith("/autotest?")) {
                sendResponse(client, 200, "OK\n");
            } else {
                sendResponse(client, 404, "Not Found");
            }
        } catch (final IOException e) {
            Log.w(TAG, "client handling failed", e);
        } finally {
            closeQuietly(client);
        }
    }

    private static String readRequest(final InputStream input) throws IOException {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int b;
        while ((b = input.read()) != -1) {
            buffer.write(b);
            if (buffer.size() >= MAX_REQUEST_BYTES)
                break;
            final byte[] data = buffer.toByteArray();
            if (data.length >= 4 && data[data.length - 4] == '\r' && data[data.length - 3] == '\n'
                    && data[data.length - 2] == '\r' && data[data.length - 1] == '\n')
                break;
        }
        return buffer.toString(StandardCharsets.UTF_8.name());
    }

    private static void sendResponse(final Socket client, final int statusCode, final String body) {
        final String statusText = statusCode == 200 ? "OK" : statusCode == 404 ? "Not Found" : "Bad Request";
        final byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        final String header = "HTTP/1.1 " + statusCode + " " + statusText + "\r\n"
                + "Content-Type: text/plain\r\n"
                + "Content-Length: " + bodyBytes.length + "\r\n"
                + "Connection: close\r\n"
                + "\r\n";
        try {
            final OutputStream output = client.getOutputStream();
            output.write(header.getBytes(StandardCharsets.UTF_8));
            output.write(bodyBytes);
            output.flush();
            Log.i(TAG, "Response sent: " + statusCode + ' ' + statusText);
        } catch (final IOException e) {
            Log.w(TAG, "response send failed", e);
        }
    }

    private static void closeQuietly(final java.io.Closeable closeable) {
        try {
            closeable.close();
        } catch (final IOException ignored) {
        }
    }
}
