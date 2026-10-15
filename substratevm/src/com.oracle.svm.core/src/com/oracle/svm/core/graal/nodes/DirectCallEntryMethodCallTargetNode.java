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
package com.oracle.svm.core.graal.nodes;

import jdk.graal.compiler.core.common.type.StampPair;
import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.java.MethodCallTargetNode;
import jdk.vm.ci.meta.JavaTypeProfile;
import jdk.vm.ci.meta.ResolvedJavaMethod;

/**
 * A call to a signature-polymorphic method-handle intrinsic ({@code MethodHandle.invokeBasic},
 * {@code linkToStatic} or {@code linkToSpecial}) in runtime-compiled code that calls the method the
 * intrinsic links to directly when that method has compiled code (elide-dev/graal#112).
 * <p>
 * {@link #getEntryHolder()} is the interpreter method the call links to, or the intrinsic's own
 * interpreter method when that is not known. Lowering reads its direct call entry right before the
 * call: when there is one, the call goes there with the intrinsic's arguments, of which the callee
 * ignores a trailing member name; otherwise the call goes through the interpreter's enter stub for
 * the intrinsic, as any other call to it.
 */
@NodeInfo
public final class DirectCallEntryMethodCallTargetNode extends MethodCallTargetNode {
    public static final NodeClass<DirectCallEntryMethodCallTargetNode> TYPE = NodeClass.create(DirectCallEntryMethodCallTargetNode.class);

    @Input ValueNode entryHolder;

    public DirectCallEntryMethodCallTargetNode(InvokeKind invokeKind, ResolvedJavaMethod targetMethod, ValueNode[] arguments, StampPair returnStamp, JavaTypeProfile typeProfile,
                    ValueNode entryHolder) {
        super(TYPE, invokeKind, targetMethod, arguments, returnStamp, typeProfile);
        this.entryHolder = entryHolder;
    }

    public ValueNode getEntryHolder() {
        return entryHolder;
    }
}
