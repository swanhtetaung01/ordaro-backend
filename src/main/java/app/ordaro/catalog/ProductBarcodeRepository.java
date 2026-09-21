package app.ordaro.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Tenant-scoped by {@code @TenantId}. */
public interface ProductBarcodeRepository extends JpaRepository<ProductBarcode, UUID> {

    List<ProductBarcode> findAllByProductIdOrderByCreatedAtAsc(UUID productId);

    Optional<ProductBarcode> findByBarcode(String barcode);
}
