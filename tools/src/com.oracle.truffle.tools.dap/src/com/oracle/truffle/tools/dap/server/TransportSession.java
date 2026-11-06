/*
 * Copyright (c) 2026, 2026, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.truffle.tools.dap.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.graalvm.polyglot.io.MessageEndpoint;

/**
 * One DAP session carried by the engine's {@link org.graalvm.polyglot.io.MessageTransport}. Each
 * text message is one DAP JSON body, so the {@code Content-Length} framing the stream-based
 * {@link com.oracle.truffle.tools.dap.types.DebugProtocolServer.Session} expects is added on the
 * way in and stripped on the way out.
 */
final class TransportSession implements MessageEndpoint {

    private static final String CONTENT_LENGTH = "Content-Length:";
    private static final byte[] EOF = new byte[0];

    private final QueueInputStream serverInput = new QueueInputStream();
    private final FrameParser serverOutput = new FrameParser();
    private volatile MessageEndpoint client;
    private volatile boolean closed;

    void opened(MessageEndpoint clientEndpoint) {
        this.client = clientEndpoint;
    }

    InputStream getInput() {
        return serverInput;
    }

    OutputStream getOutput() {
        return serverOutput;
    }

    /** Ends the session: the server's reader sees end of input and tears the session down. */
    void close() {
        if (closed) {
            return;
        }
        closed = true;
        serverInput.end();
        MessageEndpoint endpoint = client;
        if (endpoint != null) {
            try {
                endpoint.sendClose();
            } catch (IOException ex) {
                // The client is already gone.
            }
        }
    }

    @Override
    public void sendText(String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        byte[] header = String.format(Locale.ENGLISH, "Content-Length: %d\r\n\r\n", body.length).getBytes(StandardCharsets.US_ASCII);
        serverInput.offer(header);
        serverInput.offer(body);
    }

    @Override
    public void sendBinary(ByteBuffer data) throws IOException {
        byte[] bytes = new byte[data.remaining()];
        data.get(bytes);
        sendText(new String(bytes, StandardCharsets.UTF_8));
    }

    @Override
    public void sendPing(ByteBuffer data) throws IOException {
        MessageEndpoint endpoint = client;
        if (endpoint != null) {
            endpoint.sendPong(data);
        }
    }

    @Override
    public void sendPong(ByteBuffer data) {
    }

    @Override
    public void sendClose() {
        close();
    }

    /** Hands queued chunks to the session's reader; blocks between messages. */
    private static final class QueueInputStream extends InputStream {

        private final BlockingQueue<byte[]> chunks = new LinkedBlockingQueue<>();
        private byte[] current = EOF;
        private int position;
        private boolean ended;

        void offer(byte[] chunk) {
            chunks.add(chunk);
        }

        void end() {
            chunks.add(EOF);
        }

        private boolean ensure() throws IOException {
            while (position >= current.length) {
                if (ended) {
                    return false;
                }
                try {
                    current = chunks.take();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IOException(ex);
                }
                position = 0;
                if (current == EOF) {
                    ended = true;
                    return false;
                }
            }
            return true;
        }

        @Override
        public int read() throws IOException {
            if (!ensure()) {
                return -1;
            }
            return current[position++] & 0xff;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            if (!ensure()) {
                return -1;
            }
            int count = Math.min(length, current.length - position);
            System.arraycopy(current, position, buffer, offset, count);
            position += count;
            return count;
        }
    }

    /** Splits the server's framed output into bodies and forwards each as one text message. */
    private final class FrameParser extends OutputStream {

        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
        private int contentLength = -1;

        @Override
        public synchronized void write(int b) throws IOException {
            pending.write(b);
            drain();
        }

        @Override
        public synchronized void write(byte[] buffer, int offset, int length) throws IOException {
            pending.write(buffer, offset, length);
            drain();
        }

        private void drain() throws IOException {
            while (true) {
                byte[] buffered = pending.toByteArray();
                if (contentLength < 0) {
                    int end = headerEnd(buffered);
                    if (end < 0) {
                        return;
                    }
                    for (String line : new String(buffered, 0, end, StandardCharsets.US_ASCII).split("\r\n")) {
                        if (line.startsWith(CONTENT_LENGTH)) {
                            contentLength = Integer.parseInt(line.substring(CONTENT_LENGTH.length()).trim());
                        }
                    }
                    if (contentLength < 0) {
                        throw new IOException("Missing " + CONTENT_LENGTH + " header in server output");
                    }
                    consume(buffered, end + 4);
                    continue;
                }
                if (buffered.length < contentLength) {
                    return;
                }
                String body = new String(buffered, 0, contentLength, StandardCharsets.UTF_8);
                consume(buffered, contentLength);
                contentLength = -1;
                MessageEndpoint endpoint = client;
                if (endpoint != null && !closed) {
                    endpoint.sendText(body);
                }
            }
        }

        private void consume(byte[] buffered, int count) {
            pending.reset();
            pending.write(buffered, count, buffered.length - count);
        }

        private static int headerEnd(byte[] buffered) {
            for (int i = 0; i + 3 < buffered.length; i++) {
                if (buffered[i] == '\r' && buffered[i + 1] == '\n' && buffered[i + 2] == '\r' && buffered[i + 3] == '\n') {
                    return i;
                }
            }
            return -1;
        }
    }
}
