/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.tools.dap.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.io.MessageEndpoint;
import org.graalvm.polyglot.io.MessageTransport;
import org.graalvm.shadowed.org.json.JSONArray;
import org.graalvm.shadowed.org.json.JSONObject;
import org.junit.Test;

import com.oracle.truffle.api.instrumentation.test.InstrumentationTestLanguage;

/**
 * Serving DAP sessions over an engine's {@link MessageTransport}.
 */
public class DAPMessageTransportTest {

    private static final String SOURCE_PATH = "/runtime/transport.itl";
    private static final Source SOURCE = Source.newBuilder(InstrumentationTestLanguage.ID, "ROOT(\nSTATEMENT(CONSTANT(42))\n)", "transport.itl").uri(URI.create(
                    "file:///runtime/transport.itl")).buildLiteral();

    @Test
    public void testReattachAfterDisconnect() throws Exception {
        int port = freePort();
        TestTransport transport = new TestTransport();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Engine engine = Engine.newBuilder().serverTransport(transport).option("dap", "127.0.0.1:" + port).option("dap.Suspend", "false").out(out).err(out).build();
        Context context = Context.newBuilder(InstrumentationTestLanguage.ID).engine(engine).build();
        try {
            URI uri = URI.create("ws://127.0.0.1:" + port + "/dap");
            Client first = transport.next();
            assertEquals(List.of(uri), transport.uris());
            String output = out.toString(StandardCharsets.UTF_8);
            assertTrue(output, output.contains("[Graal DAP] Starting server on message transport " + uri));
            assertTrue("A socket was bound although a transport claimed the URI: " + output, !output.contains("listening on"));
            // No socket is bound for the transport's URI.
            new ServerSocket(port, 1, InetAddress.getLoopbackAddress()).close();

            JSONObject initialized = first.request("initialize", 1);
            assertTrue(initialized.toString(), initialized.getJSONObject("body").getBoolean("supportsConfigurationDoneRequest"));
            first.request("attach", 2);
            first.request("configurationDone", 3);
            // While a client is attached, the transport is not offered a second endpoint.
            assertNull("A second endpoint was offered while a client is attached.", transport.clients.poll(200, TimeUnit.MILLISECONDS));
            first.request("setBreakpoints", 4, breakpointArguments(SOURCE_PATH, 2));
            CompletableFuture<?> evaluation = evaluate(context);
            int threadId = first.event("stopped").getJSONObject("body").getInt("threadId");
            first.request("continue", 5, new JSONObject().put("threadId", threadId));
            evaluation.get(5, TimeUnit.SECONDS);
            first.request("disconnect", 6);
            first.assertClosed();

            // After the client leaves, the transport is offered a fresh endpoint at the same URI.
            Client second = transport.next();
            assertEquals(List.of(uri, uri), transport.uris());
            // The first client's endpoint is spent: nothing it sends reaches the new session.
            first.send("disconnect", 99, new JSONObject());
            first.server.sendClose();
            assertNull(first.received.poll(200, TimeUnit.MILLISECONDS));

            second.request("initialize", 1);
            second.request("attach", 2);
            second.request("configurationDone", 3);
            // The previous session's breakpoint is gone.
            evaluate(context).get(5, TimeUnit.SECONDS);
            second.request("setBreakpoints", 4, breakpointArguments(SOURCE_PATH, 2));
            evaluation = evaluate(context);
            threadId = second.event("stopped").getJSONObject("body").getInt("threadId");
            JSONObject trace = second.request("stackTrace", 5, new JSONObject().put("threadId", threadId));
            assertEquals(2, trace.getJSONObject("body").getJSONArray("stackFrames").getJSONObject(0).getInt("line"));
            second.request("continue", 6, new JSONObject().put("threadId", threadId));
            evaluation.get(5, TimeUnit.SECONDS);
            second.request("disconnect", 7);
            second.assertClosed();

            Client third = transport.next();
            // Engine close ends the offered endpoint and joins the transport thread.
            CompletableFuture.runAsync(() -> {
                context.close();
                engine.close();
            }).get(5, TimeUnit.SECONDS);
            third.awaitClose();
            assertEquals(3, transport.uris().size());
        } finally {
            context.close(true);
            engine.close();
        }
    }

