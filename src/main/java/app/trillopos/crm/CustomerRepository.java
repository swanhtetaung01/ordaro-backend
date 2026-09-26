package app.trillopos.crm;

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
public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    Optional<Customer> findByPhone(String phone);

    /** Name or phone contains {@code q} (never null; empty matches all), case-insensitive; active only. */
    @Query("""
            select c from Customer c
            where c.archivedAt is null
              and (lower(c.name) like lower(concat('%', :q, '%')) or c.phone like concat('%', :q, '%'))
            order by lower(c.name)""")
    List<Customer> search(@Param("q") String q, Pageable page);

    /** Taken before a credit check, so two registers cannot both pass it (spec §8). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Customer c where c.id = :id")
    Optional<Customer> lockById(@Param("id") UUID id);
}
