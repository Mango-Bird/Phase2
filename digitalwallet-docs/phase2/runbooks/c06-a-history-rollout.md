# C06-A — history timestamp compatibility and rollout

## Decision

Keep the pre-C06-A `TransactionJpaEntity.createdAt` mapping, `hibernate.jdbc.time_zone=UTC`, and deployment JVM timezone configuration unchanged. No schema/data migration or automatic timestamp shift is included. The direct `LOCAL_DATE_TIME` mapping proposed in the initial candidate is removed.

A DATETIME column does not record the JVM timezone or mapping that wrote each row. Under the previous mapping on UTC+7, a business timestamp `2026-11-20T10:15:30.123` can be stored as literal `03:15:30.123`; the previous reader on that same timezone returns `10:15:30.123`. Reading that row literally after an annotation change would silently move GET history seven hours relative to the saved receipt. C06-A preserves that existing public behavior.

New event `occurredAt` is the posting's UTC business instant, also used by its stored receipt. It is not reconstructed from the ambiguous physical history DATETIME. History retains its previous read/write mapping. No event is backfilled for a payment committed before C06-A, including when replaying its receipt.

## Upgrade verification

`PaymentHistoryUpgradeIntegrationTest` launches a separate JVM with `-Duser.timezone=Asia/Bangkok`. Its frozen legacy entity follows the mapping at base `250d688`; actual Hibernate writes/reads the fixture and saves its receipt, with no outbox. The upgraded HTTP app also runs on UTC+7. The test checks:

1. Old mapper returns 10:15 while the physical row is 03:15, independently proving the old encoding.
2. Upgraded GET history still returns 10:15 and agrees with the original receipt.
3. Replaying the original key returns exactly the stored receipt without creating an outbox event or another money effect.
4. A new payment adds exactly one event with the receipt's business instant; old row and stored receipt remain unchanged.

## Rollout

- Deploy the compatibility revision of PR #14, not initial candidate `ba514b8` containing the direct mapping. Keep each deployment's existing JVM/JDBC timezone settings during this rollout; rolling application upgrade must not also change timezone semantics.
- Retain V1–V3 and existing history/receipt/outbox rows. Apply no `+7 hours` or other blanket correction: rows may have different writer provenance, and saved receipts are immutable financial evidence.
- If a direct-mapping candidate has already written real data, pause that rollout and preserve a backup plus the relevant deployment interval, writer timezone and affected transaction IDs. Audit row provenance against receipts before deciding a separately reviewed migration. C06-A cannot infer it reliably and does not silently repair it.
- Any future normalization needs its own versioned migration/reader strategy and evidence for UTC, UTC+7 and mixed-provenance rows; coordinate reader cutover with historical receipt compatibility. It is not an implicit step of outbox deployment.
- Application rollback keeps V3 and committed outbox rows. Do not delete outbox or regenerate payment receipts. Pending events remain available for C07-B worker deployment.

This decision preserves existing timestamp semantics; it does not claim to repair the pre-existing ambiguity for mixed-timezone history readers.
