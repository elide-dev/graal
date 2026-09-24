/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.oracle.truffle.api.nodes.RootNode;

/**
 * Hosted-only entry points used by Native Image to compile shared call targets of the
 * pre-initialized engine at build time (the AOT tier). Never used at run time.
 */
public final class BuildTimeCompilationSupport {

    public record Pair(OptimizedCallTarget live, OptimizedCallTarget shadow) {
    }

    public record Rejection(String rootName, String reason) {
    }

    public record Selection(List<Pair> selected, List<Rejection> rejected) {
    }

    private BuildTimeCompilationSupport() {
    }

    /**
     * Hosted only: select shared call targets of the pre-initialized engine for build-time
     * compilation.
     */
    public static Selection selectPreinitialized() {
        if (!Boolean.getBoolean("polyglot.image-build-time.AOTCompile")) {
            return new Selection(List.of(), List.of());
        }
        if (!Boolean.getBoolean("polyglot.image-build-time.PreinitializeCodeSharing")) {
            throw new IllegalStateException("AOTCompile requires PreinitializeCodeSharing");
        }
        Pattern filter = globToPattern(System.getProperty("polyglot.image-build-time.AOTCompileFilter", ""));
        List<Pair> selected = new ArrayList<>();
        List<Rejection> rejected = new ArrayList<>();
        Map<OptimizedCallTarget, Boolean> seen = new LinkedHashMap<>();
        for (RootNode root : OptimizedRuntimeAccessor.ENGINE.getPreinitializedSharedRoots()) {
            OptimizedCallTarget target = (OptimizedCallTarget) root.getCallTarget();
            if (target.isAOTShadow() || target.isSplit() || seen.putIfAbsent(target, Boolean.TRUE) != null) {
                continue;
            }
            String name = root.getName() == null ? root.toString() : root.getName();
            if (filter != null && !filter.matcher(name).matches()) {
                rejected.add(new Rejection(name, "filtered"));
            } else if (target.wasExecuted()) {
                rejected.add(new Rejection(name, "executed at build time"));
            } else {
                OptimizedCallTarget shadow = target.createAOTShadow();
                if (shadow == null) {
                    rejected.add(new Rejection(name, "root refused AOT (cloneForAOT or prepareForAOT)"));
                } else {
                    selected.add(new Pair(target, shadow));
                }
            }
        }
        return new Selection(List.copyOf(selected), List.copyOf(rejected));
    }

    /**
     * Hosted only: link build-time compiled code to a live target.
     */
    public static void attach(OptimizedCallTarget live, AOTCode code) {
        live.setAOTCode(code);
    }

    private static Pattern globToPattern(String glob) {
        if (glob.isEmpty()) {
            return null;
        }
        StringBuilder regex = new StringBuilder();
        for (char c : glob.toCharArray()) {
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                case ',' -> regex.append('|');
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString());
    }
}
