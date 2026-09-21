package app.ordaro.sales;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Tenant-scoped by {@code @TenantId}. */
public interface SaleRepository extends JpaRepository<Sale, UUID> {

    Optional<Sale> findByLocationIdAndIdempotencyKey(UUID locationId, String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Sale s where s.id = :id")
    Optional<Sale> lockById(@Param("id") UUID id);

    List<Sale> findTop100ByOrderByCreatedAtDesc();

    List<Sale> findAllByCashierShiftIdOrderByCreatedAtAsc(UUID cashierShiftId);
}
