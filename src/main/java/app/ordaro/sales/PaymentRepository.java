package app.ordaro.sales;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Tenant-scoped by {@code @TenantId}. */
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    List<Payment> findAllBySaleIdOrderByCreatedAtAsc(UUID saleId);
}
