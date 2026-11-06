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
package com.oracle.svm.core.foreign;

import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;

import com.oracle.svm.core.methodhandles.RuntimeMethodHandleLinker;
import com.oracle.svm.guest.staging.util.ImageHeapList;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.AllAccess;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.NoLayeredCallbacks;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.PartiallyLayerAware;
import com.oracle.svm.shared.singletons.traits.SingletonLayeredInstallationKind.Duplicable;
import com.oracle.svm.shared.singletons.traits.SingletonTraits;
import com.oracle.svm.shared.util.ReflectionUtil;
import com.oracle.svm.shared.util.VMError;

import jdk.graal.compiler.nodes.ConstantNode;
import jdk.graal.compiler.nodes.NodeView;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.spi.CoreProviders;
import jdk.graal.compiler.nodes.type.StampTool;
import jdk.vm.ci.meta.ConstantReflectionProvider;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;
import jdk.vm.ci.meta.Signature;

/**
 * Links the invocation of a constant downcall method handle in runtime-compiled code to the entry
 * method that the image builder generated for the handle.
 * <p>
 * The image builder creates the method handle of every registered downcall at build time, with
 * the bindings specialized to bytecode by the JDK, and generates for it an entry method: a static
 * method with the handle's type whose only statement is an {@code invokeExact} of the handle as a
 * constant. That invocation is intrinsified when the image is built, so the entry method does the
 * handle's argument and return conversions, acquires the sessions of memory segment arguments
 * while the native function runs, and calls the native-image downcall stub directly. Runtime
 * compilation inlines the entry method in place of the {@code MethodHandle.invokeBasic} call of
 * an {@code invokeExact} or {@code invoke} call site whose handle is constant, so the compiled
 * code neither boxes arguments nor runs the lambda form interpreter.
 * <p>
 * Supported are the handles returned by {@code Linker.downcallHandle} for a registered function
 * descriptor and linker options, with or without the function address bound as the first argument.
 * Handles derived from them through other combinators are invoked as before.
 */
@SingletonTraits(access = AllAccess.class, layeredCallbacks = NoLayeredCallbacks.class, layeredInstallationKind = Duplicable.class, other = PartiallyLayerAware.class)
public final class DowncallHandleLinker implements RuntimeMethodHandleLinker {
    /** The entry methods, indexed by {@link DowncallHandleInfo#entryIndex}. */
    private final List<ResolvedJavaMethod> entries = ImageHeapList.create(ResolvedJavaMethod.class);

    private ResolvedJavaType infoType;
    private ResolvedJavaField linkInfoField;
    private ResolvedJavaField entryIndexField;
    private ResolvedJavaField boundAddressField;

    @Platforms(Platform.HOSTED_ONLY.class) //
    private final Map<Object, DowncallHandleInfo> hostedInfos = Collections.synchronizedMap(new IdentityHashMap<>());

    @Platforms(Platform.HOSTED_ONLY.class)
    public DowncallHandleLinker() {
    }

    /**
     * Resolves the fields that {@link #link} reads through constant reflection.
     * {@code registerAsAccessed} must make the fields available at image run time.
     */
    @Platforms(Platform.HOSTED_ONLY.class)
    public void initialize(MetaAccessProvider metaAccess, Consumer<ResolvedJavaField> registerAsAccessed) {
        infoType = metaAccess.lookupJavaType(DowncallHandleInfo.class);
        linkInfoField = metaAccess.lookupJavaField(RuntimeMethodHandleLinker.linkInfoField());
        entryIndexField = metaAccess.lookupJavaField(ReflectionUtil.lookupField(DowncallHandleInfo.class, "entryIndex"));
        boundAddressField = metaAccess.lookupJavaField(ReflectionUtil.lookupField(DowncallHandleInfo.class, "boundAddress"));
        for (ResolvedJavaField field : List.of(linkInfoField, entryIndexField, boundAddressField)) {
            registerAsAccessed.accept(field);
        }
    }

