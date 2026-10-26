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

/// Compatibility with Oracle's enterprise compiler, for an EE distribution that runs this compiler
/// with Oracle's prebuilt enterprise compiler module (`com.oracle.graal.graal_enterprise`) on top.
///
/// Upstream moved a series of optimizations from the enterprise compiler into this one (strip
/// mining, loop inversion and rotation, aggressive partial unrolling, simulation-based peeling,
/// partial redundancy elimination, deduplication, ...). An enterprise compiler built before that
/// still has its own copy of each and inserts it into the phase plan. With such an enterprise
/// compiler present, the copies here are off by default ({@link #MOVED_OPTIMIZATIONS}), so the tiers
/// build the phase plan the enterprise compiler expects and each optimization runs once, in the
/// enterprise compiler's version.
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

    /// For two option descriptors with the same name, the one to keep, or null if they conflict. The
    /// moved optimizations' options have the same names here and in the enterprise compiler; the
    /// enterprise compiler's are kept, as its phases are the ones that run.
    public static OptionDescriptor resolveDuplicateOption(OptionDescriptor a, OptionDescriptor b) {
        if (!ENTERPRISE_COMPILER) {
            return null;
        }
        if (isEnterprise(a) && isCompiler(b)) {
            return a;
        }
        if (isEnterprise(b) && isCompiler(a)) {
            return b;
        }
        return null;
    }

    private static boolean isEnterprise(OptionDescriptor descriptor) {
        String module = descriptor.getDeclaringClass().getModule().getName();
        return module != null && module.startsWith("com.oracle.") && module.contains("enterprise");
    }

    private static boolean isCompiler(OptionDescriptor descriptor) {
        return EnterpriseCompatibility.class.getModule() == descriptor.getDeclaringClass().getModule();
    }
}
