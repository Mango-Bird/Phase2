package sa.uet.digitalwallet_system.payment.adapter.out.persistence;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import sa.uet.digitalwallet_system.payment.adapter.out.persistence.entity.TransactionJpaEntity;
import sa.uet.digitalwallet_system.payment.adapter.out.persistence.mapper.TransactionEntityMapper;
import sa.uet.digitalwallet_system.payment.adapter.out.persistence.repository.TransactionRepository;
import sa.uet.digitalwallet_system.payment.application.port.out.LoadTransactionPort;
import sa.uet.digitalwallet_system.payment.application.port.out.SaveTransactionPort;
import sa.uet.digitalwallet_system.payment.domain.model.Transaction;

import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class TransactionPersistenceAdapter implements SaveTransactionPort, LoadTransactionPort {

    private final TransactionRepository transactionRepository;
    private final TransactionEntityMapper transactionEntityMapper;

    @Override
    public Transaction save(Transaction transaction) {
        TransactionJpaEntity entity = transactionEntityMapper.toEntity(transaction);
        TransactionJpaEntity saved = transactionRepository.save(entity);
        return transactionEntityMapper.toDomain(saved);
    }

    @Override
    public List<Transaction> loadByWalletId(Long walletId) {
        return transactionRepository.findByWalletId(walletId)
                .stream()
                .map(transactionEntityMapper::toDomain)
                .collect(Collectors.toList());
    }
}
