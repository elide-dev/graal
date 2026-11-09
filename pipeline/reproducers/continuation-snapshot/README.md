# Continuation Snapshot Reproducer

This source-only package reproduces a known downstream fix on an exact upstream base.
It does not contain a native executable or a Native Image bundle.

## Source and Toolchain

- Upstream base: `a369308fb9f4fbed7746caf33c87eb8f3a61b613`.
- Clean candidate: `6f55b27d87897825f2a53af3b0a5bdbd7650aef8` in [the public fork](https://github.com/elide-dev/graal/commit/6f55b27d87897825f2a53af3b0a5bdbd7650aef8).
- Target: `oracle/graal`, `master`.
- Verified platform: Linux x86_64.
- JDK: LabsJDK `25.0.5.1+0-jvmci-25.6-b27`.
- Fork CI build tool: mx `ef054fc6eca1d516820bd040efa3c0a0f6ef1899` (7.88.0).
- Local implementation checks used mx `e64ff8c8cca770c8cdbb62617ebd0fa17dd184b1`; both sides used the same local revision.

## Run the Comparison

Fetch both revisions from `https://github.com/elide-dev/graal.git`.
Create separate worktrees and separate `MX_ALT_OUTPUT_ROOT` directories for the two revisions.
Set `JAVA_HOME` to the pinned JDK and put the pinned mx on `PATH`.
Extract this ZIP and set `SAMPLE_DIR` to its absolute directory.
On the baseline only, apply the identical regression before building:

```shell
git apply "$SAMPLE_DIR/tests.patch"
```

From `substratevm/` in each worktree, run these identical commands:

```shell
ulimit -c 0
mx --max-cpus=8 --native-images=false build --build-logs=silent
mx --max-cpus=8 --native-images=false native-unittest ContinuationStackWalkerTest --build-args --parallelism=8 -J-Xmx8g
```

Baseline aborts with Access is outside of the stack of the stored continuation; candidate passes one test. Snapshot behavior is specific to Native Image internal walks, not a public HotSpot concurrent-walk guarantee.
Only the baseline receives `tests.patch`; the candidate already contains that regression.
Do not run concurrent mx builds or style checks in one workspace.
Run `mx checkstyle --primary` in the candidate suite.

## Scope

The package requires a source build and normal Native Image build prerequisites, including a C toolchain and zlib headers.
No external data or paid service is required.
Other architectures and alternate JDK versions were not executed.
Full commands, exact identities, failed attempts, independent reviews and fork CI receipts accompany this package in the public evidence tree.
