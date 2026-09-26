package app.trillopos.inventory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Tenant-scoped by {@code @TenantId}. */
public interface StockDocumentRepository extends JpaRepository<StockDocument, UUID> {

    /** Posting and voiding lock the header first, so a document cannot be posted twice. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from StockDocument d where d.id = :id")
    Optional<StockDocument> lockById(@Param("id") UUID id);

    List<StockDocument> findTop100ByOrderByCreatedAtDesc();
}
