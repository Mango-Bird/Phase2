package architecture.fixtures.bankcaller.wallet.application;
import architecture.fixtures.bankcaller.banking.api.BankEligibilityApi;
public class ForbiddenBankCaller { public void invoke(BankEligibilityApi api) { api.requireActiveForPosting(); } }
