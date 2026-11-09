{{Describe the concrete trigger and incorrect observable result in one or two sentences. Explain the resulting behavior and the smallest relevant implementation change. Name any added regressions or documentation.}}

**[Validation dashboard · graalvm.elide.dev](https://graalvm.elide.dev)** — reproducible baseline/fix comparisons, validation status, and adversarial review evidence.

### Validation at the upstream revision

We pretested this change on [our fork, PR #{{FORK_PR_NUMBER}}]({{FORK_PR_URL}}), starting from upstream `{{UPSTREAM_BASE_SHA}}`{{OPTIONAL_DEPENDENCY_IMPORT_SENTENCE}}. The clean candidate is `{{CANDIDATE_SHA}}`; the fork validation head is `{{FORK_HEAD_SHA}}`, with {{FORK_ONLY_SUPPORT_DESCRIPTION}}. {{State how the tested production diff matches the upstream candidate.}}

| Evidence | Result | Inspect |
| --- | --- | --- |
| Identical reproducer on baseline and fix | {{Observed baseline failure and candidate success, including exit codes where useful}} | [Source and commands](https://github.com/elide-dev/graal/tree/{{EVIDENCE_COMMIT}}/pipeline/reproducers/{{CANDIDATE_KEY}}) |
| Relevant regression suite and style | {{Exact suite, result, ignored/skipped/conditional cases, and style result; label local vs CI}} | [Validation evidence]({{VALIDATION_EVIDENCE_URL}}) |
| Fork CI | {{Current status; say whether artifacts were inspected and identify the verified results}} | [Run {{CI_RUN_ID}}]({{CI_RUN_URL}}) |
| Independent adversarial review | {{Actual reviewer/model, number of separate reviews, executable challenges, findings and disposition}} | [Review summary](https://github.com/elide-dev/graal/blob/{{EVIDENCE_COMMIT}}/pipeline/reviews/{{REVIEW_FILE}}){{OPTIONAL_FOLLOWUP_REVIEW_LINK}} |

{{Explain the strongest additional validation in a short paragraph: meaningful negative controls, consumer tests, or semantic edge cases. If a review found a defect, retain the finding and link its resolution. Distinguish independent review sessions from independence of model family.}}

{{State the tested platform/toolchain and material limits. Distinguish fresh builds from existing artifacts, direct execution from supplied logs or analogues, and actual coverage from untested configurations. Keep this specific to the change.}}

<!-- Authoring checklist; remove before posting:
- Replace every placeholder with verified information. Delete inapplicable rows rather than implying a check ran.
- Lead with the upstream bug and resulting behavior, not project promotion.
- Pin public evidence URLs to a full immutable commit. The evidence repository must be public; never link the private dashboard source repository or private logs.
- Do not call CI validated solely because the run is green: inspect the relevant artifacts and identify skipped, ignored, conditional, or missing checks.
- Use "pretested" only for execution already performed. If CI is running, label it pending and distinguish completed local evidence.
- Give the exact upstream baseline and relevant dependency imports. State which fork-only files differ and how production-source equivalence was checked.
- Describe only reviews actually conducted. An independent session review is not automatically a configured pipeline receipt or cross-model review.
- Preserve original findings and correction history. Do not claim exhaustive platform/configuration coverage, universal identity, zero risk, or formal pipeline readiness.
- Once filed, record {{UPSTREAM_URL}}, the fork PR URL, candidate/base SHAs, and CI/evidence links on the dashboard. Do not describe that dashboard update as complete until verified.
-->
