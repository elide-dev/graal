## Summary

{{Concrete trigger and observable bug, limitation, or measured cost.}}
{{Explain the resulting behavior and smallest relevant implementation change in one or two sentences.}}

**[Elide GraalVM fork dashboard: This PR]({{PER_PR_DASHBOARD_URL}})**

{{OPTIONAL_BEHAVIOR_DIAGRAMS_ONLY_IF_THEY_CLARIFY_THE_CHANGE}}

## Testing

Pretested on the fork at upstream [`{{BASE_SHORT_SHA}}`]({{BASE_COMMIT_URL}}){{RELEVANT_DEPENDENCY_IMPORT}}, with {{VERIFIED_PRODUCTION_SOURCE_EQUIVALENCE}}.

- ✅ **{{Reproducer or benchmark}}:** {{Actual comparable baseline and candidate results; include exits or measured units when useful.}}
- ✅ **Regression tests:** {{Exact relevant suite/result, ignored or conditional cases, and style result.}}
- ✅ **High-power models:** {{Actual independent review-session count, executed checks, findings, and disposition.}}

| Evidence | Links |
| --- | --- |
| Reproduce | [Commands]({{IMMUTABLE_PUBLIC_COMMANDS_URL}}) · [Source ZIP]({{IMMUTABLE_PUBLIC_SOURCE_ZIP_URL}}) |
| Fork | [#{{FORK_PR_NUMBER}}]({{FORK_PR_URL}}) · [✅ CI]({{CI_RUN_URL}}) |
| Reviews | [Review]({{IMMUTABLE_PUBLIC_REVIEW_URL}}){{OPTIONAL_SECOND_REVIEW_OR_RESOLUTION_LINK}} |

> {{Only material limits: conditional tests, existing builds, analogue checks, or untested configurations.}}

<!-- Canonical authoring rules for all upstream PRs, including generated bodies. Remove this comment before posting.
- Keep this canonical template, docs/templates/generated-upstream-pr.md, its renderer, and saved descriptions consistent. After final copy edits to posted PRs, commit the matching templates and descriptions together.
- Inspect the target repository's contribution guidance and recent accepted PR titles/commit subjects. Match both title and commit conventions; prefer concise imperative language naming the behavior. Use an issue prefix only for a real associated issue. Do not invent identifiers or impose Conventional Commits. Adapt headings to the target template; Graal's template uses Summary and Testing.
- Lead with the concrete behavior. Keep coverage in short bullets, generally one sentence each. Tables are compact evidence navigation: short labels and links only, never wrapped explanatory prose. Omit a table when the links are already visible elsewhere.
- Use Mermaid only when it materially clarifies the actual behavior or optimization. Prefer small before/after diagrams with the same concrete input. Verify every transition against code or evidence; distinguish a resolver API from DNS traffic and measurements from expectations. Never diagram verification, review, CI, or publication flow. Omit the diagram placeholder when unnecessary.
- Use the exact dashboard label once, linking this PR's view. Avoid repeated branding. If the view is not verified live, state its status accurately.
- Describe review as “high-power models.” Report actual independent session counts without implying different model families or human review. Preserve exact identities in raw evidence; link original findings and their resolutions.
- Pin public evidence to immutable commits. Linked commands must retain exact revisions, dependency imports, environment, and inputs. Source ZIP contents must match the retained checksum manifest. Never link private repositories or logs.
- Verify the tested fork's affected production files match the clean candidate. Retain exact fork-head, scaffolding differences, platform, and toolchain in linked evidence. Inspect relevant CI artifacts before labeling CI green.
- Use ✅ only for completed, verified checks. Keep ignored tests, assumptions, early returns, existing-build reuse, and analogue checks visible when material. Do not infer coverage from an aggregate suite total or unrelated green workflow.
- For bugs, compare the same sample on baseline and candidate. For optimizations, retain comparable measured results, units, workload, and environment; never invent a measurement or imply performance from functional tests.
- Retain unsuccessful attempts and findings in linked evidence. Do not alter raw provenance to fit shorter copy or imply exhaustive coverage or formal qualification.
- Remove unused placeholders, bullets, and links. Check actual GitHub rendering at desktop and narrow widths; tables should remain easy to scan and Mermaid controls must not obscure labels. Prefer compact vertical diagrams when horizontal nodes become cramped. Keep posted and saved descriptions synchronized.
-->
