package app.trillopos.sales;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Tenant-scoped by {@code @TenantId}. */
public interface SaleReturnLineRepository extends JpaRepository<SaleReturnLine, UUID> {

    List<SaleReturnLine> findAllByReturnIdOrderByIdAsc(UUID returnId);

    /** Σ quantity already returned per sale line: the ceiling for a new return. */
    @Query("""
            select new app.trillopos.sales.Returned(l.saleLineId, sum(l.quantity))
            from SaleReturnLine l where l.saleLineId in :lines group by l.saleLineId""")
    List<Returned> returnedSoFar(@Param("lines") Collection<UUID> saleLineIds);
}
