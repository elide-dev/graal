# GraalVM Value Candidates

Manual fork-first evidence, separate from automatic qualification.

Ristretto: https://github.com/oracle/graal/pull/14966. Native G1/JFR: https://github.com/oracle/graal/pull/14967. Both have inspected green exact-source fork CI.

NFI and FFM remain fork drafts held for product shipment and measurements. FFM CI attempt 8 is unqualified: every test skipped despite workflow success. Corrected scaffolding awaits a new CI allowance. Local executed graph and isolate evidence is retained separately.

See pipeline/validation/attempts.md, batch-budget.json, value-four.json, reviews, and the checksummed reproducer ZIPs.
