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
package com.oracle.svm.test.ristretto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import org.graalvm.nativeimage.ImageInfo;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeResourceAccess;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * Deoptimizes code that Ristretto compiled from bytecode loaded at run time, and checks that
 * execution resumes in the interpreter with the right state.
 *
 * <p>
 * The nested classes below are not used directly. Each test defines fresh copies of them at run
 * time from their class files, so the interpreter runs them and Ristretto compiles them. In those
 * copies, calls to {@link Probe#compiled()} are rewritten to
 * {@code RistrettoDirectives.inRuntimeCompiledCode()}, which tells the code whether it is running
 * compiled or interpreted.
 *
 * <p>
 * The test needs an image built with {@code -H:+RuntimeClassLoading -H:+GraalJITCompileAtRuntime},
 * {@code --add-exports=org.graalvm.nativeimage.builder/com.oracle.svm.interpreter.ristretto=ALL-UNNAMED}
 * and {@code --features=com.oracle.svm.test.ristretto.RistrettoDeoptimizationTest$TestFeature},
 * run with {@code -Dcom.oracle.svm.test.ristretto=true}. Without that property it is skipped. The
 * Truffle gate runs it in the same image as Truffle runtime compilation, whose code must not take
 * the interpreter deoptimization path (see {@code mx_substratevm.truffle_unittest_task}).
 */
public class RistrettoDeoptimizationTest {
    static final String REQUIRE_RISTRETTO_PROPERTY = "com.oracle.svm.test.ristretto";
    static final long COMPILATION_TIMEOUT_NANOS = 120_000_000_000L;

    /** Registers the class files of the run-time classes as resources. */
    public static final class TestFeature implements Feature {
        @Override
        public void beforeAnalysis(BeforeAnalysisAccess access) {
            for (Class<?> c : RUNTIME_CLASSES) {
                RuntimeResourceAccess.addResource(RistrettoDeoptimizationTest.class.getModule(), classFileName(c));
            }
        }
    }

    /** Placeholder for {@code RistrettoDirectives.inRuntimeCompiledCode()}: 1 when compiled. */
    public static final class Probe {
        public static int compiled() {
            return 0;
        }
    }

    public abstract static class Shape {
        public abstract int value();
    }

    public static final class One extends Shape {
        @Override
        public int value() {
            return 1;
        }
    }

    /** Loaded only once the compiled code has assumed that {@link One} is the only shape. */
    public static final class Two extends Shape {
        @Override
        public int value() {
            return 2;
        }
    }

    public static final class Holder {
        public Shape shape;
    }

    /** Resolved only on the rare path, which compiled code therefore leaves to the interpreter. */
    public static final class Rare {
        public static int negate(int x) {
            return -x;
        }
    }

    public static final class Hot {
        /** Returns {@code x + 1}, plus 1_000_000 when compiled. The rare path returns {@code -x}. */
        public static int eager(int x, boolean rare) {
            if (rare) {
                return Rare.negate(x) - 1_000_000 * Probe.compiled();
            }
            return x + 1 + 1_000_000 * Probe.compiled();
        }

        /**
         * Sums {@code holder.shape.value()} over {@code n} iterations and runs {@code hook} halfway.
         * {@code compiled[0]} and {@code compiled[1]} count the compiled iterations before and after
         * the hook.
         */
        public static long lazy(Holder holder, int n, Runnable hook, int[] compiled) {
            long sum = 0;
            for (int i = 0; i < n; i++) {
                if (i == n / 2) {
                    hook.run();
                }
                sum += holder.shape.value();
                compiled[i < n / 2 ? 0 : 1] += Probe.compiled();
            }
            return sum;
        }
    }

    static final List<Class<?>> RUNTIME_CLASSES = List.of(Shape.class, One.class, Two.class, Holder.class, Rare.class, Hot.class);

    @Before
    public void requireRistretto() {
        Assume.assumeTrue("Native Image only", ImageInfo.inImageRuntimeCode());
        Assume.assumeTrue("Run with -D" + REQUIRE_RISTRETTO_PROPERTY + "=true in an image with Ristretto", Boolean.getBoolean(REQUIRE_RISTRETTO_PROPERTY));
    }

    /**
     * Compiled code reaches a call that was unresolved at compile time and deoptimizes eagerly. The
     * call completes in the interpreter.
     */
    @Test
    public void eagerDeoptimizationResumesInInterpreter() throws Exception {
        RuntimeClassLoader loader = new RuntimeClassLoader();
        Method eager = loader.loadClass(Hot.class.getName()).getMethod("eager", int.class, boolean.class);

        awaitCompiled("Hot.eager", () -> {
            int result = invoke(eager, 3, false);
            if (result != 4 && result != 1_000_004) {
                throw new AssertionError("unexpected result " + result);
            }
            return result == 1_000_004;
        });

        assertEquals("rare path must complete in the interpreter", -7, (int) invoke(eager, 7, true));
        int after = invoke(eager, 5, false);
        assertTrue("unexpected result after deoptimization: " + after, after == 6 || after == 1_000_006);
    }

    /**
     * Loading a second {@link Shape} invalidates compiled code that assumed a single one, while its
     * frame is active below the hook. The frame resumes in the interpreter, which dispatches the
     * remaining calls to the new shape.
     */
    @Test
    public void lazyDeoptimizationOfActiveFrameResumesInInterpreter() throws Exception {
        RuntimeClassLoader loader = new RuntimeClassLoader();
        Class<?> holderClass = loader.loadClass(Holder.class.getName());
        Object holder = holderClass.getConstructor().newInstance();
        holderClass.getField("shape").set(holder, loader.loadClass(One.class.getName()).getConstructor().newInstance());
        Method lazy = loader.loadClass(Hot.class.getName()).getMethod("lazy", holderClass, int.class, Runnable.class, int[].class);

        Runnable noHook = () -> {
        };
        awaitCompiled("Hot.lazy", () -> {
            int[] compiled = new int[2];
            long sum = invoke(lazy, holder, 10, noHook, compiled);
            assertEquals(10, sum);
            return compiled[0] == 5 && compiled[1] == 5;
        });

        Runnable loadSecondShape = () -> {
            try {
                holderClass.getField("shape").set(holder, loader.loadClass(Two.class.getName()).getConstructor().newInstance());
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        };
        int n = 1000;
        int[] compiled = new int[2];
        long sum = invoke(lazy, holder, n, loadSecondShape, compiled);
        assertEquals("iterations after the hook must call Two.value()", n / 2 * 1 + n / 2 * 2, sum);
        assertEquals("all iterations before the hook must run compiled", n / 2, compiled[0]);
        assertEquals("iterations after the hook must run in the interpreter", 0, compiled[1]);
    }

    /** Calls {@code compiledCall} until it reports compiled execution 100 times in a row. */
    private static void awaitCompiled(String what, BooleanSupplier compiledCall) {
        long deadline = System.nanoTime() + COMPILATION_TIMEOUT_NANOS;
        int consecutive = 0;
        while (consecutive < 100) {
            assertTrue("Ristretto did not compile " + what, System.nanoTime() < deadline);
            consecutive = compiledCall.getAsBoolean() ? consecutive + 1 : 0;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T invoke(Method method, Object... args) {
        try {
            return (T) method.invoke(null, args);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static String classFileName(Class<?> c) {
        return c.getName().replace('.', '/') + ".class";
    }

    /** Defines fresh copies of {@link #RUNTIME_CLASSES}, with {@link Probe#compiled()} rewritten. */
    static final class RuntimeClassLoader extends ClassLoader {
        private static final ClassDesc PROBE = ClassDesc.of(Probe.class.getName());
        private static final ClassDesc DIRECTIVES = ClassDesc.of("com.oracle.svm.interpreter.ristretto.RistrettoDirectives");

        private final Map<String, byte[]> classFiles = new HashMap<>();

        RuntimeClassLoader() throws IOException {
            super(RistrettoDeoptimizationTest.class.getClassLoader());
            for (Class<?> c : RUNTIME_CLASSES) {
                try (InputStream in = RistrettoDeoptimizationTest.class.getModule().getResourceAsStream(classFileName(c))) {
                    assertTrue("missing class file of " + c.getName(), in != null);
                    classFiles.put(c.getName(), rewriteProbe(in.readAllBytes()));
                }
            }
        }

        private static byte[] rewriteProbe(byte[] classFile) {
            ClassFile cf = ClassFile.of();
            return cf.transformClass(cf.parse(classFile), ClassTransform.transformingMethodBodies(CodeTransform.ofStateful(() -> (builder, element) -> {
                if (element instanceof InvokeInstruction invoke && invoke.owner().asSymbol().equals(PROBE) && invoke.name().equalsString("compiled")) {
                    /* The boolean result is an int on the operand stack. */
                    builder.invokestatic(DIRECTIVES, "inRuntimeCompiledCode", MethodTypeDesc.of(ConstantDescs.CD_boolean));
                } else {
                    builder.with(element);
                }
            })));
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            byte[] classFile = classFiles.get(name);
            if (classFile == null) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) {
                    c = defineClass(name, classFile, 0, classFile.length);
                }
                if (resolve) {
                    resolveClass(c);
                }
                return c;
            }
        }
    }
}
