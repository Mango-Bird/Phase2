package sa.uet.digitalwallet_system.platform.api.outbox;

import sa.uet.digitalwallet_system.platform.api.events.PaymentCompletedV1;

public interface OutboxAppendPort {
    void append(PaymentCompletedV1 event);
}
