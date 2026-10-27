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

package shenandoah;

import java.lang.ref.WeakReference;

import org.graalvm.polyglot.Context;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.runtime.OptimizedCallTarget;

public class TruffleCodeRootsProbe {
    private static ProbeLanguage language;

    @TruffleLanguage.Registration(id = "gc-probe", name = "GC Probe", version = "1", characterMimeTypes = "application/x-gc-probe")
    public static final class ProbeLanguage extends TruffleLanguage<Object> {
        @Override
        protected Object createContext(Env env) {
            language = this;
            return new Object();
        }

        @Override
        protected CallTarget parse(ParsingRequest request) {
            return new RootNode(this) {
                @Override
                public Object execute(VirtualFrame frame) {
                    return 42;
                }
            }.getCallTarget();
        }
    }

    private static final class ProbeRoot extends RootNode {
        private final byte[] captured;

        ProbeRoot(byte[] captured) {
            super(language);
            this.captured = captured;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            if (frame.getArguments().length > 0 && (boolean) frame.getArguments()[0]) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
            }
            collect();
            return captured;
        }
    }

    @TruffleBoundary
    private static void collect() {
        System.gc();
    }

    private static OptimizedCallTarget createTarget() {
        return (OptimizedCallTarget) new ProbeRoot(new byte[]{42}).getCallTarget();
    }

    private static WeakReference<Object>[] retireTarget() {
        OptimizedCallTarget target = createTarget();
        Object captured = target.call(false);
        target.compile(true);
        target.waitForCompilation();
        if (!target.isValid()) {
            throw new AssertionError("Retired target was not compiled");
        }
        @SuppressWarnings("unchecked")
        WeakReference<Object>[] references = new WeakReference[]{new WeakReference<>(target), new WeakReference<>(captured)};
        return references;
    }

    public static void main(String[] args) {
        try (Context context = Context.newBuilder("gc-probe").allowExperimentalOptions(true)
                        .option("engine.BackgroundCompilation", "false").option("engine.CompilationFailureAction", "Throw").build()) {
            context.eval("gc-probe", "initialize");
            context.enter();
            try {
                checkCodeRoots();
            } finally {
                context.leave();
            }
        }
        System.out.println("Truffle code roots probe passed");
    }

    private static void checkCodeRoots() {
        OptimizedCallTarget target = createTarget();
        WeakReference<Object> expected = new WeakReference<>(target.call(false));
        for (int cycle = 0; cycle < 3; cycle++) {
            target.compile(true);
            target.waitForCompilation();
            if (!target.isValid()) {
                throw new AssertionError("Truffle target was not compiled");
            }
            for (int round = 0; round < 16; round++) {
                Object result = target.call(false);
                if (result != expected.get() || ((byte[]) result)[0] != 42) {
                    throw new AssertionError("Embedded code constant was not preserved or relocated");
                }
            }
            target.call(true);
            if (target.isValid()) {
                throw new AssertionError("Truffle deoptimization did not invalidate the target");
            }
            System.gc();
        }
        WeakReference<Object>[] retired = retireTarget();
        for (int round = 0; round < 16 && (retired[0].get() != null || retired[1].get() != null); round++) {
            System.gc();
        }
        if (retired[0].get() != null || retired[1].get() != null) {
            throw new AssertionError("Inactive code retained its call target or embedded heap constant");
        }
    }
}