    @Test
    public void testStartupSuspendIsNotRearmed() throws Exception {
        TestTransport transport = new TestTransport();
        try (Engine engine = Engine.newBuilder().serverTransport(transport).option("dap", "127.0.0.1:" + freePort()).build();
                        Context context = Context.newBuilder(InstrumentationTestLanguage.ID).engine(engine).build()) {
            Client first = transport.next();
            CompletableFuture<?> evaluation = evaluate(context);
            first.request("initialize", 1);
            first.request("attach", 2);
            first.request("configurationDone", 3);
            assertEquals("debugger_statement", first.event("stopped").getJSONObject("body").getString("reason"));
            first.request("disconnect", 4);
            first.assertClosed();
            evaluation.get(5, TimeUnit.SECONDS);
            Client second = transport.next();
            second.request("initialize", 1);
            second.request("attach", 2);
            second.request("configurationDone", 3);
            evaluate(context).get(5, TimeUnit.SECONDS);
            second.request("disconnect", 4);
            second.assertClosed();
        }
    }

    @Test
    public void testWaitAttachedOverContextTransport() throws Exception {
        TestTransport transport = new TestTransport();
        try (Context context = Context.newBuilder(InstrumentationTestLanguage.ID).serverTransport(transport).option("dap", "127.0.0.1:" + freePort()).option("dap.Suspend", "false").option(
                        "dap.WaitAttached", "true").build()) {
            Client first = transport.next();
            CompletableFuture<?> evaluation = evaluate(context);
            first.request("initialize", 1);
            first.request("attach", 2);
            assertThrows(TimeoutException.class, () -> evaluation.get(100, TimeUnit.MILLISECONDS));
            first.request("configurationDone", 3);
            evaluation.get(5, TimeUnit.SECONDS);
            first.request("disconnect", 4);
            first.assertClosed();
            // The startup wait is not rearmed for the next client.
            evaluate(context).get(5, TimeUnit.SECONDS);
            Client second = transport.next();
            second.request("initialize", 1);
            second.request("attach", 2);
            second.request("configurationDone", 3);
            second.request("disconnect", 4);
            second.assertClosed();
        }
    }

    @Test
    public void testVetoFailsStart() throws Exception {
        int port = freePort();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        MessageTransport veto = (uri, peer) -> {
            throw new MessageTransport.VetoException("Server vetoed.");
        };
        // dap.Suspend defaults to true: a failed start must not leave the guest suspended.
        try (Context context = Context.newBuilder(InstrumentationTestLanguage.ID).serverTransport(veto).option("dap", "127.0.0.1:" + port).out(out).err(err).build()) {
            String error = err.toString(StandardCharsets.UTF_8);
            assertTrue(error, error.contains("[Graal DAP] Starting server on 127.0.0.1:" + port + " failed: Server vetoed."));
            String output = out.toString(StandardCharsets.UTF_8);
            assertTrue(output, !output.contains("[Graal DAP] Starting server"));
            // A veto does not fall back to a socket.
            new ServerSocket(port, 1, InetAddress.getLoopbackAddress()).close();
            evaluate(context).get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    public void testNoTransportFallsBackToSocket() throws Exception {
        int port = freePort();
        List<URI> offered = Collections.synchronizedList(new ArrayList<>());
        MessageTransport decline = (uri, peer) -> {
            offered.add(uri);
            return null;
        };
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(InstrumentationTestLanguage.ID).serverTransport(decline).option("dap", "127.0.0.1:" + port).option("dap.Suspend", "false").out(out).build()) {
            assertEquals(List.of(URI.create("ws://127.0.0.1:" + port + "/dap")), offered);
            try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
                socket.setSoTimeout(5000);
                assertEquals("initialize", socketRequest(socket, "initialize", 1, new JSONObject().put("adapterID", "graalvm")).getString("command"));
                // The accept thread reports the socket before it serves the first client.
                String output = out.toString(StandardCharsets.UTF_8);
                assertTrue(output, output.contains("[Graal DAP] Starting server and listening on /127.0.0.1:" + port));
                socketRequest(socket, "disconnect", 2, new JSONObject().put("restart", false));
                while (DAPTester.readMessageBytes(socket.getInputStream()) != null) {
                    // Events sent before the disconnect.
                }
            }
            assertEquals(1, offered.size());
            evaluate(context).get(5, TimeUnit.SECONDS);
        }
    }

