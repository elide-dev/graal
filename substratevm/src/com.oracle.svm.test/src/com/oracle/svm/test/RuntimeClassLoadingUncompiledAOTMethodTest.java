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
import static java.lang.constant.ConstantDescs.CD_String;
import static java.lang.constant.ConstantDescs.INIT_NAME;
import static java.lang.constant.ConstantDescs.MTD_void;

import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.Assert;
import org.junit.Test;

/**
 * A class loaded at run time calls image methods: its constructor calls the constructor of the
 * image class it extends, and its methods call image methods. The interpreter can only call an
 * image method through the method's compiled code, because image methods keep no bytecodes.
 * <p>
 * The compile queue compiles a method only when compiled code still calls it after inlining, so
 * the trivial constructor of {@link InlinedBase}, inlined into its only image caller, would have
 * no compiled code: the image must compile the constructors of subclassable classes on their own.
 * The constructor of {@link UncalledBase}, and the static method of it, are never called from image
 * code, so the image has no code for them at all; calling them must raise a
 * {@link NoSuchMethodError} the program can handle, not take the VM down (elide-dev/runtime#2508).
 * <p>
 * The reflection configuration of this test project registers the two image classes by name, so
 * that the run-time loaded classes can resolve them; it registers none of their members.
 */
@NativeImageBuildArgs({
                "-H:+UnlockExperimentalVMOptions",
                "-H:+RuntimeClassLoading",
})
public class RuntimeClassLoadingUncompiledAOTMethodTest {
    private static final String PACKAGE = "com.oracle.svm.test.generated.uncompiled";
    private static final String GUEST_OF_INLINED_NAME = PACKAGE + ".GuestOfInlined";
    private static final String GUEST_OF_UNCALLED_NAME = PACKAGE + ".GuestOfUncalled";
    private static final String CALLS_HELPER_NAME = PACKAGE + ".CallsHelper";

    /** Extended by {@link ImageSub}, so the analysis links the constructor, and inlines it. */
    public abstract static class InlinedBase {
        protected InlinedBase() {
        }

        public abstract String name();
    }

    public static final class ImageSub extends InlinedBase {
        @Override
        public String name() {
            return "image";
        }
    }

    /** Never instantiated by image code; {@link #helper()} is never called by image code. */
    public abstract static class UncalledBase {
        protected UncalledBase() {
        }

        public static String helper() {
            return "helper";
        }
    }

    /** Defines generated classes at run time. */
    private static final class GeneratedClassLoader extends ClassLoader {
        private final Map<String, byte[]> classFiles;

        GeneratedClassLoader(Map<String, byte[]> classFiles) {
            super(RuntimeClassLoadingUncompiledAOTMethodTest.class.getClassLoader());
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
    public void testInlinedSuperConstructorStaysCallable() throws Exception {
        Assert.assertEquals("image", new ImageSub().name());

        GeneratedClassLoader loader = new GeneratedClassLoader(Map.of(GUEST_OF_INLINED_NAME, generateGuestOfInlined()));
        Object guest = Class.forName(GUEST_OF_INLINED_NAME, true, loader).getDeclaredConstructor().newInstance();
        Assert.assertTrue(guest instanceof InlinedBase);
        Assert.assertEquals("guest", ((InlinedBase) guest).name());
    }

    @Test
    public void testUncompiledMethodsRaiseNoSuchMethodError() throws Exception {
        GeneratedClassLoader loader = new GeneratedClassLoader(Map.of(
                        GUEST_OF_UNCALLED_NAME, generateGuestOfUncalled(),
                        CALLS_HELPER_NAME, generateCallsHelper(),
                        GUEST_OF_INLINED_NAME, generateGuestOfInlined()));

        NoSuchMethodError constructorError = invokeExpectingError(() -> Class.forName(GUEST_OF_UNCALLED_NAME, true, loader).getDeclaredConstructor().newInstance());
        assertNames(constructorError, UncalledBase.class.getName() + ".<init>()V");

        NoSuchMethodError helperError = invokeExpectingError(() -> Class.forName(CALLS_HELPER_NAME, true, loader).getMethod("call").invoke(null));
        assertNames(helperError, UncalledBase.class.getName() + ".helper()Ljava/lang/String;");

        /* The errors leave the loader, and the interpreter, usable. */
        Object guest = Class.forName(GUEST_OF_INLINED_NAME, true, loader).getDeclaredConstructor().newInstance();
        Assert.assertEquals("guest", ((InlinedBase) guest).name());
    }

    @FunctionalInterface
    private interface ReflectiveCall {
        Object call() throws Exception;
    }

    private static NoSuchMethodError invokeExpectingError(ReflectiveCall call) throws Exception {
        try {
            call.call();
            throw new AssertionError("expected NoSuchMethodError");
        } catch (InvocationTargetException e) {
            if (!(e.getCause() instanceof NoSuchMethodError error)) {
                throw new AssertionError("expected NoSuchMethodError, got " + e.getCause(), e.getCause());
            }
            return error;
        }
    }

    private static void assertNames(NoSuchMethodError error, String method) {
        String message = String.valueOf(error.getMessage());
        Assert.assertTrue(message, message.contains(method));
        Assert.assertTrue(message, message.contains("-H:Preserve=package=" + RuntimeClassLoadingUncompiledAOTMethodTest.class.getPackageName()));
    }

    /** {@code public class GuestOfInlined extends InlinedBase { public String name() { return "guest"; } }}. */
    private static byte[] generateGuestOfInlined() {
        return generateSubclass(GUEST_OF_INLINED_NAME, InlinedBase.class, cb -> cb.withMethodBody("name", MethodTypeDesc.of(CD_String), ACC_PUBLIC, code -> code.ldc("guest").areturn()));
    }

    /** {@code public class GuestOfUncalled extends UncalledBase { }}. */
    private static byte[] generateGuestOfUncalled() {
        return generateSubclass(GUEST_OF_UNCALLED_NAME, UncalledBase.class, _ -> {
        });
    }

    private static byte[] generateSubclass(String name, Class<?> superclass, Consumer<ClassBuilder> members) {
        ClassDesc superDesc = ClassDesc.of(superclass.getName());
        return ClassFile.of().build(ClassDesc.of(name), cb -> {
            cb.withFlags(ACC_PUBLIC | ACC_SUPER);
            cb.withSuperclass(superDesc);
            cb.withMethodBody(INIT_NAME, MTD_void, ACC_PUBLIC, code -> code.aload(0).invokespecial(superDesc, INIT_NAME, MTD_void).return_());
            members.accept(cb);
        });
    }

    /** {@code public class CallsHelper { public static String call() { return UncalledBase.helper(); } }}. */
    private static byte[] generateCallsHelper() {
        MethodTypeDesc stringReturn = MethodTypeDesc.of(CD_String);
        return ClassFile.of().build(ClassDesc.of(CALLS_HELPER_NAME), cb -> {
            cb.withFlags(ACC_PUBLIC | ACC_SUPER);
            cb.withMethodBody("call", stringReturn, ACC_PUBLIC | ACC_STATIC,
                            code -> code.invokestatic(ClassDesc.of(UncalledBase.class.getName()), "helper", stringReturn).areturn());
        });
    }
}
