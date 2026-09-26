package app.trillopos.sales;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Tenant-scoped by {@code @TenantId}. */
public interface SaleLineRepository extends JpaRepository<SaleLine, UUID> {

    List<SaleLine> findAllBySaleIdOrderByPosition(UUID saleId);

    /** Line counts for a page of sales, in one query. */
    @Query("""
            select new app.trillopos.sales.LineCount(l.saleId, count(l))
            from SaleLine l where l.saleId in :sales group by l.saleId""")
    List<LineCount> countLines(@Param("sales") Collection<UUID> saleIds);

    /** Only a parked cart's provisional lines are ever replaced. */
    @Modifying
    @Query("delete from SaleLine l where l.saleId = :sale")
    int deleteAllOfSale(@Param("sale") UUID saleId);
}
