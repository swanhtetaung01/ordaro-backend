package app.ordaro.inventory;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Tenant-scoped by {@code @TenantId}. Ledger order is always {@code seq} within a (location, product). */
public interface StockMovementRepository extends JpaRepository<StockMovement, UUID> {

    @Query("""
            select m from StockMovement m
            where m.referenceType = :type and m.referenceId = :id
            order by m.productId, m.locationId, m.seq""")
    List<StockMovement> findByReference(@Param("type") StockReferenceType type, @Param("id") UUID referenceId);

    /** Newest first, for the ledger view. */
    @Query("""
            select m from StockMovement m
            where m.locationId = :location and m.productId = :product
            order by m.seq desc""")
    List<StockMovement> ledger(@Param("location") UUID locationId, @Param("product") UUID productId,
            Pageable page);
}
