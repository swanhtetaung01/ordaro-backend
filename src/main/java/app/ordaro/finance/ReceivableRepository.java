package app.ordaro.finance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Tenant-scoped by {@code @TenantId}. */
public interface ReceivableRepository extends JpaRepository<Receivable, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Receivable r where r.id = :id")
    Optional<Receivable> lockById(@Param("id") UUID id);

    Optional<Receivable> findBySourceTypeAndSourceId(ReceivableSourceType sourceType, UUID sourceId);

    /** Σ outstanding for one customer: what a credit limit is checked against. */
    @Query("select coalesce(sum(r.outstandingAmount), 0) from Receivable r where r.customerId = :customer")
    BigDecimal outstandingFor(@Param("customer") UUID customerId);

    /** Σ outstanding per customer, for a list of customers in one query. */
    @Query("""
            select new app.ordaro.finance.Owed(r.customerId, sum(r.outstandingAmount))
            from Receivable r where r.customerId in :customers and r.outstandingAmount > 0
            group by r.customerId""")
    List<Owed> outstandingByCustomer(@Param("customers") Collection<UUID> customerIds);

    /** Newest first. {@code dueBefore} is always given; today gives the overdue list. */
    @Query("""
            select r from Receivable r
            where r.status in :statuses and r.dueDate < :dueBefore
            order by r.issuedAt desc""")
    List<Receivable> search(@Param("statuses") Collection<ReceivableStatus> statuses,
            @Param("dueBefore") LocalDate dueBefore, Pageable page);

    @Query("""
            select r from Receivable r
            where r.customerId = :customer and r.status in :statuses and r.dueDate < :dueBefore
            order by r.issuedAt desc""")
    List<Receivable> searchForCustomer(@Param("customer") UUID customerId,
            @Param("statuses") Collection<ReceivableStatus> statuses, @Param("dueBefore") LocalDate dueBefore,
            Pageable page);
}
