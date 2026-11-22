package sa.uet.digitalwallet_system.wallet.api;

public interface WalletQueryApi {
    WalletView getOwnedWallet(long actorUserId);
    WalletView getOwnedWalletById(long actorUserId, long walletId);
}
