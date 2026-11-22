package sa.uet.digitalwallet_system.identity.application.port.out;

import sa.uet.digitalwallet_system.identity.domain.UserAccount;

import java.util.Optional;

public interface UserAccountStore {
    Optional<UserAccount> findByUsername(String username);
    UserAccount save(UserAccount user);
    boolean existsByUsername(String username);
    boolean existsByEmail(String email);
}
