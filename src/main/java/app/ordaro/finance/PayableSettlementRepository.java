package app.ordaro.finance;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Tenant-scoped by {@code @TenantId}. */
public interface PayableSettlementRepository extends JpaRepository<PayableSettlement, UUID> {

    List<PayableSettlement> findAllByPayableIdOrderByPaidAtAscIdAsc(UUID payableId);

    Optional<PayableSettlement> findByPayableIdAndIdempotencyKey(UUID payableId, String idempotencyKey);
}
