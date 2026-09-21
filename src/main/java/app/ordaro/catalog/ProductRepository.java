package app.ordaro.catalog;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Tenant-scoped by {@code @TenantId}. */
public interface ProductRepository extends JpaRepository<Product, UUID> {

    List<Product> findAllByArchivedAtIsNullOrderByNameAsc();

    boolean existsBySku(String sku);
}
