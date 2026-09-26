package app.trillopos.catalog;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Tenant-scoped by {@code @TenantId}. */
public interface SupplierRepository extends JpaRepository<Supplier, UUID> {

    List<Supplier> findAllByArchivedAtIsNullOrderByNameAsc();
}
