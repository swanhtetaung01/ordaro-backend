package app.trillopos.finance;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Tenant-scoped by {@code @TenantId}. */
public interface ExpenseCategoryRepository extends JpaRepository<ExpenseCategory, UUID> {

    List<ExpenseCategory> findAllByArchivedAtIsNullOrderByNameAsc();

    Optional<ExpenseCategory> findByNameIgnoreCase(String name);
}
