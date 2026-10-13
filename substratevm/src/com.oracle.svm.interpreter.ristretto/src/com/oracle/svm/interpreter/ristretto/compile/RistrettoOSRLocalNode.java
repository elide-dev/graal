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
package com.oracle.svm.interpreter.ristretto.compile;

import static jdk.graal.compiler.nodeinfo.NodeCycles.CYCLES_2;
import static jdk.graal.compiler.nodeinfo.NodeSize.SIZE_1;

import jdk.graal.compiler.core.common.type.Stamp;
import jdk.graal.compiler.graph.IterableNodeType;
import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.calc.FloatingNode;
import jdk.vm.ci.meta.JavaKind;

/**
 * The value of an interpreter local or lock slot at the entry of a Ristretto OSR compilation.
 *
 * {@link RistrettoOnStackReplacementPhase} puts these nodes into the OSR entry frame state, so
 * every deoptimization of the compiled code, including one at the very start, restores the
 * values that the interpreter frame held when it entered the compiled code. The nodes stay
 * floating, with no inputs and no frame state, until
 * {@link RistrettoOSRLocalLoweringPhase} turns them into loads from the transferred interpreter
 * frame at the front of the graph, once all deoptimization points are fixed (compare HotSpot's
 * {@code OSRLocalNode}).
 */
@NodeInfo(cycles = CYCLES_2, size = SIZE_1)
public final class RistrettoOSRLocalNode extends FloatingNode implements IterableNodeType {
    public static final NodeClass<RistrettoOSRLocalNode> TYPE = NodeClass.create(RistrettoOSRLocalNode.class);

    private final int index;
    private final JavaKind kind;
    private final boolean lock;

    /**
     * @param index the local slot, or the lock depth if {@code lock} is set
     * @param kind the stack kind of the value
     * @param lock whether this is the object of a monitor the interpreter frame holds
     */
    public RistrettoOSRLocalNode(int index, JavaKind kind, boolean lock, Stamp stamp) {
        super(TYPE, stamp);
        this.index = index;
        this.kind = kind;
        this.lock = lock;
    }

    public int index() {
        return index;
    }

    public JavaKind kind() {
        return kind;
    }

    public boolean isLock() {
        return lock;
    }
}
