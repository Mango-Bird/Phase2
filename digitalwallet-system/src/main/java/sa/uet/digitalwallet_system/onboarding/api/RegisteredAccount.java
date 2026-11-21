package sa.uet.digitalwallet_system.onboarding.api;

import sa.uet.digitalwallet_system.identity.api.UserView;
import sa.uet.digitalwallet_system.wallet.api.WalletView;

public record RegisteredAccount(UserView user, WalletView wallet) {
}
