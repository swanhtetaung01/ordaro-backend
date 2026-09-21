package app.ordaro.inventory;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Tenant-scoped by {@code @TenantId}. */
public interface StockDocumentLineRepository extends JpaRepository<StockDocumentLine, UUID> {

    List<StockDocumentLine> findAllByDocumentIdOrderByPosition(UUID documentId);

    @Modifying
    @Query("delete from StockDocumentLine l where l.documentId = :document")
    int deleteAllOfDocument(@Param("document") UUID documentId);
}
