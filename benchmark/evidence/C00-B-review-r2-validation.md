# C00-B PR #6 review r2 validation

Date: 2026-11-21 (Asia/Saigon)

This validation addresses review comments `discussion_r4651771702` and
`discussion_r4651771708`. It did not execute k6, rerun the 32-minute benchmark,
or modify any historical r1 evidence/raw CSV.

## Regression and backend tests

- Harness regression: 18 tests, 0 failures.
- Round-trip regression: a real `summarize_run.py` FAIL report is used as one
  of three load summaries; `aggregate_runs.py` propagates the per-run errors,
  writes its aggregate report, and exits nonzero.
- Backend build: MySQL 8.4 isolated database, 111 tests, 0 failures/errors/
  skipped, `BUILD SUCCESSFUL`.
- Python compilation and `git diff --check`: PASS.

## Read-only validation of retained raw evidence

The r2 validator scanned each retained primary raw CSV and compared it with its
existing native `k6_summary.json`. No summary or CSV was rewritten.

| Run | Valid `request_outcome` samples | Failure samples | Invariant samples | Malformed/invalid rows | Counter consistency |
|---|---:|---:|---:|---:|---|
| load-run-01 | 179,151 | 374 | 0 | 0 / 0 | PASS |
| load-run-02 | 175,236 | 422 | 0 | 0 / 0 | PASS |
| load-run-03 | 178,763 | 413 | 0 | 0 / 0 | PASS |
| hot-wallet-run-01 | 176,906 | 14,075 | 0 | 0 / 0 | PASS |

Consistency checks require the raw `request_outcome` total and its
success/failure split to equal `request_outcome.count` and
`unexpected_failure.fails/passes`; failure samples must also equal
`checks.fails`. The raw invariant count is compared with
`invariant_violation.count` (missing is treated as zero only when raw is zero).

HTTP 409/500/deadlock observations remain unchanged as performance evidence and
are not converted into financial failures. Financial reconciliation, negative
balance, and client-observed invariant gates remain mandatory.

## Summary compatibility policy

- r1 summaries containing neither `gate` nor `completenessErrors` are evaluated
  by the existing legacy required-field, coverage, duration, exit-code, and
  financial checks.
- If either field is present, the summary is treated as r2 and must contain a
  true `gate.mandatoryPassed`, passing nonempty `gate.financial` and
  `gate.completeness` objects, and an empty `completenessErrors` list.
- An explicit per-run FAIL is propagated into aggregate `completenessErrors`
  and makes the aggregate exit nonzero. Historical r1 summaries are not
  rewritten to add a gate.
