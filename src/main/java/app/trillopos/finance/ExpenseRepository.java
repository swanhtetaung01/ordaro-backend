package app.trillopos.finance;

import java.time.Instant;
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
public interface ExpenseRepository extends JpaRepository<Expense, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Expense e where e.id = :id")
    Optional<Expense> lockById(@Param("id") UUID id);

    /** Newest first, void included (the list shows them struck through). */
    @Query("""
            select e from Expense e
            where e.paidAt >= :from and e.paidAt < :to
            order by e.paidAt desc""")
    List<Expense> between(@Param("from") Instant from, @Param("to") Instant to, Pageable page);

    @Query("""
            select e from Expense e
            where e.locationId = :location and e.paidAt >= :from and e.paidAt < :to
            order by e.paidAt desc""")
    List<Expense> betweenAt(@Param("location") UUID locationId, @Param("from") Instant from,
            @Param("to") Instant to, Pageable page);
}
