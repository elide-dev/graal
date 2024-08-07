/*
 * Copyright (c) 2017, 2024, Oracle and/or its affiliates.
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of
 * conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other materials provided
 * with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors may be used to
 * endorse or promote products derived from this software without specific prior written
 * permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS
 * OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
 * GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED
 * AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.oracle.truffle.llvm.runtime.nodes.memory.store;

import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.GenerateAOT;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.llvm.runtime.library.internal.LLVMManagedWriteLibrary;
import com.oracle.truffle.llvm.runtime.nodes.api.LLVMExpressionNode;
import com.oracle.truffle.llvm.runtime.nodes.api.LLVMStoreNode;
import com.oracle.truffle.llvm.runtime.nodes.api.LLVMToNativeNode;
import com.oracle.truffle.llvm.runtime.nodes.memory.load.LLVMDerefHandleGetReceiverNode;
import com.oracle.truffle.llvm.runtime.nodes.memory.store.LLVMI64StoreNodeGen.LLVMI64OffsetStoreNodeGen;
import com.oracle.truffle.llvm.runtime.nodes.memory.store.LLVMOffsetStoreNode.LLVMPrimitiveOffsetStoreNode;
import com.oracle.truffle.llvm.runtime.pointer.LLVMManagedPointer;
import com.oracle.truffle.llvm.runtime.pointer.LLVMNativePointer;
import com.oracle.truffle.llvm.runtime.pointer.LLVMPointer;

public abstract class LLVMI64StoreNode extends LLVMStoreNode {

    public abstract void executeWithTarget(LLVMPointer address, long value);

    @GenerateUncached
    public abstract static class LLVMI64OffsetStoreNode extends LLVMPrimitiveOffsetStoreNode {

        public static LLVMI64OffsetStoreNode create() {
            return LLVMI64OffsetStoreNodeGen.create(null, null, null);
        }

        public static LLVMI64OffsetStoreNode create(LLVMExpressionNode value) {
            return LLVMI64OffsetStoreNodeGen.create(null, null, value);
        }

        public abstract void executeWithTarget(LLVMPointer receiver, long offset, long value);

        @Specialization(guards = "!isAutoDerefHandle(addr)")
        protected void doOp(LLVMNativePointer addr, long offset, long value) {
            getLanguage().getLLVMMemory().putI64(this, addr.asNative() + offset, value);
        }

        @Specialization(guards = "isAutoDerefHandle(addr)")
        protected static void doOpDerefHandleI64(LLVMNativePointer addr, long offset, long value,
                        @Cached LLVMDerefHandleGetReceiverNode getReceiver,
                        @CachedLibrary(limit = "3") LLVMManagedWriteLibrary nativeWrite) {
            doOpManagedI64(getReceiver.execute(addr), offset, value, nativeWrite);
        }

        @Specialization(guards = "isAutoDerefHandle(addr)", replaces = "doOpDerefHandleI64")
        protected static void doOpDerefHandle(LLVMNativePointer addr, long offset, Object value,
                        @Cached LLVMDerefHandleGetReceiverNode getReceiver,
                        @CachedLibrary(limit = "3") LLVMManagedWriteLibrary nativeWrite) {
            doOpManaged(getReceiver.execute(addr), offset, value, nativeWrite);
        }

        @Specialization(guards = "!isAutoDerefHandle(addr)")
        protected void doOpNative(LLVMNativePointer addr, long offset, LLVMNativePointer value) {
            getLanguage().getLLVMMemory().putI64(this, addr.asNative() + offset, value.asNative());
        }

        @Specialization(replaces = "doOpNative", guards = "!isAutoDerefHandle(addr)")
        protected void doOp(LLVMNativePointer addr, long offset, Object value,
                        @Cached("createToNativeWithTarget()") LLVMToNativeNode toAddress) {
            getLanguage().getLLVMMemory().putI64(this, addr.asNative() + offset, toAddress.executeWithTarget(value).asNative());
        }

        @Specialization(limit = "3")
        @GenerateAOT.Exclude
        protected static void doOpManagedI64(LLVMManagedPointer address, long offset, long value,
                        @CachedLibrary("address.getObject()") LLVMManagedWriteLibrary nativeWrite) {
            nativeWrite.writeI64(address.getObject(), address.getOffset() + offset, value);
        }

        @Specialization(limit = "3", replaces = "doOpManagedI64")
        @GenerateAOT.Exclude
        protected static void doOpManaged(LLVMManagedPointer address, long offset, Object value,
                        @CachedLibrary("address.getObject()") LLVMManagedWriteLibrary nativeWrite) {
            nativeWrite.writeGenericI64(address.getObject(), address.getOffset() + offset, value);
        }
    }

    @Specialization(guards = "!isAutoDerefHandle(address)")
    protected void doOp(LLVMNativePointer address, long value) {
        getLanguage().getLLVMMemory().putI64(this, address, value);
    }

    @Specialization(guards = "isAutoDerefHandle(addr)")
    protected static void doOpDerefHandleI64(LLVMNativePointer addr, long value,
                    @Cached LLVMDerefHandleGetReceiverNode getReceiver,
                    @CachedLibrary(limit = "3") LLVMManagedWriteLibrary nativeWrite) {
        doOpManagedI64(getReceiver.execute(addr), value, nativeWrite);
    }

    @Specialization(guards = "isAutoDerefHandle(addr)", replaces = "doOpDerefHandleI64")
    protected static void doOpDerefHandle(LLVMNativePointer addr, Object value,
                    @Cached LLVMDerefHandleGetReceiverNode getReceiver,
                    @CachedLibrary(limit = "3") LLVMManagedWriteLibrary nativeWrite) {
        doOpManaged(getReceiver.execute(addr), value, nativeWrite);
    }

    @Specialization(guards = "!isAutoDerefHandle(address)")
    protected void doOpNative(LLVMNativePointer address, LLVMNativePointer value) {
        getLanguage().getLLVMMemory().putI64(this, address, value.asNative());
    }

    @Specialization(replaces = "doOpNative", guards = "!isAutoDerefHandle(addr)")
    protected void doOp(LLVMNativePointer addr, Object value,
                    @Cached("createToNativeWithTarget()") LLVMToNativeNode toAddress) {
        getLanguage().getLLVMMemory().putI64(this, addr, toAddress.executeWithTarget(value).asNative());
    }

    @Specialization(limit = "3")
    @GenerateAOT.Exclude
    protected static void doOpManagedI64(LLVMManagedPointer address, long value,
                    @CachedLibrary("address.getObject()") LLVMManagedWriteLibrary nativeWrite) {
        nativeWrite.writeI64(address.getObject(), address.getOffset(), value);
    }

    @Specialization(limit = "3", replaces = "doOpManagedI64")
    @GenerateAOT.Exclude
    protected static void doOpManaged(LLVMManagedPointer address, Object value,
                    @CachedLibrary("address.getObject()") LLVMManagedWriteLibrary nativeWrite) {
        nativeWrite.writeGenericI64(address.getObject(), address.getOffset(), value);
    }

    public static LLVMI64StoreNode create() {
        return LLVMI64StoreNodeGen.create(null, null);
    }
}
