package sa.uet.digitalwallet_system.identity.adapter.out.persistence;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import sa.uet.digitalwallet_system.identity.application.port.out.UserAccountStore;
import sa.uet.digitalwallet_system.identity.domain.UserAccount;

import java.util.Optional;

@Component
@RequiredArgsConstructor
class UserAccountPersistenceAdapter implements UserAccountStore {
    private final UserAccountRepository repository;

    @Override public Optional<UserAccount> findByUsername(String username) {
        return repository.findByUsername(username).map(this::toDomain);
    }
    @Override public UserAccount save(UserAccount user) {
        UserAccountJpaEntity entity = new UserAccountJpaEntity(user.id(), user.username(), user.passwordHash(),
                user.fullName(), user.email(), user.role(), user.createdAt());
        return toDomain(repository.save(entity));
    }
    @Override public boolean existsByUsername(String username) { return repository.existsByUsername(username); }
    @Override public boolean existsByEmail(String email) { return repository.existsByEmail(email); }

    private UserAccount toDomain(UserAccountJpaEntity entity) {
        return new UserAccount(entity.getId(), entity.getUsername(), entity.getPasswordHash(), entity.getFullName(),
                entity.getEmail(), entity.getRole(), entity.getCreatedAt());
    }
}
