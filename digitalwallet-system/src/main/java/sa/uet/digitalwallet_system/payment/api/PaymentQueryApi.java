package sa.uet.digitalwallet_system.payment.api;

public interface PaymentQueryApi {
    PaymentView getOwned(long actorUserId, String referenceCode);
    PaymentSlice history(long actorUserId, HistoryQuery query);
}
