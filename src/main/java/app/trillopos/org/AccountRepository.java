package app.trillopos.org;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    Optional<Account> findByPhone(String phone);

    boolean existsByPhone(String phone);
}
