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
package com.oracle.truffle.tools.dap.server;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.oracle.truffle.api.instrumentation.TruffleInstrument;
import com.oracle.truffle.tools.dap.types.DebugProtocolServer;
import com.oracle.truffle.tools.dap.types.DebugProtocolServer.Session;

/**
 * Owns an Engine's listening socket and serves one debugger client at a time. Each connection gets
 * fresh debugger and protocol state. A client that connects while another one is attached gets an
 * error response to its first request and is disconnected; the attached session is not disturbed.
 */
public final class DebugProtocolServerListener {

    /** How long a refused client may take to send its first request. */
    private static final int REFUSAL_TIMEOUT_MILLIS = 5000;
    /** Further clients are closed without a response while this many are being refused. */
    private static final int MAX_PENDING_REFUSALS = 8;

    private final TruffleInstrument.Env env;
    private final PrintWriter info;
    private final PrintWriter err;
    private final boolean inspectInternal;
    private final boolean inspectInitialization;
    private final List<URI> sourcePath;
    private final Map<Socket, Thread> refusals = new HashMap<>();
    private DebugProtocolServerImpl session;
    private ServerSocket serverSocket;
    /** The attached client's transport, until it is closed. */
    private Socket clientSocket;
    /** Serves the current client and cleans up after it. */
    private Thread sessionThread;
    private Throwable sessionFailure;
    private Thread thread;
    private boolean disposed;

    /**
     * Installs startup debugger state before any connection is accepted.
     *
     * @param context the first session's execution context, owned by this listener
     * @param debugBreak suspends the first guest execution
     * @param waitAttached delays language initialization until configuration is complete
     * @param inspectInitialization includes language initialization in debugging
     * @param sourcePath source lookup locations shared by successive sessions
     */
    public DebugProtocolServerListener(ExecutionContext context, boolean debugBreak, boolean waitAttached, boolean inspectInitialization, List<URI> sourcePath) {
        this.env = context.getEnv();
        this.info = context.getInfo();
        this.err = context.getErr();
        this.inspectInternal = context.isInspectInternal();
        this.inspectInitialization = context.isInspectInitialization();
        this.sourcePath = sourcePath;
        // Startup suspension must be installed before the first client is accepted.
        this.session = DebugProtocolServerImpl.create(context, debugBreak, waitAttached, inspectInitialization, sourcePath);
    }

    /**
     * Takes ownership of the listening socket until this listener is disposed.
     *
     * @param socket the bound listening socket
     * @return completion of the accept loop and all session cleanup
     */
    public synchronized CompletableFuture<Void> start(ServerSocket socket) {
        serverSocket = socket;
        CompletableFuture<Void> completion = new CompletableFuture<>();
        thread = env.createSystemThread(() -> {
            try {
                run();
                completion.complete(null);
            } catch (Throwable error) {
                completion.completeExceptionally(error);
            }
        });
        thread.setName("DAP client connection thread");
        thread.start();
        return completion;
    }

