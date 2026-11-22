package architecture.fixtures.postingcaller.banking.application;

import architecture.fixtures.postingcaller.wallet.api.WalletPostingApi;

public class ForbiddenPostingCaller {
    public void write(WalletPostingApi api) { api.applyBalanceChange(); }
}
