package app.ordaro.finance;

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
public interface PayableRepository extends JpaRepository<Payable, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payable p where p.id = :id")
    Optional<Payable> lockById(@Param("id") UUID id);

    Optional<Payable> findBySourceTypeAndSourceId(PayableSourceType sourceType, UUID sourceId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payable p where p.sourceType = :type and p.sourceId = :source")
    Optional<Payable> lockBySource(@Param("type") PayableSourceType sourceType, @Param("source") UUID sourceId);

    /** Newest first. {@code dueBefore} is always given; today gives the overdue list. */
    @Query("""
            select p from Payable p
            where p.status in :statuses and p.dueDate < :dueBefore
            order by p.issuedAt desc""")
    List<Payable> search(@Param("statuses") Collection<PayableStatus> statuses,
            @Param("dueBefore") LocalDate dueBefore, Pageable page);

    @Query("""
            select p from Payable p
            where p.supplierId = :supplier and p.status in :statuses and p.dueDate < :dueBefore
            order by p.issuedAt desc""")
    List<Payable> searchForSupplier(@Param("supplier") UUID supplierId,
            @Param("statuses") Collection<PayableStatus> statuses, @Param("dueBefore") LocalDate dueBefore,
            Pageable page);
}
