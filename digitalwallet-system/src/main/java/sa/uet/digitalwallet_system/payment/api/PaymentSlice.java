package sa.uet.digitalwallet_system.payment.api;

import java.util.List;

public record PaymentSlice(List<PaymentView> items, String nextCursor, boolean hasMore) {
    public PaymentSlice { items=List.copyOf(items); }
}
