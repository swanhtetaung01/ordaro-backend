package app.ordaro.auth;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Tenant-scoped by {@code @TenantId}. */
public interface RegisterDeviceRepository extends JpaRepository<RegisterDevice, UUID> {

    List<RegisterDevice> findAllByOrderByCreatedAtDesc();
}
