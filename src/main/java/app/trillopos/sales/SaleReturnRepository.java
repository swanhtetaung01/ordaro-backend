package app.trillopos.sales;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Tenant-scoped by {@code @TenantId}. */
public interface SaleReturnRepository extends JpaRepository<SaleReturn, UUID> {

    List<SaleReturn> findAllByOriginalSaleIdOrderByReturnedAtAscIdAsc(UUID originalSaleId);

    Optional<SaleReturn> findByOriginalSaleIdAndIdempotencyKey(UUID originalSaleId, String idempotencyKey);

    List<SaleReturn> findTop100ByOrderByReturnedAtDesc();
}
