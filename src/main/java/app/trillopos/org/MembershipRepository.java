package app.trillopos.org;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Tenant-scoped by {@code @TenantId}. The cross-organization reads live in {@code MembershipDirectory}. */
public interface MembershipRepository extends JpaRepository<Membership, UUID> {

    List<Membership> findAllByOrderByCreatedAtAsc();

    boolean existsByAccountId(UUID accountId);
}
