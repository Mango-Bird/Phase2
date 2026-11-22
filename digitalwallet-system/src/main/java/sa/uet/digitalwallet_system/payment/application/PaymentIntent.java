package sa.uet.digitalwallet_system.payment.application;

import sa.uet.digitalwallet_system.payment.api.PaymentOperation;

public record PaymentIntent(long actorId, PaymentOperation operation, long walletId,
                            Long linkedBankId, String targetWalletNumber, long amount, String description) {}
