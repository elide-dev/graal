## Summary

Pathless sources currently share `SF:null` in LCOV output.
Use their URI instead, with the existing content/name-derived fallback for an explicitly empty URI.
File-backed paths retain precedence; tests and documentation cover the fallback.

**[Elide GraalVM fork dashboard: This PR](https://graalvm.elide.dev/contributions/contribution-first-lcov)**

## Testing

Pretested on the fork at upstream [`a369308fb9f4`](https://github.com/oracle/graal/commit/a369308fb9f4fbed7746caf33c87eb8f3a61b613), with matching source and tests.

- ✅ **Reproducer:** identical Java source gives two `SF:null` records on baseline and distinct URIs after the fix; exit `1 → 0`.
- ✅ **Regression tests:** all 9 `CoverageTest` tests and checkstyle passed.
- ✅ **High-power models:** one review plus confirmation; the empty-URI finding was fixed, and LCOV consumer/content probes passed.

| Evidence | Links |
| --- | --- |
| Reproduce | [Commands](https://github.com/elide-dev/graal/blob/b4fe11971b7cc5a5edc5fd0e7c75aa24dd600388/pipeline/reproducers/lcov/README.md) · [Source ZIP](https://github.com/elide-dev/graal/raw/b4fe11971b7cc5a5edc5fd0e7c75aa24dd600388/pipeline/reproducers/archives/lcov.zip) |
| Fork | [#225](https://github.com/elide-dev/graal/pull/225) · [✅ CI](https://github.com/elide-dev/graal/actions/runs/42560305112) |
| Reviews | [Finding](https://github.com/elide-dev/graal/blob/b4fe11971b7cc5a5edc5fd0e7c75aa24dd600388/pipeline/reviews/lcov.json) · [Resolution](https://github.com/elide-dev/graal/blob/b4fe11971b7cc5a5edc5fd0e7c75aa24dd600388/pipeline/reviews/lcov-confirmation.json) |

> LCOV checks ignored existing format warnings. URIs do not locate `genhtml` source text; shared explicit URIs can still merge records. Review probes used existing builds.
