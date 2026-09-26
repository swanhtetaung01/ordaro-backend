package app.trillopos.inventory;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Tenant-scoped by {@code @TenantId}. */
public interface StockBalanceRepository extends JpaRepository<StockBalance, UUID> {

    /**
     * Locks balance rows in one deterministic order — product, then location — so two
     * postings touching the same rows can never deadlock (spec §9.2 step 1).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select b from StockBalance b
            where b.locationId in :locations and b.productId in :products
            order by b.productId, b.locationId""")
    List<StockBalance> lockAll(@Param("locations") Collection<UUID> locations,
            @Param("products") Collection<UUID> products);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from StockBalance b where b.locationId = :location and b.productId = :product")
    Optional<StockBalance> lockOne(@Param("location") UUID locationId, @Param("product") UUID productId);

    Optional<StockBalance> findByLocationIdAndProductId(UUID locationId, UUID productId);

    List<StockBalance> findAllByLocationIdOrderByProductId(UUID locationId);

    List<StockBalance> findAllByProductIdOrderByLocationId(UUID productId);

    List<StockBalance> findAllByOrderByLocationIdAscProductIdAsc();
}
