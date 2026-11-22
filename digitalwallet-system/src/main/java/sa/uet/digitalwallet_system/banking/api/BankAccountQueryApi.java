package sa.uet.digitalwallet_system.banking.api;

public interface BankAccountQueryApi {
    void requireActiveAccount(long linkedBankId, long walletId);
}
