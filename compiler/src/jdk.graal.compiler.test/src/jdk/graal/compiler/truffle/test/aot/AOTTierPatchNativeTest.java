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
package jdk.graal.compiler.truffle.test.aot;

import static jdk.graal.compiler.truffle.test.aot.AOTTierNativeTestSupport.enters;
import static jdk.graal.compiler.truffle.test.aot.AOTTierNativeTestSupport.fixture;
import static jdk.graal.compiler.truffle.test.aot.AOTTierNativeTestSupport.text;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Source;
import org.junit.Test;

import com.oracle.truffle.tck.tests.TruffleTestAssumptions;

/**
 * A context created while the first context of the adopting engine patches the pre-initialized
 * context must not share the pre-initialized layer before the patch is complete: the language
 * instances of the layer still have their build-time state. The patch sets the host instance of
 * the layer first, and patches the languages afterwards. The first context of an image run
 * patches the pre-initialized context, so this class needs an image run of its own (see
 * {@link AOTTierNativeTestSupport}).
 */
public class AOTTierPatchNativeTest {

    private static Source probe(String name) {
        return Source.newBuilder(AOTTestLanguage.ID, "patchprobe", name + ".aot").buildLiteral();
    }

    @Test
    public void contextCreatedDuringPatchDoesNotShareUnpatchedLayer() throws Exception {
        TruffleTestAssumptions.assumeAOT();
        ByteArrayOutputStream trace = new ByteArrayOutputStream();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AOTTestLanguage.patchEntered = entered;
        AOTTestLanguage.patchRelease = release;
        /* Releases the patch in any case, e.g., if the context created during the patch blocks. */
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(TimeUnit.SECONDS.toMillis(30));
            } catch (InterruptedException e) {
                return;
            }
            release.countDown();
        });
        watchdog.setDaemon(true);
        watchdog.start();
        try (Engine engine = AOTTierNativeTestSupport.traceEngine(trace)) {
            AtomicReference<Context> first = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread patching = AOTTierNativeTestSupport.daemon("aot-patching", () -> {
                try {
                    first.set(Context.newBuilder().engine(engine).build());
                } catch (Throwable t) {
                    failure.set(t);
                }
            });
            patching.start();
            assertTrue("the first context did not patch the pre-initialized context", entered.await(30, TimeUnit.SECONDS));

            /* The patch of the first context is in the patch of the test language now. */
            try (Context during = Context.newBuilder().engine(engine).build()) {
                assertEquals("a context created during the patch used a language instance that was not patched yet", 0, during.parse(probe("during")).execute().asInt());
                assertEquals(4, during.parse(fixture("add3", "add:3")).execute(1).asInt());
            } finally {
                release.countDown();
            }
            AOTTierNativeTestSupport.join(patching, 60);
            if (failure.get() != null) {
                throw new AssertionError(failure.get());
            }

            /* The patched context and a context created after the patch share the layer. */
            try (Context patched = first.get(); Context after = Context.newBuilder().engine(engine).build()) {
                assertEquals(0, patched.parse(probe("patched")).execute().asInt());
                assertEquals(3, patched.parse(fixture("add2", "add:2")).execute(1).asInt());
                assertEquals(0, after.parse(probe("after")).execute().asInt());
                assertEquals(2, after.parse(fixture("add1", "add:1")).execute(1).asInt());
            }
        } finally {
            AOTTestLanguage.patchRelease = null;
            AOTTestLanguage.patchEntered = null;
            watchdog.interrupt();
        }
        String log = text(trace);
        /* The context created during the patch had a layer of its own, without the AOT code. */
        assertEquals(log, 0, enters(log, "add3"));
        assertEquals(log, 1, enters(log, "add2"));
        assertEquals(log, 1, enters(log, "add1"));
    }
}
