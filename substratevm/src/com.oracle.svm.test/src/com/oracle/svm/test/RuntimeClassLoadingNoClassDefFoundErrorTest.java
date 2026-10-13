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

import static java.lang.classfile.ClassFile.ACC_PUBLIC;
import static java.lang.classfile.ClassFile.ACC_STATIC;
import static java.lang.classfile.ClassFile.ACC_SUPER;
import static java.lang.constant.ConstantDescs.CD_Object;
import static java.lang.constant.ConstantDescs.CD_String;

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;

/**
 * A runtime-loaded class that references a class its loader cannot find must fail the way HotSpot
 * fails: a {@link NoClassDefFoundError} whose message is the internal name of the missing class
 * (not its descriptor), with the loader's {@link ClassNotFoundException} as the cause, and with the
 * guest frame first in the stack trace (elide-dev/bali#227).
 */
@NativeImageBuildArgs({
                "-H:+UnlockExperimentalVMOptions",
                "-H:+RuntimeClassLoading",
})
public class RuntimeClassLoadingNoClassDefFoundErrorTest {
    private static final String PACKAGE = "com.oracle.svm.test.generated.missing";
    private static final String USES_NAME = PACKAGE + ".Uses";
    private static final String ABSENT_NAME = PACKAGE + ".Absent";

    /** Defines a generated class at run time; it does not know the class it references. */
    private static final class GeneratedClassLoader extends ClassLoader {
        private final Map<String, byte[]> classFiles;

        GeneratedClassLoader(Map<String, byte[]> classFiles) {
            super(RuntimeClassLoadingNoClassDefFoundErrorTest.class.getClassLoader());
            this.classFiles = classFiles;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = classFiles.get(name);
            if (bytes == null) {
                throw new ClassNotFoundException(name);
            }
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    @Test
    public void testMissingClassReportedLikeHotSpot() throws Exception {
        GeneratedClassLoader loader = new GeneratedClassLoader(Map.of(USES_NAME, generateUses()));
        Method call = Class.forName(USES_NAME, true, loader).getMethod("call");

        /* The access that fails resolution throws the error resolution raised. */
        assertHotSpotShape(invokeExpectingError(call));
        /* Later accesses of the failed constant pool entry get a fresh error of the same shape. */
        assertHotSpotShape(invokeExpectingError(call));
    }

    private static NoClassDefFoundError invokeExpectingError(Method call) throws Exception {
        try {
            call.invoke(null);
            throw new AssertionError("expected NoClassDefFoundError");
        } catch (InvocationTargetException e) {
            if (!(e.getCause() instanceof NoClassDefFoundError error)) {
                throw new AssertionError("expected NoClassDefFoundError, got " + e.getCause(), e.getCause());
            }
            return error;
        }
    }

    private static void assertHotSpotShape(NoClassDefFoundError error) {
        Assert.assertEquals(ABSENT_NAME.replace('.', '/'), error.getMessage());

        Throwable cause = error.getCause();
        Assert.assertTrue(String.valueOf(cause), cause instanceof ClassNotFoundException);
        Assert.assertEquals(ABSENT_NAME, cause.getMessage());

        StackTraceElement[] trace = error.getStackTrace();
        Assert.assertTrue("empty stack trace", trace.length > 0);
        Assert.assertEquals(trace[0].toString(), USES_NAME, trace[0].getClassName());
        Assert.assertEquals(trace[0].toString(), "call", trace[0].getMethodName());
    }

    /** {@code public static String call() { return Absent.hello(); }}. */
    private static byte[] generateUses() {
        MethodTypeDesc stringReturn = MethodTypeDesc.of(CD_String);
        return ClassFile.of().build(ClassDesc.of(USES_NAME), cb -> {
            cb.withFlags(ACC_PUBLIC | ACC_SUPER);
            cb.withSuperclass(CD_Object);
            cb.withMethodBody("call", stringReturn, ACC_PUBLIC | ACC_STATIC, code -> code.invokestatic(ClassDesc.of(ABSENT_NAME), "hello", stringReturn).areturn());
        });
    }
}
