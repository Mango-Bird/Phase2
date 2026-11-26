package sa.uet.digitalwallet_system.payment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.NullSource;
import sa.uet.digitalwallet_system.payment.application.service.TransactionService;
import sa.uet.digitalwallet_system.payment.application.port.in.*;
import sa.uet.digitalwallet_system.payment.application.port.out.*;
import sa.uet.digitalwallet_system.payment.api.error.PaymentFailure;
import sa.uet.digitalwallet_system.wallet.api.*;
import sa.uet.digitalwallet_system.wallet.api.error.WalletNotFoundException;
import sa.uet.digitalwallet_system.banking.api.BankEligibilityApi;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TransactionServiceInvariantTest {
    final WalletPostingApi wallets=mock(WalletPostingApi.class);
    final BankEligibilityApi banks=mock(BankEligibilityApi.class);
    final SaveTransactionPort save=mock(SaveTransactionPort.class);
    final LoadTransactionPort history=mock(LoadTransactionPort.class);
    final sa.uet.digitalwallet_system.payment.application.PaymentEventPublisher events=mock(sa.uet.digitalwallet_system.payment.application.PaymentEventPublisher.class);
    final TransactionService service=new TransactionService(wallets,banks,save,history,events);
    @ParameterizedTest @NullSource @ValueSource(longs={0,-1,Long.MIN_VALUE})
    void directUseCasesValidateAmountEvenWithoutHttpOrFacade(Long amount) {
        assertThatThrownBy(() -> service.topup(new TopupCommand(101L,11L,31L,amount,null))).isInstanceOf(PaymentFailure.class);
        assertThatThrownBy(() -> service.withdraw(new WithdrawCommand(101L,11L,31L,amount,null))).isInstanceOf(PaymentFailure.class);
        assertThatThrownBy(() -> service.transfer(new TransferCommand(101L,"TARGET",amount,null))).isInstanceOf(PaymentFailure.class);
        verifyNoInteractions(wallets,banks,save,history);
    }
    @Test void changedSourceOwnershipCannotUseAStaleResolvedWalletId() {
        when(wallets.loadOwnedForPosting(101)).thenReturn(new PostingWallet(22,101,"NEW",1000,"ACTIVE",0));
        assertThatThrownBy(() -> service.topup(new TopupCommand(101L,11L,31L,100L,null))).isInstanceOf(WalletNotFoundException.class);
        verify(wallets,never()).applyBalanceChange(any()); verifyNoInteractions(save);
    }
    @Test void receiverOverflowIsDetectedBeforeSourceDebit() {
        when(wallets.loadOwnedForPosting(101)).thenReturn(new PostingWallet(11,101,"SOURCE",1000,"ACTIVE",0));
        when(wallets.loadTargetForPosting("TARGET")).thenReturn(new PostingWallet(22,202,"TARGET",Long.MAX_VALUE,"ACTIVE",0));
        assertThatThrownBy(() -> service.transfer(new TransferCommand(101L,"TARGET",1L,null))).isInstanceOfSatisfying(PaymentFailure.class,
                error -> assertThat(error.code()).isEqualTo("MONEY_OVERFLOW"));
        verify(wallets,never()).applyBalanceChange(any()); verifyNoInteractions(save);
    }
}
