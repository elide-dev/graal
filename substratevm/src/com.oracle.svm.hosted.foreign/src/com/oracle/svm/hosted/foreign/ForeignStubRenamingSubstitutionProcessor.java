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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.oracle.graal.pointsto.infrastructure.SubstitutionProcessor;
import com.oracle.graal.pointsto.meta.BaseLayerType;
import com.oracle.svm.hosted.annotation.CustomSubstitutionType;
import com.oracle.svm.shared.util.VMError;
import com.oracle.svm.util.OriginalClassProvider;

import jdk.vm.ci.meta.MetaUtil;
import jdk.vm.ci.meta.ResolvedJavaType;

/**
 * Gives the hidden classes that the JDK and the image builder generate for registered foreign
 * calls stable names, so that an image does not depend on the names of hidden classes, which
 * change from one build to another. Renamed are the upcall stubs and the downcall stubs with
 * specialized bindings (see {@code jdk.internal.foreign.abi.BindingSpecializer}) and the entry
 * methods of downcall handles (see {@link DowncallHandleEntryGenerator}).
 */
final class ForeignStubRenamingSubstitutionProcessor extends SubstitutionProcessor {
    private static final String UPCALL_STUB_CLASS_NAME_PREFIX = "jdk.internal.foreign.abi.UpcallStub/";
    private static final String[] CLASS_NAME_PREFIXES = {
                    UPCALL_STUB_CLASS_NAME_PREFIX,
                    DowncallHandleEntryGenerator.SPECIALIZED_STUB_CLASS_NAME + "/",
                    DowncallHandleEntryGenerator.ENTRY_CLASS_NAME + "/",
    };

    private final ConcurrentMap<Class<?>, String> stableNames = new ConcurrentHashMap<>();
    private final ConcurrentMap<ResolvedJavaType, ForeignStubSubstitutionType> typeSubstitutions = new ConcurrentHashMap<>();

    private static String prefixOf(String className) {
        for (String prefix : CLASS_NAME_PREFIXES) {
            if (className.startsWith(prefix)) {
                return prefix;
            }
        }
        return null;
    }

    /**
     * Registers the stable name of the hidden class {@code stubClass}: the internal name of a
     * hidden class with the same name prefix, for example
     * {@code Ljdk/internal/foreign/abi/UpcallStub.s<digest>;}.
     */
    void register(Class<?> stubClass, String stableName) {
        String prefix = prefixOf(stubClass.getName());
        VMError.guarantee(prefix != null && stubClass.isHidden(), "Not a hidden foreign stub class: %s", stubClass);
        VMError.guarantee(stableName.endsWith(";") && MetaUtil.internalNameToJava(stableName, true, true).startsWith(prefix),
                        "Invalid stable name for foreign stub %s: %s", stubClass, stableName);
        String previous = stableNames.putIfAbsent(stubClass, stableName);
        VMError.guarantee(previous == null || previous.equals(stableName), "Conflicting stable names for foreign stub %s: %s and %s", stubClass, previous, stableName);
    }

    @Override
    public ResolvedJavaType lookup(ResolvedJavaType type) {
        if (type instanceof ForeignStubSubstitutionType || type instanceof BaseLayerType || prefixOf(type.toClassName()) == null) {
            return type;
        }
        VMError.guarantee(type.isHidden(), "Expected a hidden foreign stub class: %s", type);
        String stableName = stableNames.get(OriginalClassProvider.getJavaClass(type));
        if (stableName == null) {
            return type;
        }
        return typeSubstitutions.computeIfAbsent(type, original -> new ForeignStubSubstitutionType(original, stableName));
    }
}

final class ForeignStubSubstitutionType extends CustomSubstitutionType {
    private final String stableName;

    ForeignStubSubstitutionType(ResolvedJavaType original, String stableName) {
        super(original);
        this.stableName = stableName;
    }

    @Override
    public String getName() {
        return stableName;
    }
}
