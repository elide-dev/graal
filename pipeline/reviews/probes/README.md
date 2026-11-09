# Retained Review Counterexamples

These are the reviewer-authored probe sources, retained separately from the implementing agent's regression tests.
Use the matching source/JDK and built distributions described in the reproducer instructions.

From the GraalJS _graal-js_ suite, run each _probe.js_ or _.mjs_ entry with:

```shell
mx js --experimental-options --js.explicit-resource-management=true \
  --js.unhandled-rejections=throw /path/to/entry-file
```

Keep neighboring module files together.
_resource-module.mjs_, _module-resource.mjs_, _module-sync.mjs_ and _module-error.mjs_ are module fixtures, not standalone success probes.

For LCOV probes, build the tools distributions and use their classpath as in the minimal LCOV reproducer.
_ReviewProbe.java_ takes an output directory and writes coverage trace files for default/shared/empty/file URIs and real source paths.
_CopyEdgeProbe.java_ directly exercises the built printer with empty/no/byte content; it tests identifiers rather than coverage roots.

The resource URL probes use a JVM handler analogue and reflection into `URL` caching state.
Run them with `java --add-opens java.base/java.net=ALL-UNNAMED /path/to/Probe.java` (or _AuthorityProbe.java_).
They complement, and do not replace, the actual native baseline/fix reproducer.
The summaries record which outcomes were independently executed and which came from supplied logs.
