---
layout: docs
toc_group: truffle
link_title: Truffle AOT Compilation
permalink: /graalvm-as-a-platform/language-implementation-framework/AOT/
---
# Truffle AOT Tutorial

Many statically compiled languages, like C are designed to be compilable without prior execution.
By default, Truffle first interprets code before it is compiled.
In order to improve warmup speed of static languages AOT compilation can be supported.
The following tutorial describes how to support Truffle AOT in your language, how to trigger and test it.

## Language Support

In order for languages to support AOT compilation the language needs to implement the [RootNode.prepareForAOT()](https://www.graalvm.org/truffle/javadoc/com/oracle/truffle/api/nodes/RootNode.html#prepareForAOT--) method.
The language implementation can indicate support for AOT by returning a non `null` value in this method.
The goal for implementing a root node for AOT is to prepare all the AST nodes such that they no longer deoptimize when they are compiled without prior execution.

Typical actions performed in an implementation of this method are:

* Initialize local variable types in the FrameDescriptor of the root node. If a language uses local variables and their types are known, then this information must be provided to the FrameDescriptor. This step can often be done already during parsing.
* Compute the expected execution signature of a root node and return it. This step requires the parser to infer expected types
for arguments and return values.
* Prepare specializing nodes with profiles that do not invalidate on first execution. Truffle DSL supports preparation of specializing nodes for AOT. See the example AOT language for details.

### Trigger AOT compilation

AOT compilation can be triggered and tested by using the `--engine.CompileAOTOnCreate=true` option.
This will trigger AOT compilation for every created call target with a root node that supports AOT compilation.
A root node supports AOT compilation if it returns a non null value in [RootNode.prepareForAOT()](https://www.graalvm.org/truffle/javadoc/com/oracle/truffle/api/nodes/RootNode.html#prepareForAOT--).
Note that enabling this flag will also disable background compilation which makes it not suitable for production usage.

### Example Usage

Use the following documented and executable Truffle language as inspiration for AOT support:
[AOT Tutorial](https://github.com/oracle/graal/blob/master/truffle/src/com.oracle.truffle.api.dsl.test/src/com/oracle/truffle/api/dsl/test/examples/AOTTutorial.java)

The example is executable as mx unittest using `mx unittest AOTTutorial`.

## AOT Tier

The AOT tier compiles AOT-prepared *shadow* copies of shared call targets at Native Image build time.
A shadow is a clone of a call target's root node that is prepared and compiled before the target has ever run, while the live root node stays untouched.
At run time the AOT-compiled shadow code runs from the very first call, so an application skips the interpreter for that call target.
The runtime counts calls and loop iterations against `engine.AOTTierThreshold` and, once the threshold is reached, unlinks the AOT code and hands the call target over to the interpreter, which then warms up towards the just-in-time (JIT) compiler as usual.

For example, an engine option controls when the hand-over happens:

```shell
--engine.AOTTierThreshold=5000
```

### Preparing a Language for the AOT Tier

A root node takes part in the AOT tier only if it is cloneable: it must return `true` from [RootNode.isCloningAllowed()](https://www.graalvm.org/truffle/javadoc/com/oracle/truffle/api/nodes/RootNode.html#isCloningAllowed--), or support [RootNode.cloneUninitialized()](https://www.graalvm.org/truffle/javadoc/com/oracle/truffle/api/nodes/RootNode.html#cloneUninitialized--).
It must also implement [RootNode.prepareForAOT()](https://www.graalvm.org/truffle/javadoc/com/oracle/truffle/api/nodes/RootNode.html#prepareForAOT--), the same method the [Language Support](#language-support) section above describes.
Nodes annotated with `@GenerateAOT` are prepared automatically through `AOTSupport.prepareForAOT`.

A root node overrides [RootNode.cloneForAOT()](https://www.graalvm.org/truffle/javadoc/com/oracle/truffle/api/nodes/RootNode.html#cloneForAOT--) when the default uninitialized copy would share mutable speculative state with the live root node, for example frame-slot kinds held in a shared `FrameDescriptor`:

```java
@Override
protected RootNode cloneForAOT() {
    FunctionRootNode copy = (FunctionRootNode) cloneUninitialized();
    copy.frameDescriptor = frameDescriptor.copy();
    return copy;
}
```

Returning `null` from `cloneForAOT()` opts the root node out of the AOT tier.
The runtime calls `cloneForAOT()` only for a call target that has never executed.

The live abstract syntax tree (AST) is never prepared for AOT at build time.
AOT preparation state is sticky, and preparing the live AST would lower its JIT peak performance once it eventually warms up.
