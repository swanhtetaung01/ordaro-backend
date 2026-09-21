package app.ordaro.org;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Tenant-scoped by {@code @TenantId}: every method sees the current organization only. */
public interface LocationRepository extends JpaRepository<Location, UUID> {

    List<Location> findAllByOrderByCodeAsc();

    boolean existsByCode(String code);
}