    private void run() throws Throwable {
        info.println("[Graal DAP] Starting server and listening on " + serverSocket.getLocalSocketAddress());
        try (ServerSocket listener = serverSocket) {
            while (accept(listener.accept())) {
                // The accept thread never serves a client, so it can refuse extra clients.
            }
        } catch (IOException error) {
            synchronized (this) {
                // A failed session closes the listener; its failure is reported below instead.
                if (!disposed && sessionFailure == null) {
                    throw error;
                }
            }
        } finally {
            Thread clientThread;
            List<Thread> refusalThreads;
            synchronized (this) {
                if (!disposed) {
                    // The accept loop failed. On dispose, dispose() closes the client's transport
                    // after reporting termination to it.
                    disposed = true;
                    close(clientSocket);
                }
                clientThread = sessionThread;
                refusals.keySet().forEach(this::close);
                refusalThreads = new ArrayList<>(refusals.values());
            }
            join(clientThread);
            refusalThreads.forEach(DebugProtocolServerListener::join);
            DebugProtocolServerImpl server;
            synchronized (this) {
                server = session;
                session = null;
            }
            if (server != null) {
                server.dispose();
            }
        }
        Throwable failure;
        synchronized (this) {
            failure = sessionFailure;
        }
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * Serves or refuses a new connection.
     *
     * @return {@code false} once this listener is disposed
     */
    private boolean accept(Socket socket) {
        Thread previous;
        synchronized (this) {
            if (disposed) {
                close(socket);
                return false;
            }
            if (clientSocket != null) {
                refuse(socket, clientSocket.getRemoteSocketAddress());
                return true;
            }
            previous = sessionThread;
        }
        // The previous client's transport is closed; its cleanup (resuming suspended threads,
        // completing pending requests) finishes before the next session starts.
        join(previous);
        synchronized (this) {
            if (disposed) {
                close(socket);
                return false;
            }
            clientSocket = socket;
            if (session == null) {
                ExecutionContext context = new ExecutionContext(env, info, err, inspectInternal, inspectInitialization);
                // Suspend/WaitAttached are startup options, not reconnect options.
                session = DebugProtocolServerImpl.create(context, false, false, context.isInspectInitialization(), sourcePath);
            }
            DebugProtocolServerImpl server = session;
            info.println("[Graal DAP] Client connected on " + socket.getRemoteSocketAddress());
            sessionThread = env.createSystemThread(() -> serve(server, socket));
            sessionThread.setName("DAP client session thread");
            sessionThread.start();
        }
        return true;
    }

    private void serve(DebugProtocolServerImpl server, Socket socket) {
        try {
            // Completes after the client's pending requests and cleanup have finished.
            Session.connect(server, socket.getInputStream(), socket.getOutputStream(), Runnable::run, () -> release(socket), server::dispose).get();
        } catch (Throwable error) {
            ServerSocket listener;
            synchronized (this) {
                if (sessionFailure == null) {
                    sessionFailure = error;
                }
                listener = disposed ? null : serverSocket;
            }
            // A failed session stops the listener, as when sessions ran on the accept thread.
            close(listener);
        } finally {
            server.dispose();
            release(socket);
            synchronized (this) {
                if (session == server) {
                    session = null;
                }
            }
        }
    }

    /**
     * Closes an attached client's transport; the next client may connect from now on.
     */
    private void release(Socket socket) {
        synchronized (this) {
            if (clientSocket == socket) {
                clientSocket = null;
            }
        }
        close(socket);
    }

    /**
     * Answers the first request of a client that connected while another one is attached, then
     * closes the connection. Runs on its own thread so neither the attached session nor the
     * accept loop waits for the refused client.
     */
    private void refuse(Socket socket, SocketAddress active) {
        assert Thread.holdsLock(this);
        info.println("[Graal DAP] Refused client on " + socket.getRemoteSocketAddress() + ": client on " + active + " is attached.");
        if (refusals.size() >= MAX_PENDING_REFUSALS) {
            close(socket);
            return;
        }
        String reason = "Another debugger client is attached to this process (" + active + "). Disconnect it before attaching a new one.";
        DebugProtocolServer.LoggerProxy logger = session.getLogger();
        Thread refusal = env.createSystemThread(() -> {
            try {
                socket.setSoTimeout(REFUSAL_TIMEOUT_MILLIS);
                Session.refuse(socket.getInputStream(), socket.getOutputStream(), reason, logger);
                // Closing with unread input would reset the connection and could drop the
                // response, so wait for the client to close first.
                socket.shutdownOutput();
                drain(socket.getInputStream());
            } catch (IOException error) {
                // The refused client went away or stayed silent; nothing else depends on it.
            } finally {
                close(socket);
                synchronized (this) {
                    refusals.remove(socket);
                }
            }
        });
        refusal.setName("DAP refused client thread");
        refusals.put(socket, refusal);
        refusal.start();
    }

    private static void drain(InputStream in) throws IOException {
        byte[] buffer = new byte[1024];
        while (in.read(buffer) != -1) {
            // Discard requests sent before the refusal arrived.
        }
    }

    private static void join(Thread thread) {
        if (thread == null || thread == Thread.currentThread()) {
            return;
        }
        boolean interrupted = false;
        while (thread.isAlive()) {
            try {
                thread.join();
            } catch (InterruptedException error) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Closes the listener and active transport and joins the client threads.
     */
    public void dispose() {
        Thread acceptThread;
        DebugProtocolServerImpl server;
        Socket socket;
        synchronized (this) {
            disposed = true;
            close(serverSocket);
            acceptThread = thread;
            server = session;
            socket = clientSocket;
        }
        try {
            if (server != null) {
                // Engine shutdown reports termination before closing the active transport.
                // Client disconnect/EOF cleanup disposes the session without this event.
                server.dispose(true);
            }
        } finally {
            close(socket);
            // The accept thread joins the session and refusal threads before it ends.
            join(acceptThread);
        }
    }

    private void close(Closeable socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException error) {
                err.println("[Graal DAP] Error while closing socket: " + error.getLocalizedMessage());
            }
        }
    }
}
