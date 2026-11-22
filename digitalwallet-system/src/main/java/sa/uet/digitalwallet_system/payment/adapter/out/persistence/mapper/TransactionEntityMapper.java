package sa.uet.digitalwallet_system.payment.adapter.out.persistence.mapper;

import org.mapstruct.Mapper;
import sa.uet.digitalwallet_system.payment.adapter.out.persistence.entity.TransactionJpaEntity;
import sa.uet.digitalwallet_system.payment.domain.model.Transaction;

@Mapper(componentModel = "spring")
public interface TransactionEntityMapper {
    Transaction toDomain(TransactionJpaEntity entity);
    TransactionJpaEntity toEntity(Transaction domain);
}
