package sa.uet.digitalwallet_system.payment.api;

import java.time.LocalDateTime;

public record PaymentView(Long id, String referenceCode, String type, Long sourceWalletId,
                          Long targetWalletId, Long linkedBankAccountId, Long amount,
                          String description, LocalDateTime createdAt) {}
