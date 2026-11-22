package sa.uet.digitalwallet_system.payment.api;

public interface PaymentCommandApi {
    PaymentReceipt topup(long actorUserId, String key, TopupInput input);
    PaymentReceipt withdraw(long actorUserId, String key, WithdrawInput input);
    PaymentReceipt transfer(long actorUserId, String key, TransferInput input);
}
