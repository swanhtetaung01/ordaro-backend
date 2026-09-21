package app.ordaro.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Tenant-scoped by {@code @TenantId}. */
public interface LocationProductRepository extends JpaRepository<LocationProduct, UUID> {

    Optional<LocationProduct> findByLocationIdAndProductId(UUID locationId, UUID productId);

    List<LocationProduct> findAllByProductId(UUID productId);
}