    private static JSONObject socketRequest(Socket socket, String command, int sequence, JSONObject arguments) throws IOException {
        byte[] request = new JSONObject().put("seq", sequence).put("type", "request").put("command", command).put("arguments", arguments).toString().getBytes(StandardCharsets.UTF_8);
        OutputStream stream = socket.getOutputStream();
        stream.write(("Content-Length: " + request.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        stream.write(request);
        stream.flush();
        while (true) {
            byte[] bytes = DAPTester.readMessageBytes(socket.getInputStream());
            assertNotNull("Connection closed before responding to " + command, bytes);
            JSONObject message = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if ("response".equals(message.getString("type"))) {
                assertEquals(message.toString(), sequence, message.getInt("request_seq"));
                assertTrue(message.toString(), message.getBoolean("success"));
                return message;
            }
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static CompletableFuture<?> evaluate(Context context) {
        return CompletableFuture.runAsync(() -> context.eval(SOURCE));
    }

    private static JSONObject breakpointArguments(String path, int line) {
        return new JSONObject().put("source", new JSONObject().put("path", path)).put("breakpoints", new JSONArray().put(new JSONObject().put("line", line)));
    }

    /** Hands out one {@link Client} per endpoint the DAP instrument opens. */
    private static final class TestTransport implements MessageTransport {

        final BlockingQueue<Client> clients = new LinkedBlockingQueue<>();
        private final List<URI> uris = Collections.synchronizedList(new ArrayList<>());

        @Override
        public MessageEndpoint open(URI uri, MessageEndpoint peerEndpoint) {
            uris.add(uri);
            Client client = new Client(peerEndpoint);
            clients.add(client);
            return client;
        }

        Client next() throws InterruptedException {
            Client client = clients.poll(5, TimeUnit.SECONDS);
            assertNotNull("The DAP instrument offered no endpoint.", client);
            return client;
        }

        List<URI> uris() {
            synchronized (uris) {
                return new ArrayList<>(uris);
            }
        }
    }

    /** A debugger client on the far side of the transport: one text message per DAP body. */
    private static final class Client implements MessageEndpoint {

        private static final JSONObject CLOSED = new JSONObject();

        final MessageEndpoint server;
        final BlockingQueue<JSONObject> received = new LinkedBlockingQueue<>();
        private final Deque<JSONObject> skipped = new ArrayDeque<>();

        Client(MessageEndpoint server) {
            this.server = server;
        }

        @Override
        public void sendText(String text) {
            received.add(new JSONObject(text));
        }

        @Override
        public void sendBinary(ByteBuffer data) {
            received.add(new JSONObject().put("type", "unexpected binary message"));
        }

        @Override
        public void sendPing(ByteBuffer data) throws IOException {
            server.sendPong(data);
        }

        @Override
        public void sendPong(ByteBuffer data) {
        }

        @Override
        public void sendClose() {
            received.add(CLOSED);
        }

        void send(String command, int sequence, JSONObject arguments) throws IOException {
            server.sendText(new JSONObject().put("seq", sequence).put("type", "request").put("command", command).put("arguments", arguments).toString());
        }

        /** The next message, or {@code null} once the server closed this endpoint. */
        JSONObject receive() throws InterruptedException {
            JSONObject message = received.poll(5, TimeUnit.SECONDS);
            assertNotNull("No message from the DAP server.", message);
            return message == CLOSED ? null : message;
        }

        JSONObject request(String command, int sequence) throws IOException, InterruptedException {
            JSONObject arguments = new JSONObject();
            if ("initialize".equals(command)) {
                arguments.put("adapterID", "graalvm");
            } else if ("disconnect".equals(command)) {
                arguments.put("restart", false);
            }
            return request(command, sequence, arguments);
        }

        JSONObject request(String command, int sequence, JSONObject arguments) throws IOException, InterruptedException {
            send(command, sequence, arguments);
            while (true) {
                JSONObject message = receive();
                assertNotNull("Endpoint closed before responding to " + command, message);
                if ("event".equals(message.getString("type"))) {
                    skipped.add(message);
                } else {
                    assertEquals(message.toString(), "response", message.getString("type"));
                    assertEquals(message.toString(), sequence, message.getInt("request_seq"));
                    assertEquals(command, message.getString("command"));
                    assertTrue(message.toString(), message.getBoolean("success"));
                    return message;
                }
            }
        }

        JSONObject event(String name) throws InterruptedException {
            for (Iterator<JSONObject> iterator = skipped.iterator(); iterator.hasNext();) {
                JSONObject message = iterator.next();
                if (name.equals(message.getString("event"))) {
                    iterator.remove();
                    return message;
                }
            }
            while (true) {
                JSONObject message = receive();
                assertNotNull("Endpoint closed before event " + name, message);
                if ("event".equals(message.getString("type")) && name.equals(message.getString("event"))) {
                    return message;
                }
            }
        }

        /** After a client's disconnect: only events precede the close, and no termination. */
        void assertClosed() throws InterruptedException {
            while (true) {
                JSONObject message = receive();
                if (message == null) {
                    return;
                }
                assertEquals(message.toString(), "event", message.getString("type"));
                assertTrue("Unexpected termination event after disconnect.", !"terminated".equals(message.getString("event")));
            }
        }

        /** Waits for the server to close this endpoint, skipping events such as termination. */
        void awaitClose() throws InterruptedException {
            while (true) {
                JSONObject message = receive();
                if (message == null) {
                    return;
                }
                assertEquals(message.toString(), "event", message.getString("type"));
            }
        }
    }
}
