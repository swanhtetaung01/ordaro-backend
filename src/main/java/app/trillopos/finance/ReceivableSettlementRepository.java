package app.trillopos.finance;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Tenant-scoped by {@code @TenantId}. */
public interface ReceivableSettlementRepository extends JpaRepository<ReceivableSettlement, UUID> {

    List<ReceivableSettlement> findAllByReceivableIdOrderByPaidAtAscIdAsc(UUID receivableId);

    Optional<ReceivableSettlement> findByReceivableIdAndIdempotencyKey(UUID receivableId, String idempotencyKey);
}
