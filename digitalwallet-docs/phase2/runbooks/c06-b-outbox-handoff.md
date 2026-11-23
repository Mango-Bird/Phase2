# C06-B handoff — C06-A publisher and C07-B worker

## Appending from C06-A

Inject `OutboxAppendPort` into the Payment transaction executor and call `append(PaymentCompletedV1)` only for the newly executed successful payment, after stable transaction/wallet/user snapshots exist and before commit. The port uses propagation `MANDATORY`: calling it outside an existing transaction fails. It must remain in the same transaction as money, history and idempotency completion; do not catch an append failure and commit payment, and do not add `REQUIRES_NEW`.

Idempotency replay must return the stored response without appending. The DB unique key `(PAYMENT, transactionId, payment.completed.v1)` is a final defense against a publisher bug, not a replacement for that branch logic.

## Payload v1

Use `PaymentCompletedV1.create(...)`. `eventId` and `referenceCode` are separate UUIDs; `occurredAt` is the payment business instant in UTC. TOPUP has only target wallet/user, WITHDRAW only source, and TRANSFER both. Currency is fixed to VND. Recipient IDs come from verified wallet snapshots, never the request body.

## C07-B claim/lease semantics

The schema reserves worker metadata but C06-B provides no worker SQL:

1. A short claim transaction selects eligible PENDING rows using `(status,next_attempt_at,created_at)`, changes them to CLAIMED, increments attempts and writes `lease_until`, a new UUID `claim_token`, and `claim_owner`.
2. A reclaimer uses `(status,lease_until)`. An expired claim is either made eligible again or moved DEAD when all five attempts are consumed.
3. Processing must lock/re-read the row and verify the current token. Only that token may insert inbox and mark DONE/failure. An old worker cannot overwrite a reclaimed event.
4. DONE/DEAD clear claim fields and set `processed_at`; DEAD requires bounded `last_error`.
5. Provider/handler work must not hold the claim transaction open. The later inbox insert and DONE update share their own short processing transaction as specified by ADR-004.

The default batch=50, lease=30 seconds, poll=1 second and retry delays/jitter belong to C07-B implementation. Schema limits attempts to five but does not implement scheduling or replay.

