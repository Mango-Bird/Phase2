package sa.uet.digitalwallet_system.payment.application.port.in;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Value;

@Value
public class WithdrawCommand {
    @NotNull
    Long actorUserId;

    @NotNull
    Long walletId;

    @NotNull
    Long linkedBankAccountId;

    @NotNull @Min(1)
    Long amount;

    String description;
}
