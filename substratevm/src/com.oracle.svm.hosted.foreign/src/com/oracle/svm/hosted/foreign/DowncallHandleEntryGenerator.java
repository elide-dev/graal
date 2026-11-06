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
package com.oracle.svm.hosted.foreign;

import java.lang.classfile.ClassFile;
import java.lang.classfile.TypeKind;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.DynamicConstantDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleInfo;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Set;

import com.oracle.svm.shared.util.ReflectionUtil;
import com.oracle.svm.shared.util.VMError;

import jdk.internal.foreign.abi.AbstractLinker;
import jdk.internal.foreign.abi.SharedUtils;

/**
 * Creates downcall method handles at image build time and generates their entry methods; see
 * {@link com.oracle.svm.core.foreign.DowncallHandleLinker}.
 * <p>
 * A handle is created by the JDK linker of the image builder's JVM, so its bindings are
 * specialized to bytecode in a hidden class named {@value #SPECIALIZED_STUB_CLASS_NAME} (see
 * {@code jdk.internal.foreign.abi.BindingSpecializer}). The entry method of a handle is the
 * static method {@code invoke} of a generated hidden class named
 * {@value #ENTRY_CLASS_NAME}, which has the handle's type and invokes the handle, taken from
 * the class data of its class, with {@code invokeExact}.
 */
final class DowncallHandleEntryGenerator {
    static final String SPECIALIZED_STUB_CLASS_NAME = "jdk.internal.foreign.abi.DowncallStub";
    static final String ENTRY_CLASS_NAME = "jdk.internal.foreign.abi.DowncallHandleEntry";
    static final String ENTRY_METHOD_NAME = "invoke";

    private static final Method DOWNCALL_HANDLE0 = ReflectionUtil.lookupMethod(AbstractLinker.class, "downcallHandle0", FunctionDescriptor.class, Linker.Option[].class);
    private static final Method STRIP_NAMES = ReflectionUtil.lookupMethod(AbstractLinker.class, "stripNames", FunctionDescriptor.class);

    private DowncallHandleEntryGenerator() {
    }

    /**
     * Creates the handle that {@code Linker.downcallHandle(FunctionDescriptor, Linker.Option...)}
     * returns on the image builder's JVM, without the native access check of that method.
     */
    static MethodHandle createDowncallHandle(Linker linker, FunctionDescriptor descriptor, Linker.Option[] options) {
        return ReflectionUtil.invokeMethod(DOWNCALL_HANDLE0, linker, descriptor, options);
    }

    /** Removes the layout names from the descriptor, as the JDK linker does before it caches a handle. */
    static FunctionDescriptor stripNames(FunctionDescriptor descriptor) {
        return ReflectionUtil.invokeMethod(STRIP_NAMES, null, descriptor);
    }

    /**
     * Finds the hidden class with the specialized bindings of the downcall handle: the handle is
     * a chain of bound method handles around the direct method handle of the class's
     * {@code invoke} method. Returns null if the handle does not invoke such a class, for example
     * because the JDK of the image builder interprets the bindings
     * ({@code -Djdk.internal.foreign.DowncallLinker.USE_SPEC=false}).
     */
    static Class<?> findSpecializedStubClass(MethodHandle handle, Lookup lookup) {
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<MethodHandle> pending = new ArrayDeque<>();
        pending.add(handle);
        while (!pending.isEmpty()) {
            MethodHandle current = pending.poll();
            if (!visited.add(current)) {
                continue;
            }
            Class<?> directTarget = directTargetClass(current, lookup);
            if (directTarget != null && directTarget.getName().startsWith(SPECIALIZED_STUB_CLASS_NAME + "/")) {
                return directTarget;
            }
            for (Class<?> c = current.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field field : c.getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers()) && !field.getType().isPrimitive()) {
                        Object value = ReflectionUtil.readField(c, field.getName(), current);
                        if (value instanceof MethodHandle nested) {
                            pending.add(nested);
                        }
                    }
                }
            }
        }
        return null;
    }

    private static Class<?> directTargetClass(MethodHandle handle, Lookup lookup) {
        try {
            MethodHandleInfo info = lookup.revealDirect(handle);
            return info.getDeclaringClass();
        } catch (IllegalArgumentException e) {
            /* Not a direct method handle. */
            return null;
        }
    }

    /**
     * Generates the entry method of the downcall handle. The method is defined in a hidden class
     * of the package of {@code jdk.internal.foreign.abi.SharedUtils}, which {@code lookup} must be
     * able to define classes in.
     */
    static Method generateEntry(MethodHandle handle, Lookup lookup) {
        MethodType type = handle.type();
        MethodTypeDesc typeDesc = MethodTypeDesc.ofDescriptor(type.descriptorString());
        byte[] bytes = ClassFile.of().build(ClassDesc.of(ENTRY_CLASS_NAME), clb -> {
            clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_SUPER | ClassFile.ACC_SYNTHETIC)
                            .withSuperclass(ConstantDescs.CD_Object)
                            .withVersion(ClassFile.latestMajorVersion(), 0)
                            .withMethod(ENTRY_METHOD_NAME, typeDesc, ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, mb -> mb.withCode(cb -> {
                                cb.loadConstant(DynamicConstantDesc.of(ConstantDescs.BSM_CLASS_DATA));
                                cb.checkcast(ConstantDescs.CD_MethodHandle);
                                int slot = 0;
                                for (Class<?> parameterType : type.parameterArray()) {
                                    TypeKind kind = TypeKind.from(parameterType);
                                    cb.loadLocal(kind, slot);
                                    slot += kind.slotSize();
                                }
                                cb.invokevirtual(ConstantDescs.CD_MethodHandle, "invokeExact", typeDesc);
                                cb.return_(TypeKind.from(type.returnType()));
                            }));
        });
        try {
            Lookup entryLookup = MethodHandles.privateLookupIn(SharedUtils.class, lookup).defineHiddenClassWithClassData(bytes, handle, false);
            return entryLookup.lookupClass().getDeclaredMethod(ENTRY_METHOD_NAME, type.parameterArray());
        } catch (ReflectiveOperationException e) {
            throw VMError.shouldNotReachHere("Cannot generate the entry method of downcall handle " + handle, e);
        }
    }
}
