package sa.uet.digitalwallet_system.payment.adapter.out.persistence.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import sa.uet.digitalwallet_system.payment.adapter.out.persistence.entity.TransactionJpaEntity;

import java.util.List;

public interface TransactionRepository extends JpaRepository<TransactionJpaEntity, Long> {

    @Query("SELECT t FROM TransactionJpaEntity t WHERE t.referenceCode=:reference AND (t.sourceWalletId=:walletId OR t.targetWalletId=:walletId)")
    java.util.Optional<TransactionJpaEntity> findOwnedReference(@Param("walletId") long walletId,@Param("reference") String reference);

    @Query("""
            SELECT t FROM TransactionJpaEntity t
            WHERE t.sourceWalletId = :walletId OR t.targetWalletId = :walletId
            ORDER BY t.createdAt DESC
            """)
    List<TransactionJpaEntity> findByWalletId(@Param("walletId") Long walletId);
}
