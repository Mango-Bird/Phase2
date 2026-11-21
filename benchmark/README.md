# C00-B reproducible baseline

This directory contains the Phase 2 pre-change performance baseline harness and
evidence. It intentionally does not change application behavior.

## Environment

- The application and MySQL run through the root `docker-compose.yml` under the
  isolated Compose project `phase2-c00b`.
- k6 runs in a separate container pinned by image digest and reaches the app
  directly through the Compose bridge network.
- All three containers share one Docker Desktop host. Results are therefore a
  reproducible local baseline, not an isolated capacity claim.
- Each measured run recreates `digitalwallet_db` from the Phase 1 schema. The
  application process stays warm between runs so the JVM/JIT is not restarted.
- Before creating a run directory, the runner fingerprints every file copied by
  the application Dockerfile, builds the SUT with revision/fingerprint labels,
  and verifies that the running container uses that exact image ID. A stale
  container or mismatched label fails without recording a new baseline.

## Commands

From the repository root in PowerShell:

```powershell
docker compose -p phase2-c00b up -d --build --wait

# Warm-up/diagnostic
.\benchmark\scripts\run-c00.ps1 -RunName warmup-network -Profile smoke

# Three required measured runs
.\benchmark\scripts\run-c00.ps1 -RunName load-run-01 -Profile load
.\benchmark\scripts\run-c00.ps1 -RunName load-run-02 -Profile load
.\benchmark\scripts\run-c00.ps1 -RunName load-run-03 -Profile load

# Concentrated contention scenario required by C00-B
.\benchmark\scripts\run-c00.ps1 -RunName hot-wallet-run-01 -Profile hot-wallet
```

The standard load profile is 200 accounts, a 100 RPS target, a two-minute ramp and a
30-minute hold (`durationSec=1920`). The executor is `ramping-vus`, a closed
model; k6 therefore does not emit `dropped_iterations`. Every summary records
that field as not applicable instead of silently treating it as zero.

The hot-wallet profile keeps the same load and duration but reduces
`receivingWallets` from 50 to 1. It is reported separately and is not mixed
into the three-run standard-load median.

## Evidence per run

- `metadata.json`: base SHA, hardware, topology and pinned tool versions.
  New runs also contain `applicationImageId`, `applicationSourceFingerprint`,
  `applicationRevisionLabel`, and `sutProvenanceVerified=true`.
- `k6_summary.json`: native k6 summary including p50/p95/p99.
- `run-summary.json`: normalized RPS, latency, failures, resources and database
  reconciliation.
- `sampler.csv`: host, container and MySQL samples every two seconds.
- `reconciliation.tsv`: actual versus ledger-derived balance for every wallet.
- `app.log` / `mysql.log`: server logs scoped to the run.
- `k6_raw.csv`: full raw stream, retained locally and uploaded as a PR/CI
  artifact. It is intentionally ignored by Git because each file is hundreds
  of megabytes. Its byte size and SHA-256 are committed in `run-summary.json`.

The reconciliation assumes a clean C00 database where every wallet opens at
zero. Expected balance is `topup + transfer-in - withdraw - transfer-out` over
the append-only Phase 1 transaction history. A zero mismatch count is useful
evidence for this workload, not proof against every possible concurrency bug.

The per-run summarizer rejects missing required k6 metrics, malformed or empty
reconciliation data, and wallet coverage other than the profile contract
(`15` smoke, `250` load, `201` hot-wallet). It writes `run-summary.json` before
returning nonzero for completeness, mismatch, negative-balance, or client-side
invariant failures. HTTP 409/500 observations remain performance evidence and
do not by themselves fail a financial gate.

The aggregate command requires all three load summaries and the hot-wallet
summary. It likewise writes its report before returning nonzero when a mandatory
financial/completeness gate fails:

```powershell
python benchmark/scripts/aggregate_runs.py `
  --evidence-dir benchmark/evidence/C00-B-redo `
  --output benchmark/evidence/C00-B-redo/aggregate-summary.json
```

Harness-only regression tests do not execute k6 or change benchmark evidence:

```powershell
python -m unittest discover -s benchmark/tests -p 'test_*.py'
```
