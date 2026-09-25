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

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.SourceSection;

/**
 * Helpers of the native AOT tier tests ({@link AOTTierNativeTest} and the tests that need an image
 * run of their own, because they need the engine that adopts the pre-initialized engine to have
 * other options, or no such engine).
 * <p>
 * Only the first engine created in an image run that can use the pre-initialized context adopts
 * the pre-initialized engine, whose shared layer holds the call targets compiled at build time.
 * {@code mx_substratevm.truffle_aot_tier_native_tests} runs each test class in an image run of its
 * own (of the same image).
 */
final class AOTTierNativeTestSupport {

    private AOTTierNativeTestSupport() {
    }

    /**
     * A source equal to the one pre-initialization parsed for the fixture {@code name} (same
     * content, same name: the fixture file name), so that parsing it hits the pre-initialized call
     * target in the shared layer's source cache.
     */
    static Source fixture(String name, String code) {
        return Source.newBuilder(AOTTestLanguage.ID, code, name + ".aot").buildLiteral();
    }

    /**
     * The "add7" source {@link AOTTestPreinitializedSourcesFeature} registers; a context of the
     * image must parse an equal source to use its pre-initialized call target. Not a method of the
     * feature: image code cannot reference a {@code Feature} class.
     */
    static Source registeredAdd7() {
        return Source.newBuilder(AOTTestLanguage.ID, "add:7", "add7.aotm").mimeType(AOTTestLanguage.MODULE_MIME_TYPE).buildLiteral();
    }

    /** An engine with {@code engine.TraceAOT} that logs to {@code log}, with extra options. */
    static Engine traceEngine(ByteArrayOutputStream log, String... options) {
        Engine.Builder builder = Engine.newBuilder().allowExperimentalOptions(true).option("engine.TraceAOT", "true").logHandler(log);
        for (int i = 0; i < options.length; i += 2) {
            builder.option(options[i], options[i + 1]);
        }
        return builder.build();
    }

    /**
     * Joins {@code thread} for at most {@code seconds}, and fails, with the thread's stack, if it
     * is still running: a test must fail rather than hang.
     */
    static void join(Thread thread, long seconds) throws InterruptedException {
        thread.join(TimeUnit.SECONDS.toMillis(seconds));
        if (thread.isAlive()) {
            AssertionError error = new AssertionError(thread.getName() + " did not finish within " + seconds + " s");
            error.setStackTrace(thread.getStackTrace());
            throw error;
        }
    }

    /** A daemon thread, so that a thread a failed test leaves running cannot keep the run alive. */
    static Thread daemon(String name, Runnable body) {
        Thread thread = new Thread(body, name);
        thread.setDaemon(true);
        return thread;
    }

    static String text(ByteArrayOutputStream log) {
        synchronized (log) {
            return log.toString();
        }
    }

    /** The number of {@code [aot] enter <root>} lines of {@code log}. */
    static int enters(String log, String root) {
        return count(log, "\\[aot\\] enter " + Pattern.quote(root) + "$");
    }

    /**
     * The number of {@code [aot] deoptimize <root>: <reason>} lines of {@code log}: the safepoints
     * that invalidations of the code of {@code root} took.
     */
    static int deoptimizations(String log, String root) {
        return count(log, "\\[aot\\] deoptimize " + Pattern.quote(root) + ": ");
    }

    /** The number of {@code [aot] unlink <root>: <reason>} lines of {@code log}. */
    static int unlinks(String log, String root) {
        return count(log, "\\[aot\\] unlink " + Pattern.quote(root) + ": ");
    }

    /** The number of {@code [aot] unlink <root>: <reason>} lines of {@code log}. */
    static int unlinks(String log, String root, String reason) {
        return count(log, "\\[aot\\] unlink " + Pattern.quote(root) + ": " + Pattern.quote(reason) + "$");
    }

    private static int count(String log, String regex) {
        Matcher m = Pattern.compile(regex, Pattern.MULTILINE).matcher(log.replace("\r", ""));
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    /**
     * The guest frames of the stack trace of {@code e}, innermost first, each as
     * {@code <root name>@<source name>:<start>-<end>} (character indices of its source section).
     */
    static List<String> guestFrames(PolyglotException e) {
        List<String> frames = new ArrayList<>();
        for (PolyglotException.StackFrame frame : e.getPolyglotStackTrace()) {
            if (frame.isGuestFrame()) {
                SourceSection s = frame.getSourceLocation();
                frames.add(frame.getRootName() + "@" + (s == null ? "<no source>" : s.getSource().getName() + ":" + s.getCharIndex() + "-" + s.getCharEndIndex()));
            }
        }
        return frames;
    }
}
