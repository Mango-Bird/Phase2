package sa.uet.digitalwallet_system.banking.application.port.in;

public interface UnlinkBankUseCase {
    void unlink(Long linkedBankId, Long walletId);
}