    /**
     * Registers {@code entry} as the entry method of the downcall handle {@code handle}, which
     * must have been created at image build time and must have the entry method's type.
     */
    @Platforms(Platform.HOSTED_ONLY.class)
    public void registerEntry(MethodHandle handle, ResolvedJavaMethod entry) {
        VMError.guarantee(entry.isStatic() && entry.getSignature().getParameterCount(false) == handle.type().parameterCount(), "Entry method %s does not match the handle type %s", entry,
                        handle.type());
        int index;
        synchronized (entries) {
            index = entries.size();
            entries.add(entry);
        }
        DowncallHandleInfo previous = hostedInfos.put(handle, new DowncallHandleInfo(index, null));
        VMError.guarantee(previous == null, "Downcall handle registered twice: %s", handle);
    }

    @Override
    public Object hostedLinkInfo(Object methodHandle) {
        return hostedInfos.get(methodHandle);
    }

    @Override
    public Object bindLinkInfo(Object linkInfo, Object boundValue) {
        if (linkInfo instanceof DowncallHandleInfo info && info.boundAddress == null && boundValue instanceof MemorySegment address) {
            return new DowncallHandleInfo(info.entryIndex, address);
        }
        return null;
    }

    @Override
    public LinkedInvoke link(ResolvedJavaMethod invokeBasic, ValueNode[] arguments, CoreProviders providers, StructuredGraph graph) {
        if (arguments.length < 1 || !RuntimeMethodHandleLinker.isInvokeBasic(invokeBasic)) {
            return null;
        }
        JavaConstant handle = arguments[0].asJavaConstant();
        if (handle == null || handle.isNull()) {
            return null;
        }
        ConstantReflectionProvider constantReflection = providers.getConstantReflection();
        MetaAccessProvider metaAccess = providers.getMetaAccess();
        JavaConstant info = constantReflection.readFieldValue(linkInfoField, handle);
        if (info == null || info.isNull() || !infoType.equals(metaAccess.lookupJavaType(info))) {
            return null;
        }
        ResolvedJavaMethod entry = entries.get(constantReflection.readFieldValue(entryIndexField, info).asInt());
        JavaConstant boundAddress = constantReflection.readFieldValue(boundAddressField, info);
        int boundCount = boundAddress.isNull() ? 0 : 1;

        /*
         * The arguments of invokeBasic have the basic types of the handle's type, which is the
         * entry method's type without the bound address: the call site's type was checked against
         * the handle's type (invokeExact) or the handle was adapted to it (invoke) before. The
         * entry method's parameter types are more precise; the call is only linked when the
         * compiler can see that the arguments have them, which is the case when the arguments
         * come from expressions of the call site's static types.
         */
        Signature signature = entry.getSignature();
        int argumentCount = arguments.length - 1;
        if (argumentCount + boundCount != signature.getParameterCount(false)) {
            return null;
        }
        ValueNode[] linkedArguments = new ValueNode[signature.getParameterCount(false)];
        if (boundCount == 1) {
            linkedArguments[0] = ConstantNode.forConstant(boundAddress, metaAccess, graph);
        }
        for (int i = 0; i < argumentCount; i++) {
            ValueNode argument = arguments[i + 1];
            int parameterIndex = i + boundCount;
            JavaKind parameterKind = signature.getParameterKind(parameterIndex);
            if (argument.getStackKind() != parameterKind.getStackKind()) {
                return null;
            }
            if (parameterKind == JavaKind.Object) {
                ResolvedJavaType parameterType = (ResolvedJavaType) signature.getParameterType(parameterIndex, null);
                ResolvedJavaType argumentType = StampTool.typeOrNull(argument.stamp(NodeView.DEFAULT));
                if (argumentType == null || !parameterType.isAssignableFrom(argumentType)) {
                    return null;
                }
            }
            linkedArguments[parameterIndex] = argument;
        }
        return new LinkedInvoke(entry, linkedArguments);
    }
}
