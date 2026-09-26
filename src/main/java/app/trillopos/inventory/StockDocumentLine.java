package app.trillopos.inventory;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import app.trillopos.shared.persistence.TenantEntity;

/**
 * {@code quantity} is positive on every type except ADJUSTMENT, where it is the <em>signed
 * difference</em> from the counted figure (a count of 39 against 42 stores −3), so two people
 * counting one shelf never overwrite each other and the shrinkage figure survives.
 */
@Entity
@Table(name = "stock_document_line")
public class StockDocumentLine extends TenantEntity {

    @Column(name = "document_id", nullable = false, updatable = false)
    private UUID documentId;

    @Column(name = "position", nullable = false, updatable = false)
    private int position;

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(name = "quantity", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal quantity;

    /** OPENING, STOCK_IN and positive ADJUSTMENT only; the current average is used otherwise. */
    @Column(name = "unit_cost", precision = 19, scale = 4, updatable = false)
    private BigDecimal unitCost;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", length = 32, updatable = false)
    private StockMovementReason reason;

    protected StockDocumentLine() {
    }

    StockDocumentLine(UUID documentId, int position, UUID productId, BigDecimal quantity, BigDecimal unitCost,
            StockMovementReason reason) {
        this.documentId = documentId;
        this.position = position;
        this.productId = productId;
        this.quantity = quantity;
        this.unitCost = unitCost;
        this.reason = reason;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public int getPosition() {
        return position;
    }

    public UUID getProductId() {
        return productId;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public BigDecimal getUnitCost() {
        return unitCost;
    }

    public StockMovementReason getReason() {
        return reason;
    }
}
