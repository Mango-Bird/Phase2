package sa.uet.digitalwallet_system.wallet.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.OptimisticLockingFailureException;
import sa.uet.digitalwallet_system.wallet.api.*;
import sa.uet.digitalwallet_system.wallet.api.error.WalletNotFoundException;
import sa.uet.digitalwallet_system.wallet.application.port.out.*;
import sa.uet.digitalwallet_system.wallet.domain.model.Wallet;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class WalletPostingService implements WalletPostingApi {
    private final LoadWalletPort loadWalletPort;
    private final SaveWalletPort saveWalletPort;
    public PostingWallet loadOwnedForPosting(long actorUserId) {
        return snapshot(loadWalletPort.loadByUserId(actorUserId).orElseThrow(WalletNotFoundException::new));
    }
    public PostingWallet loadTargetForPosting(String walletNumber) {
        return snapshot(loadWalletPort.loadByWalletNumber(walletNumber)
                .orElseThrow(() -> new WalletNotFoundException("Target wallet not found: " + walletNumber)));
    }
    public void applyBalanceChange(BalanceChange change) {
        Wallet wallet = loadWalletPort.loadById(change.walletId()).orElseThrow(WalletNotFoundException::new);
        if (wallet.getVersion() != change.expectedVersion() || wallet.getBalance() != change.expectedBalance()) {
            throw new OptimisticLockingFailureException("Wallet snapshot changed");
        }
        saveWalletPort.save(wallet.withBalance(change.newBalance()).withUpdatedAt(LocalDateTime.now()));
    }
    private PostingWallet snapshot(Wallet wallet) {
        return new PostingWallet(wallet.getId(), wallet.getUserId(), wallet.getWalletNumber(),
                wallet.getBalance(), wallet.getStatus(), wallet.getVersion());
    }
}
