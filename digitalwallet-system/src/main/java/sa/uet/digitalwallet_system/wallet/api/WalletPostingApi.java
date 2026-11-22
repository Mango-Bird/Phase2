package sa.uet.digitalwallet_system.wallet.api;

/** Payment-only API. Every method must participate in its caller's transaction. */
public interface WalletPostingApi {
    PostingWallet loadOwnedForPosting(long actorUserId);
    PostingWallet loadTargetForPosting(String walletNumber);
    void applyBalanceChange(BalanceChange change);
}
