package sa.uet.digitalwallet_system.banking.adapter.out.persistence.mapper;

import org.mapstruct.Mapper;
import sa.uet.digitalwallet_system.banking.adapter.out.persistence.entity.LinkedBankAccountJpaEntity;
import sa.uet.digitalwallet_system.banking.domain.model.LinkedBankAccount;

@Mapper(componentModel = "spring")
public interface LinkedBankAccountMapper {
    LinkedBankAccount toDomain(LinkedBankAccountJpaEntity entity);
    LinkedBankAccountJpaEntity toEntity(LinkedBankAccount domain);
}
