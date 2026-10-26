# Live Sonar import check — 2026-10-26

The dedicated [TeaCoCo coverage pilot](https://sonar.instanto.io/dashboard?id=tea-coco-jacoco-pilot)
was analyzed at TeaCoCo commit `792f1154c3b4e682d80466e9e9f3117b9395d968` by
[workflow 40305984423](https://github.com/instanto-io/tea-coco/actions/runs/40305984423).
The scanner imported the generated standard JaCoCo XML and submitted analysis
successfully. Its CE task was `73187d8d-4c95-4bc1-b5d2-f0963fb17355`.

The authenticated Sonar UI was checked directly after analysis:

| Measure | Generated report | Sonar |
| --- | ---: | ---: |
| Lines to cover | 6 | 6 |
| Uncovered lines | 1 | 1 |
| Line coverage | 5 / 6 | 83.3% |
| Conditions to cover | 8 | 8 |
| Uncovered conditions | 2 | 2 |
| Condition coverage | 6 / 8 | 75.0% |
| Combined coverage | (5 + 6) / (6 + 8) | 78.6% |
| Quality gate | — | Passed |

This verifies that TeaCoCo's original-bytecode browser report participates in
Sonar's normal line/condition coverage calculation. The first analysis has no
new-code comparison baseline: the passed gate is **not** evidence that a new-code
coverage threshold was exercised. No thresholds, exclusions or gates were changed.
The pilot intentionally leaves one method with two branch outcomes untested.

The workflow's final automated readback step failed with HTTP 403 on `/api/ce/task`:
the existing analysis credential can submit an analysis but did not authorize this
API read. `SONAR_READ_TOKEN` was not configured. This is a readback access failure,
not a failed import or a passing automated readback. The exact counters above were
verified through the logged-in UI, not inferred from scanner submission success.

Manual browser verification is the current default, as requested. The workflow
links the dashboard in its job summary and does not claim automated counter or
gate verification. Its optional `verify_readback` input enables the API assertions
when read access becomes available. Existing failures are not rewritten as passes.

For repeatable automated checks, make a Sonar **user** token with the required
project read access available as `SONAR_READ_TOKEN` to the repository; keep the
analysis credential separate. Do not copy tokens into source or logs. See
[Sonar's token documentation](https://docs.sonarsource.com/sonarqube-community-build/user-guide/managing-tokens)
for user versus analysis tokens. Then run `sonar-pilot.yml` with `verify_readback` enabled.
`scripts/check_sonar_import.py` waits for processing, compares all four integer
counters and the combined percentage, and records the gate status. A counter
mismatch or failed gate remains a failed check.
