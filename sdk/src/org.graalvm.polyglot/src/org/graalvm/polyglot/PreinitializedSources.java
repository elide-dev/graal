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
package org.graalvm.polyglot;

import java.util.Objects;

/**
 * Registers sources that context pre-initialization parses when a native image is built. Context
 * pre-initialization is enabled with the image build time system property
 * {@code polyglot.image-build-time.PreinitializeContexts}, see {@link Context}.
 * <p>
 * Pre-initialization parses each registered source with its language in the pre-initialized
 * context, and never executes it. A source that a context of the image parses later and that is
 * {@linkplain Source#equals(Object) equal} to a registered source then uses the call target
 * parsed at build time, and with {@code polyglot.image-build-time.AOTCompile} its build-time
 * compiled code. Two sources are equal only if everything the source builder can set is equal,
 * e.g., the characters, name, MIME type, path and URI, so a source registered here should be built
 * exactly as the image will build it at run time.
 * <p>
 * Sources are registered while the image is built, before the contexts are pre-initialized, e.g.,
 * from {@code Feature.duringSetup} of a {@code org.graalvm.nativeimage.hosted.Feature}. The system
 * property {@code polyglot.image-build-time.PreinitializeSources} is a shorthand for registering
 * sources read from files, with a name and no MIME type.
 * <p>
 * The language of every registered source must be pre-initialized and support context patching,
 * or pre-initialization fails.
 * <p>
 * Only sources that the embedder parses, e.g., with {@link Context#eval(Source)} or
 * {@link Context#parse(Source)}, can use a registered source. A source that a language creates
 * itself, e.g., a module that its module loader loads for an {@code import}, is never equal to a
 * registered source.
 *
 * <pre>
 * public final class MyFeature implements Feature {
 *     &#64;Override
 *     public void duringSetup(DuringSetupAccess access) {
 *         PreinitializedSources.register(Source.newBuilder("js", file).mimeType("application/javascript+module").build());
 *     }
 * }
 * </pre>
 *
 * @since 25.4
 */
public final class PreinitializedSources {

    private PreinitializedSources() {
    }

    /**
     * Registers {@code sources} to be parsed by context pre-initialization.
     *
     * @throws IllegalStateException if the contexts were already pre-initialized, or if called in
     *             a native image at run time
     * @since 25.4
     */
    public static void register(Source... sources) {
        Objects.requireNonNull(sources);
        for (Source source : sources) {
            Engine.getImpl().registerPreinitializedSource(Objects.requireNonNull(source).receiver);
        }
    }
}
