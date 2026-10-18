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
package com.oracle.svm.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeProxyCreation;
import org.graalvm.nativeimage.hosted.RuntimeReflection;
import org.junit.Test;

import com.oracle.svm.core.jdk.StackTraceUtils;
import com.oracle.svm.guest.staging.core.graal.KnownIntrinsics;
import com.oracle.svm.guest.staging.jdk.InternalVMMethod;
import com.oracle.svm.shared.AlwaysInline;
import com.oracle.svm.shared.NeverInline;

/** Caller identity, access decisions and allocation for ordinary native stack frames. */
@NativeImageBuildArgs({"--add-exports=org.graalvm.nativeimage.guest.staging/com.oracle.svm.guest.staging.core.graal=ALL-UNNAMED",
                "--features=com.oracle.svm.test.CallerClassMetadataTest$TestFeature"})
public class CallerClassMetadataTest {
    public static class TestFeature implements Feature {
        @Override
        public void beforeAnalysis(BeforeAnalysisAccess access) {
            RuntimeReflection.register(Visible.class.getDeclaredMethods());
            RuntimeReflection.register(Owner.class.getDeclaredMethods());
            RuntimeReflection.register(Owner.class.getDeclaredConstructors());
            RuntimeReflection.register(Owner.class.getDeclaredFields());
            RuntimeProxyCreation.register(Caller.class);
        }
    }

    @InternalVMMethod
    static final class InternalFrames {
        @NeverInline("Keep each skipped physical frame in the allocation regression.")
        static Class<?> walk(int depth, boolean ignoreFirst) {
            if (depth > 0) {
                return walk(depth - 1, ignoreFirst);
            }
            return StackTraceUtils.getCallerClass(KnownIntrinsics.readCallerStackPointer(), ignoreFirst);
        }
    }

    public static final class Visible {
        @NeverInline("Caller identity must include this visible frame.")
        public static Class<?> caller(int depth, boolean ignoreFirst) {
            return InternalFrames.walk(depth, ignoreFirst);
        }

        @AlwaysInline("Exercise inline source metadata as well as physical frames.")
        static Class<?> inlinedCaller(boolean ignoreFirst) {
            return InternalFrames.walk(0, ignoreFirst);
        }
    }

    public interface Caller {
        Class<?> caller();
    }

    static final class CallerHandler implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return Visible.caller(4, true);
        }
    }

    static final class Owner {
        private static final int VALUE = 23;
        private final int value;

        private Owner(int value) {
            this.value = value;
        }

        private static int secret() {
            return 17;
        }

        static int ownMethod() throws ReflectiveOperationException {
            return (int) Owner.class.getDeclaredMethod("secret").invoke(null);
        }

        static int ownField() throws ReflectiveOperationException {
            return Owner.class.getDeclaredField("VALUE").getInt(null);
        }

        static int ownConstructor() throws ReflectiveOperationException {
            return Owner.class.getDeclaredConstructor(int.class).newInstance(31).value;
        }
    }

    @Test
    public void callerIdentityAcrossPhysicalAndInlineFrames() {
        for (int depth : new int[]{0, 1, 8, 24}) {
            assertSame(Visible.class, Visible.caller(depth, false));
            assertSame(CallerClassMetadataTest.class, Visible.caller(depth, true));
        }
        assertSame(Visible.class, Visible.inlinedCaller(false));
        assertSame(CallerClassMetadataTest.class, Visible.inlinedCaller(true));
    }

    @Test
    public void reflectiveAndMethodHandleCallers() throws Throwable {
        Method method = Visible.class.getMethod("caller", int.class, boolean.class);
        assertSame(Visible.class, method.invoke(null, 8, false));
        assertSame(CallerClassMetadataTest.class, method.invoke(null, 8, true));
        MethodHandle handle = MethodHandles.lookup().findStatic(Visible.class, "caller", MethodType.methodType(Class.class, int.class, boolean.class));
        assertSame(Visible.class, (Class<?>) handle.invokeExact(8, false));
        assertSame(CallerClassMetadataTest.class, (Class<?>) handle.invokeExact(8, true));
        Caller proxy = (Caller) Proxy.newProxyInstance(Caller.class.getClassLoader(), new Class<?>[]{Caller.class}, new CallerHandler());
        assertSame(CallerHandler.class, proxy.caller());
    }

    @Test
    public void reflectionAllowsOwnerAndRejectsForeignCaller() throws ReflectiveOperationException {
        assertEquals(17, Owner.ownMethod());
        assertEquals(23, Owner.ownField());
        assertEquals(31, Owner.ownConstructor());
        Method method = Owner.class.getDeclaredMethod("secret");
        Field field = Owner.class.getDeclaredField("VALUE");
        Constructor<?> constructor = Owner.class.getDeclaredConstructor(int.class);
        assertThrows(IllegalAccessException.class, () -> CallerClassForeignAccess.method(method));
        assertThrows(IllegalAccessException.class, () -> CallerClassForeignAccess.field(field));
        assertThrows(IllegalAccessException.class, () -> CallerClassForeignAccess.constructor(constructor));
    }

    private static long allocated(int depth) {
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertTrue("thread allocation accounting is supported", bean.isThreadAllocatedMemorySupported());
        assertTrue("thread allocation accounting is enabled", bean.isThreadAllocatedMemoryEnabled());
        long before = bean.getCurrentThreadAllocatedBytes();
        assertTrue("allocation reading is available", before >= 0);
        for (int i = 0; i < 1_000; i++) {
            assertSame(Visible.class, Visible.caller(depth, false));
        }
        long after = bean.getCurrentThreadAllocatedBytes();
        assertTrue("allocation reading is monotonic", after >= before);
        return after - before;
    }

    @Test
    public void skippedOrdinaryFramesDoNotAllocatePerDepth() {
        allocated(0);
        allocated(24);
        long shallow = allocated(0);
        long deep = allocated(24);
        assertTrue("skipped frames must use bounded metadata: shallow=" + shallow + ", deep=" + deep, deep <= shallow + 256_000);
    }
}

/** A distinct nest, so successful owner calls cannot grant it private access. */
final class CallerClassForeignAccess {
    static Object method(Method method) throws ReflectiveOperationException {
        return method.invoke(null);
    }

    static int field(Field field) throws ReflectiveOperationException {
        return field.getInt(null);
    }

    static Object constructor(Constructor<?> constructor) throws ReflectiveOperationException {
        return constructor.newInstance(1);
    }
}
