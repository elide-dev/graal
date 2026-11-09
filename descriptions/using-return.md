## Summary

Functions containing `using` can return `undefined`; asynchronous disposal can also lose a normal completion value after suspension.
Preserve enclosing block flags and completion values across resumed finally execution while retaining pending exceptions.

**[Elide GraalVM fork dashboard: This PR](https://graalvm.elide.dev/contributions/contribution-first-using-return)**

## Testing

Pretested on the fork at upstream [`934cb7cb826`](https://github.com/oracle/graaljs/commit/934cb7cb826453f687220493bcf08fa386c659aa) with its declared [Graal import](https://github.com/oracle/graal/commit/29054f702c3cd8420cdc927409b7d0f55acddec2) and matching source and tests.

- ✅ **Reproducers:** identical sync/async samples lose their results on baseline and preserve them after the fix; exits `7/7 → 0/0`.
- ✅ **Regression tests:** `JSSimpleTestSuite` reported `OK (487 tests)`, with one ignored; checkstyle passed.
- ✅ **High-power models:** two independent review sessions; 31 + 53 assertions and module probes passed, covering repeated suspension, disposal-error precedence, and exports.

| Evidence | Links |
| --- | --- |
| Reproduce | [Commands](https://github.com/elide-dev/graal/blob/b4fe11971b7cc5a5edc5fd0e7c75aa24dd600388/pipeline/reproducers/using-return/README.md) · [Source ZIP](https://github.com/elide-dev/graal/raw/b4fe11971b7cc5a5edc5fd0e7c75aa24dd600388/pipeline/reproducers/archives/using-return.zip) |
| Fork | [#14](https://github.com/elide-dev/graaljs/pull/14) · [✅ CI](https://github.com/elide-dev/graaljs/actions/runs/42561388254) |
| Reviews | [Review 1](https://github.com/elide-dev/graal/blob/b4fe11971b7cc5a5edc5fd0e7c75aa24dd600388/pipeline/reviews/using-return.json) · [Review 2](https://github.com/elide-dev/graal/blob/b4fe11971b7cc5a5edc5fd0e7c75aa24dd600388/pipeline/reviews/using-return-second.json) |

> Review probes used existing builds. Test262 and compiler-stress runs were not performed.
