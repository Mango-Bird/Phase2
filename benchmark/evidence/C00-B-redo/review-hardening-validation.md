# C00-B r2 review-hardening validation

Date: 2026-11-21 (Asia/Saigon)

This is append-only harness validation. It does not replace, regenerate, or
modify any r1 benchmark result or raw CSV.

## Review cases

- stale built/running image ID: rejected
- stale source fingerprint label: rejected
- matching revision, fingerprint, and image ID: accepted
- header-only reconciliation: report written, nonzero exit
- missing wallet coverage: report written, nonzero exit
- missing required k6 metric: report written, nonzero exit
- wallet mismatch: report written, nonzero exit
- negative balance: report written, nonzero exit
- client-observed invariant violation: report written, nonzero exit
- missing hot-wallet summary: aggregate report written, nonzero exit
- complete evidence with recorded HTTP 409/500: PASS

Command:

```text
python -m unittest discover -s benchmark/tests -p 'test_*.py' -v
```

Local result: 12 tests, PASS. No benchmark workload was executed.

Backend CI-equivalent local result: Gradle 9.8.1 / JDK 21 / isolated MySQL
8.4, 111 tests, 0 failures, 0 errors, 0 skipped, `BUILD SUCCESSFUL`.

The historical r1 application image remains locally inspectable as
`sha256:86351d70adb7066db6e337d8d8b280400703093bd8720ac0217db4ae75a7dd71`.
It predates the r2 provenance labels. A harness-only Docker validation build was
created with source fingerprint
`6fb12b013e6c6365b3b595896d80ea27a4b027710f80b1e1d005b964d90c50db`;
the provenance verifier accepted its revision, fingerprint, and image ID. The
SHA-256 of `/app/app.jar` in that build and the historical r1 image was identical:
`bcf8d6792a92f401e62f795094f4c6070342b2691b771befb6f2564fe3106be4`.

This binary comparison does not invent labels that were absent from the r1
image. The r2 runner enforces exact labels and image identity before any future
run directory is created.
