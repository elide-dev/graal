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
package com.oracle.svm.core.methodhandles;

import java.lang.reflect.Field;

import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;

import com.oracle.svm.shared.util.ReflectionUtil;

import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.spi.CoreProviders;
import jdk.vm.ci.meta.ResolvedJavaMethod;

/**
 * Links the invocation of a compilation-constant method handle to a direct call when a graph is
 * compiled at image run time, for example during Truffle partial evaluation.
 * <p>
 * A native image executes a method handle that was not intrinsified at image build time in the
 * lambda form interpreter, and a runtime compiler has no {@code MethodHandleAccessProvider} to look
 * through the lambda forms of a constant handle. The image builder can instead prepare a direct
 * implementation of selected handles ahead of time and mark those handles through
 * {@link Target_java_lang_invoke_MethodHandle#linkInfo}. A linker then replaces the call of
 * {@code MethodHandle.invokeBasic} that remains of a {@code MethodHandle.invokeExact} or
 * {@code MethodHandle.invoke} call site after the JDK's type check has been folded with a call
 * to that implementation when the handle is a constant.
 */
public interface RuntimeMethodHandleLinker {

    /** The direct target of a linked invocation and the arguments to pass to it. */
    record LinkedInvoke(ResolvedJavaMethod target, ValueNode[] arguments) {
    }

    static boolean isAvailable() {
        return ImageSingletons.contains(RuntimeMethodHandleLinker.class);
    }

    static RuntimeMethodHandleLinker singleton() {
        return ImageSingletons.lookup(RuntimeMethodHandleLinker.class);
    }

    /** The field of {@code MethodHandle} that holds the link information of a handle. */
    @Platforms(Platform.HOSTED_ONLY.class)
    static Field linkInfoField() {
        return ReflectionUtil.lookupField(Target_java_lang_invoke_MethodHandle.class, "linkInfo");
    }

    /**
     * Returns whether {@code method} is {@code MethodHandle.invokeBasic}, in native images a
     * method with the basic types of the call site that boxes its arguments and runs the lambda
     * form interpreter. Its receiver is the handle.
     */
    static boolean isInvokeBasic(ResolvedJavaMethod method) {
        /* The name is checked first because resolving the declaring class name is more expensive. */
        return "invokeBasic".equals(method.getName()) && !method.isStatic() && "java.lang.invoke.MethodHandle".equals(method.getDeclaringClass().toJavaName(true));
    }

    /**
     * Returns the link information of a method handle at image build time, or null if the handle
     * cannot be linked directly. The result is stored in
     * {@link Target_java_lang_invoke_MethodHandle#linkInfo}.
     */
    @Platforms(Platform.HOSTED_ONLY.class)
    MethodHandleLinkInfo hostedLinkInfo(Object methodHandle);

    /**
     * Returns the link information of the handle that {@link java.lang.invoke.MethodHandle#bindTo}
     * creates from a handle with the link information {@code linkInfo} and the bound value, or
     * null if the bound handle cannot be linked directly.
     */
    MethodHandleLinkInfo bindLinkInfo(MethodHandleLinkInfo linkInfo, Object boundValue);

    /**
     * Links a call of {@link #isInvokeBasic MethodHandle.invokeBasic} to a direct target. The
     * first argument is the handle, which must be a constant that carries link information for
     * the call to be linked. Returns null if the call must remain as it is. Nodes that the linked
     * call needs are added to {@code graph}.
     */
    LinkedInvoke link(ResolvedJavaMethod invokeBasic, ValueNode[] arguments, CoreProviders providers, StructuredGraph graph);
}
