# Independent Adversarial Reviews

These are public, curated summaries of actual `gpt-6-astra` session reviews and selected executable counterexamples.
They are not fixtures or configured pipeline-adapter receipts.
The original structured findings, commands, results and failed attempts remain in the private investigation store, with each original report's SHA-256 recorded in its public summary.
No private conversation or hidden reasoning is published.
The session tool exposes a model alias and task identity, but not an exact backend version, token count or dollar cost; no such values are inferred.

Each initial reviewer inspected the exact patch and specification in a fresh context before reading implementer evidence or rationale.
The two runtime candidates have separate independent reviews using the same available Astra model family.
The LCOV follow-up is a finding-disposition confirmation by its original reviewer.

| Review | Exact clean source head | Result |
| --- | --- | --- |
| [LCOV initial](lcov.json) | `bafce83ab1988342606ef0017dbbea764a41bf31` | One P2 finding: empty explicit URI loses its LCOV record |
| [LCOV confirmation](lcov-confirmation.json) | `3c92f7b36f8968bbb805448b422bde7f769ef080` | Finding resolved after code/test/documentation change and repeated consumer/edge probes |
| [Resource URL first](resource-url.json) | `f62ead1c846d842b3abe64afae338cd4971e8315` | No change-introduced blocker; correction to configuration coverage wording |
| [Resource URL second](resource-url-second.json) | `f62ead1c846d842b3abe64afae338cd4971e8315` | No change-introduced blocker; numeric/IPv6/case/hash/cache probes |
| [JavaScript first](using-return.json) | `639ec40d68f4242166b56b2f30d89e767ac197f6` | 31 adversarial assertions plus module-completion checks passed |
| [JavaScript second](using-return-second.json) | `639ec40d68f4242166b56b2f30d89e767ac197f6` | 53 additional assertions plus module disposal/export/suppression checks passed |

The original LCOV finding remains visible alongside its confirmed resolution.
The resource URL patch targets DNS/address-alias semantics of the host component; it does not redesign module user-info identity or preexisting Unicode case/hash behavior.
Native resource tests include assumption-skipped/conditional configurations, so the aggregate harness count does not prove every configuration.
JVM custom-handler probes are analogues, labeled separately from execution of the actual native binaries.
Fork CI must still establish the exact staging head and expected baseline failure; session approval alone does not turn a failed CI run green.

See [the fork-first handoff](../reproducers/README.md).
