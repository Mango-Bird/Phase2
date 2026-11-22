package sa.uet.digitalwallet_system.banking.api;

/** Payment-only API: holds the bank-link row lock until the caller commits or rolls back. */
public interface BankEligibilityApi {
    EligibleBank requireActiveForPosting(long linkedBankId, long walletId);
}
