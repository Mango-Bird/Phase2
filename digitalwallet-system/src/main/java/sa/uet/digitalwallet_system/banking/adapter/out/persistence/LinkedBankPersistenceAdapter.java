package sa.uet.digitalwallet_system.banking.adapter.out.persistence;

import lombok.RequiredArgsConstructor;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import sa.uet.digitalwallet_system.platform.api.error.SqlFailures;
import sa.uet.digitalwallet_system.banking.api.error.BankResourceBusyException;
import org.springframework.stereotype.Component;
import sa.uet.digitalwallet_system.banking.adapter.out.persistence.entity.LinkedBankAccountJpaEntity;
import sa.uet.digitalwallet_system.banking.adapter.out.persistence.mapper.LinkedBankAccountMapper;
import sa.uet.digitalwallet_system.banking.adapter.out.persistence.repository.LinkedBankAccountRepository;
import sa.uet.digitalwallet_system.banking.application.port.out.LoadLinkedBankPort;
import sa.uet.digitalwallet_system.banking.application.port.out.SaveLinkedBankPort;
import sa.uet.digitalwallet_system.banking.domain.model.LinkedBankAccount;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class LinkedBankPersistenceAdapter implements LoadLinkedBankPort, SaveLinkedBankPort {

    private final LinkedBankAccountRepository linkedBankAccountRepository;
    private final LinkedBankAccountMapper linkedBankAccountMapper;
    private final EntityManager entityManager;

    @Override
    public List<LinkedBankAccount> loadActiveByWalletId(Long walletId) {
        return linkedBankAccountRepository.findByWalletIdAndStatus(walletId, "ACTIVE")
                .stream()
                .map(linkedBankAccountMapper::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<LinkedBankAccount> lockByIdAndWalletId(Long id, Long walletId) {
        try {
            // Check ownership without locking a foreign link. Refresh obtains a current read + row lock,
            // including when the entity was already present in this persistence context.
            var entity = linkedBankAccountRepository.findByIdAndWalletId(id, walletId).orElse(null);
            if (entity == null) return Optional.empty();
            entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE);
            if (!walletId.equals(entity.getWalletId())) return Optional.empty();
            return Optional.of(linkedBankAccountMapper.toDomain(entity));
        } catch (RuntimeException ex) {
            if (SqlFailures.isMySqlLockTimeout(ex)) throw new BankResourceBusyException(ex);
            throw ex;
        }
    }

    @Override
    public Optional<LinkedBankAccount> loadById(Long id) {
        return linkedBankAccountRepository.findById(id)
                .map(linkedBankAccountMapper::toDomain);
    }

    @Override
    public LinkedBankAccount save(LinkedBankAccount linkedBankAccount) {
        LinkedBankAccountJpaEntity entity = linkedBankAccountMapper.toEntity(linkedBankAccount);
        LinkedBankAccountJpaEntity saved = linkedBankAccountRepository.save(entity);
        return linkedBankAccountMapper.toDomain(saved);
    }
}
