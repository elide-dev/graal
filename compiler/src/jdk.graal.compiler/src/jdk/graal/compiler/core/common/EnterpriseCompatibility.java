/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
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
package jdk.graal.compiler.core.common;

import jdk.graal.compiler.options.OptionDescriptor;

/// Compatibility with Oracle's enterprise modules, for an EE distribution that runs this compiler
/// and the open-source Truffle runtime with Oracle's prebuilt enterprise modules
/// (`com.oracle.graal.graal_enterprise`, `com.oracle.truffle.enterprise.svm`) on top.
///
/// Upstream moved a series of optimizations from the enterprise compiler into this one (strip
/// mining, loop inversion and rotation, aggressive partial unrolling, simulation-based peeling,
/// partial redundancy elimination, deduplication, ...). An enterprise compiler built before that
/// still has its own copy of each and inserts it into the phase plan. With such an enterprise
/// compiler present, the copies here are off by default ({@link #MOVED_OPTIMIZATIONS}), so the tiers
/// build the phase plan the enterprise compiler expects and each optimization runs once, in the
/// enterprise compiler's version.
///
/// Likewise, upstream moved the typed (unboxed) calling convention for compiled Truffle calls from
/// the enterprise Truffle runtime into `com.oracle.svm.truffle.api.TypedEntrySupport`. An enterprise
/// Truffle runtime built before that still has its own copy, and with one present the open-source
/// copy stays dormant ({@link #hasEnterpriseTruffleTypedEntries}).
public final class EnterpriseCompatibility {

    private EnterpriseCompatibility() {
    }

    /// Whether an enterprise compiler that still has its own copies of the moved optimizations runs
    /// on top of this compiler. Oracle removed those copies from its enterprise compiler once they
    /// were in this one (e.g. Oracle GraalVM 25.5 EA 25i5 ea.03 has none), so this checks for one of
    /// them: its loop inversion phase. GraalVM CE has an empty placeholder module of the same name.
    public static final boolean ENTERPRISE_COMPILER = ModuleLayer.boot().findModule("com.oracle.graal.graal_enterprise").map(
                    m -> Class.forName(m, "com.oracle.graal.compiler.enterprise.phases.LoopInversionPhase") != null).orElse(false);

    /// The default for the options of the optimizations moved from the enterprise compiler: on,
    /// unless the enterprise compiler is present.
    public static final boolean MOVED_OPTIMIZATIONS = !ENTERPRISE_COMPILER;

    /// Whether an enterprise Truffle runtime that still has its own copy of the typed calling
    /// convention for compiled Truffle calls is visible from the given module layer (Oracle GraalVM
    /// 25.5 EA builds up to at least 25i5 ea.04, built before upstream moved it into
    /// `org.graalvm.truffle.runtime.svm`). This checks for its `TypedEntrySupport`. The layer is
    /// that of the open-source copy: the image builder loads the Truffle builder modules into the
    /// image class loader's layer, not the boot layer. Null (an unnamed module) means the boot layer.
    public static boolean hasEnterpriseTruffleTypedEntries(ModuleLayer layer) {
        return (layer == null ? ModuleLayer.boot() : layer).findModule("com.oracle.truffle.enterprise.svm").map(
                        m -> Class.forName(m, "com.oracle.svm.enterprise.truffle.compiler.TypedEntrySupport") != null).orElse(false);
    }

    /// For two option descriptors with the same name, the one to keep, or null if they conflict. The
    /// moved optimizations' options have the same names here and in the enterprise compiler, and the
    /// typed-entry options the same names in the open-source and the enterprise Truffle runtime; the
    /// enterprise one is kept, as its code is what runs.
    public static OptionDescriptor resolveDuplicateOption(OptionDescriptor a, OptionDescriptor b) {
        if (isEnterprise(a) && isSupersededCopy(b)) {
            return a;
        }
        if (isEnterprise(b) && isSupersededCopy(a)) {
            return b;
        }
        return null;
    }

    private static boolean isEnterprise(OptionDescriptor descriptor) {
        String module = descriptor.getDeclaringClass().getModule().getName();
        return module != null && module.startsWith("com.oracle.") && module.contains("enterprise");
    }

    /// Whether the option belongs to an open-source copy that an enterprise module present at build
    /// time supersedes.
    private static boolean isSupersededCopy(OptionDescriptor descriptor) {
        Module module = descriptor.getDeclaringClass().getModule();
        if (module == EnterpriseCompatibility.class.getModule()) {
            return ENTERPRISE_COMPILER;
        }
        if ("org.graalvm.truffle.runtime.svm".equals(module.getName())) {
            return hasEnterpriseTruffleTypedEntries(module.getLayer());
        }
        return false;
    }
}
