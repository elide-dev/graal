# Fork-First Bug Validation

The first cohort is filed on the Elide forks before any upstream PR:

| Candidate | Fork draft | Clean upstream source head | Local validation |
| --- | --- | --- | --- |
| LCOV source identity | [Graal #225](https://github.com/elide-dev/graal/pull/225) | `3c92f7b36f8968bbb805448b422bde7f769ef080` | Same source sample fails baseline and passes fix; 9 regression tests and checkstyle pass |
| Resource URL host identity | [Graal #226](https://github.com/elide-dev/graal/pull/226) | `f62ead1c846d842b3abe64afae338cd4971e8315` | Same class fails baseline and passes fix; new native identity regression runs; harness reports 24 tests with conditional coverage; checkstyle passes |
| JavaScript using completion | [GraalJS #14](https://github.com/elide-dev/graaljs/pull/14) | `639ec40d68f4242166b56b2f30d89e767ac197f6` | Sync/async samples fail baseline and pass fix; 487 regression tests and checkstyle pass |

All six authorized Astra review calls have completed; [findings, resolutions and limitations](../reviews/README.md) are recorded separately from test results.
LCOV is filed as [oracle/graal #14947](https://github.com/oracle/graal/pull/14947). All three fork jobs are green, and their baseline failures, candidate passes, regression results and style logs have been inspected.

## Small Reproducer Packages

Each source-only ZIP is reproducibly generated from the adjacent sample, copyable instructions and, for LCOV, the standalone regression patch.
The archives contain no executables, credentials or host-specific bundle metadata.
[The package manifest](archives/manifest.json) records both archive and individual file SHA-256 values.
These are manual reviewer reproducers, not nib/OCI qualification artifacts accepted by the automatic pipeline gate.
The user's follow-up explicitly permits small code samples and ZIPs for this fork-first workflow.

- [LCOV source ZIP](archives/lcov.zip) and [copyable instructions](lcov/README.md).
- [Resource URL source ZIP](archives/resource-url.zip) and [native build instructions](resource-url/README.md).
- [JavaScript source ZIP](archives/using-return.zip) and [copyable instructions](using-return/README.md).

A native resource URL bundle was also built locally and its corresponding executable passed.
Its input/class file is the same one used for the failing baseline.
It is not published: that generated bundle includes an executable and host-specific build paths, while the small source sample is sufficient to reproduce the defect.
Bundle creation/replay commands remain available in the resource URL instructions.

## Exact Fork Comparison

Each fork base contains the actual upstream base plus a fork-only validation workflow and reproducer harness.
The LCOV PR diff contains the proposed source/test/documentation change. The remaining fork PRs also include a fork-only scratch-isolation harness correction; their affected production/test paths still match the clean upstream branches.
Affected source paths are byte-identical to the separately pushed clean upstream candidate.
The fork-only CI scaffold will not be part of the upstream PR.
The job checks out exact source identities, uses the same sample bytes on baseline and fix, requires the expected baseline diagnostic, then runs the candidate regression/style checks.
The GraalJS job checks out the exact declared Graal import.

CI uses the existing `self-hosted, linux-amd64-cipool` pool, caps each job at 180 minutes, and limits mx/native-image CPU concurrency to eight.
No new runner infrastructure is deployed.
Artifacts retain exact identities, observed environment, reproducer hashes, actual exit codes and logs for 14 days; they are not permanent public evidence storage.
Downloaded artifacts and all failed history are retained in the local private investigation store.

## Initial CI Failure And Prepared Correction

The first three authorized runs failed before compilation:

- [LCOV run 42559696812](https://github.com/elide-dev/graal/actions/runs/42559696812).
- [Resource URL run 42559701040](https://github.com/elide-dev/graal/actions/runs/42559701040).
- [JavaScript run 42559705944](https://github.com/elide-dev/graaljs/actions/runs/42559705944).

All three baseline build logs report `mx: error: unrecognized arguments: --cpu-count=8`.
This was an operator error in the shared CI harness, not an observed test failure in a proposed fix.
The corrected option is `--max-cpus=8`, verified locally using the exact pinned mx revision `ef054fc6eca1d516820bd040efa3c0a0f6ef1899` (7.88.0).
The user authorized three replacement runs after the failure was diagnosed and the corrected command was verified locally.
LCOV replacement run 42560305112 is green. The two runtime replacements stopped before compilation on a stale temporary baseline path; their logs are retained. A unique mktemp workspace correction passed repeated-launch checks locally. The user approved two further runs, resource URL 42561385244 and JavaScript 42561388254. Both jobs are green; retained artifacts confirm exact sample bytes, expected baseline failures, candidate success, regression results and style success. An accidental duplicate JavaScript run 42560305467 was canceled during setup before comparison. No additional runs or infrastructure are authorized.
The clean reviewed source trees are unchanged by this harness correction.
No automatic retry or weaker test requirement is used to bypass the failure.

After approved replacement runs are green on the current heads, refresh upstream refs, verify exact source equivalence, open the corresponding upstream PRs, and add reciprocal fork/upstream links.
The automatic pipeline's provider adapter, public nib/OCI publication and online readiness receipt are separate uncompleted milestones; no flags or synthetic receipts mark them complete.
