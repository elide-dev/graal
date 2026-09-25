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

    /**
     * The {@link Rejection#reason() reason} of a root that
     * {@code polyglot.image-build-time.AOTCompileFilter} excludes.
     */
    public static final String FILTERED = "filtered";

    /**
     * The {@link Rejection#reason() reason} of a root that was executed while the engine was
     * pre-initialized: its live AST is specialized already, so it is not eligible by design.
     */
    public static final String EXECUTED_AT_BUILD_TIME = "executed at build time";

    /**
     * The prefix of the {@link Rejection#reason() reason} of a root whose AOT shadow could not be
     * created, followed by the exception.
     */
    public static final String SHADOW_FAILED = "cannot create AOT shadow";

    public record Selection(List<Pair> selected, List<Rejection> rejected) {
    }

    private BuildTimeCompilationSupport() {
    }

    /**
     * Hosted only: whether the engine is pre-initialized without code sharing
     * ({@code polyglot.image-build-time.PreinitializeCodeSharing} is not set). Then the call targets
     * compiled at build time are those of the pre-initialized context, which only the engine that
     * adopts it at run time uses, and whose language contexts are patched before any of its code
     * runs; build-time partial evaluation must not fold context state that patching can change.
     */
    public static boolean isBoundPreinitialization() {
        return !Boolean.getBoolean("polyglot.image-build-time.PreinitializeCodeSharing");
    }

    /**
     * The context object of the language context {@code polyglotLanguageContext}, as context
     * references return it: AOT tier code folds it at build time, so the run time checks that
     * patching the pre-initialized context kept it.
     */
    public static Object getLanguageContextObject(Object polyglotLanguageContext) {
        return OptimizedRuntimeAccessor.ENGINE.getLanguageContextObject(polyglotLanguageContext);
    }

    /**
     * Hosted only: select call targets of the pre-initialized engine for build-time compilation:
     * those of its claimed shared layers and, without code sharing, those of the pre-initialized
     * context ({@link #isBoundPreinitialization()}). Each root is considered once, in engine
     * order:
     * <ul>
     * <li>AOT shadows, splits, and roots without a guest language
     * ({@link RootNode#getLanguageInfo()} is {@code null}, e.g., the engine's
     * {@link RootNode#createConstantNode constant root}) are skipped silently: they are not guest
     * code of their own, so they are neither selected nor rejected.</li>
     * <li>A root that {@code polyglot.image-build-time.AOTCompileFilter} excludes is rejected as
     * {@link #FILTERED}.</li>
     * <li>A root executed at build time is rejected as {@link #EXECUTED_AT_BUILD_TIME}: its live
     * AST is specialized already.</li>
     * <li>A root that refuses AOT ({@code cloneForAOT} or {@code prepareForAOT}) is rejected.</li>
     * <li>A root whose AOT shadow cannot be created is rejected with the exception that prevented
     * it, e.g., one thrown by the language's {@code cloneForAOT} or {@code prepareForAOT}: a bug in
     * one root must not fail the image build.</li>
     * <li>Every other root is selected, with its AOT shadow.</li>
     * </ul>
     */
    public static Selection selectPreinitialized() {
        if (!Boolean.getBoolean("polyglot.image-build-time.AOTCompile")) {
            return new Selection(List.of(), List.of());
        }
        Pattern filter = globToPattern(System.getProperty("polyglot.image-build-time.AOTCompileFilter", ""));
        List<Pair> selected = new ArrayList<>();
        List<Rejection> rejected = new ArrayList<>();
        Map<OptimizedCallTarget, Boolean> seen = new LinkedHashMap<>();
        for (RootNode root : OptimizedRuntimeAccessor.ENGINE.getPreinitializedRoots()) {
            OptimizedCallTarget target = (OptimizedCallTarget) root.getCallTarget();
            if (target.isAOTShadow() || target.isSplit() || root.getLanguageInfo() == null || seen.putIfAbsent(target, Boolean.TRUE) != null) {
                continue;
            }
            String name = root.getName() == null ? root.toString() : root.getName();
            if (filter != null && !filter.matcher(name).matches()) {
                rejected.add(new Rejection(name, FILTERED));
            } else if (target.wasExecuted()) {
                rejected.add(new Rejection(name, EXECUTED_AT_BUILD_TIME));
            } else {
                OptimizedCallTarget shadow;
                try {
                    shadow = target.createAOTShadow();
                } catch (VirtualMachineError e) {
                    throw e;
                } catch (Throwable t) {
                    rejected.add(new Rejection(name, SHADOW_FAILED + ": " + t));
                    continue;
                }
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
        if (glob.isBlank()) {
            return null;
        }
        StringBuilder regex = new StringBuilder();
        for (String part : glob.split(",")) {
            if (!regex.isEmpty()) {
                regex.append('|');
            }
            for (char c : part.strip().toCharArray()) {
                switch (c) {
                    case '*' -> regex.append(".*");
                    case '?' -> regex.append('.');
                    default -> regex.append(Pattern.quote(String.valueOf(c)));
                }
            }
        }
        return Pattern.compile(regex.toString());
    }
}
