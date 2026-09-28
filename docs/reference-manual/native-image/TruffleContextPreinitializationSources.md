---
layout: docs
toc_group: native-image
link_title: Parsing Sources During Context Pre-Initialization
permalink: /reference-manual/native-image/TruffleContextPreinitializationSources/
---
# Parsing Sources During Context Pre-Initialization

If your application embeds a Graal Language, Native Image can create and initialize a polyglot context while building the native executable, and store it in the image heap.
This is called [context pre-initialization](../../../truffle/docs/AOTOverview.md#preinitialization-of-the-first-context).
At run time, the first context of the native executable uses the pre-initialized context instead of initializing a new one.

Context pre-initialization can also parse sources of your application, without executing them.
The call targets that the parser produces are stored in the image heap with the pre-initialized context.
When a context that uses the pre-initialized context parses an equal source at run time, it uses the stored call target instead of parsing the source again.
This reduces the startup time of applications that parse large sources, such as bundled scripts or modules.

A source is parsed during pre-initialization only if its language is pre-initialized.
To pre-initialize a language, list it in the `-Dpolyglot.image-build-time.PreinitializeContexts` system property at build time.
The language must also support context pre-initialization, that is, it must override `TruffleLanguage.patchContext`.

## Listing Sources in a System Property

To parse sources read from files, pass the `-Dpolyglot.image-build-time.PreinitializeSources` system property to `native-image`.
Its value is a comma-separated list of `<languageId>:<path>` entries.
For example, to parse _main.js_ during pre-initialization of a JavaScript context, run:

```shell
native-image \
  -Dpolyglot.image-build-time.PreinitializeContexts=js \
  -Dpolyglot.image-build-time.PreinitializeSources=js:/path/to/main.js \
  -cp app.jar MainClass
```

Each source has the file name as its name, without the path on the build machine, and no MIME type.
No build-machine path is therefore embedded in the native executable.

Every language listed in the system property must be listed in `PreinitializeContexts` and must override `TruffleLanguage.patchContext`.
Otherwise the build fails with an `IllegalArgumentException` that names the offending entry.

Because the system property cannot express a MIME type, a path, or a URI, some sources listed in it never match a source that your application parses.
For example, a JavaScript module (`application/javascript+module`) listed in the system property never matches a module that your application parses.
Register such sources with `PreinitializedSources` instead.

## Registering Sources

To parse sources that carry a MIME type, a path, or a URI, register them with `org.graalvm.polyglot.PreinitializedSources`.
Register sources while the native executable is built, before the contexts are pre-initialized, for example from `Feature.duringSetup`:

```java
public final class MyFeature implements Feature {
    @Override
    public void duringSetup(DuringSetupAccess access) {
        PreinitializedSources.register(Source.newBuilder("js", moduleCode, "main.mjs").mimeType("application/javascript+module").buildLiteral());
    }
}
```

Registering is a hint.
A registered source is parsed only if the native executable pre-initializes its language, that is, the language is listed in `-Dpolyglot.image-build-time.PreinitializeContexts`.
Otherwise the source is skipped, so a library can register its sources from its own feature without failing the builds of native executables that do not pre-initialize its language.
If the language is pre-initialized but does not override `TruffleLanguage.patchContext`, the build fails.

A call to `PreinitializedSources.register` registers all of the given sources, or none of them if it throws.
Registering after the contexts were pre-initialized, or in a native executable at run time, throws an `IllegalStateException`.

## Matching Sources at Run Time

To use a call target parsed during pre-initialization, your application must parse a source that is equal to the parsed one.
Two sources are equal only if everything that the source builder can set is equal: the characters, name, MIME type, path, URI, and flags.
Build a registered source exactly as your application builds it at run time.

Keep the following limits in mind:

* Only sources that your application parses, for example with `Context.eval` or `Context.parse`, can match a source that is listed in the system property or registered. A source that a language creates itself, for example a module that its module loader loads for an `import`, is never equal to such a source.
* A source that a language parses itself during pre-initialization, for example a built-in library, is also kept in the image heap. If the language parses an equal source at run time, it uses the stored call target.
* A source built from a `File` or a `URL` has the path or URI of the build machine. It is equal to a source of the native executable only if the executable builds its source from the same path or URI. Prefer a literal source with a name and a MIME type.
* On the Java HotSpot VM, setting `-Dpolyglot.image-build-time.PreinitializeContexts` pre-initializes the contexts when the polyglot implementation is loaded, to debug context pre-initialization. Sources cannot be registered in this mode.

## Using the Pre-Initialized Context

The stored call targets belong to the pre-initialized context, so only a context that uses the pre-initialized context can use them.
The pre-initialized context is used by one engine per process, the first one that may use it:

* An engine that is created implicitly for a context, for example by `Context.create()` without an explicit `Engine`, uses the pre-initialized context.
* An explicitly created `Engine` does not use it by default.

A context that is created with options incompatible with the pre-initialized context, or with `engine.UsePreInitializedContext=false`, initializes a new context and parses its sources again.

## Related Documentation

* [Truffle AOT Overview](../../../truffle/docs/AOTOverview.md)
* [Embedding Languages](../embedding/embed-languages.md)
