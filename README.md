# First Upstream Bug Contributions

**[Elide GraalVM Fork Dashboard](https://graalvm.elide.dev)** · [Source reproducers and instructions](pipeline/reproducers/README.md) · [Adversarial review findings and resolutions](pipeline/reviews/README.md)

This scoped public evidence accompanies three fixes extracted onto exact Oracle upstream revisions and tested on Elide fork PRs before upstream submission. It includes small source ZIPs with checksums, copyable commands, regression patches where needed, actual Astra review summaries, and reviewer counterexamples. No private conversations or private repository history are included.

| Fix | Upstream base | Fork PR | CI | Upstream PR |
| --- | --- | --- | --- | --- |
| LCOV pathless source identifiers | `a369308fb9f4fbed7746caf33c87eb8f3a61b613` | [Graal #225](https://github.com/elide-dev/graal/pull/225) | [Passed](https://github.com/elide-dev/graal/actions/runs/42560305112) | [Graal #14947](https://github.com/oracle/graal/pull/14947) |
| Resource URL host identity | `a369308fb9f4fbed7746caf33c87eb8f3a61b613` | [Graal #226](https://github.com/elide-dev/graal/pull/226) | [Passed](https://github.com/elide-dev/graal/actions/runs/42561385244) | Pending upstream creation |
| JavaScript using completion | `934cb7cb826453f687220493bcf08fa386c659aa` | [GraalJS #14](https://github.com/elide-dev/graaljs/pull/14) | [Passed](https://github.com/elide-dev/graaljs/actions/runs/42561388254) | Pending upstream creation |

The evidence distinguishes local and CI execution, session review and automatic pipeline qualification, conditional native test coverage, earlier harness failures, and the LCOV finding that was corrected before submission. Exact source identities and review scope are recorded in the linked documents. A green fork job is evidence for its tested tree and environment, not a guarantee across all supported platforms.

[Reusable upstream PR description template](templates/upstream-pr.md), authored by Astra for this cohort.
