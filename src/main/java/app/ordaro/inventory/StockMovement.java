package app.ordaro.inventory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;

import app.ordaro.shared.persistence.TenantEntity;

/**
 * One row of the append-only stock ledger (spec §5). Never updated, never deleted — the
 * database refuses both — so a mistake is corrected by posting another row. Ledger order is
 * {@link #getSeq() seq}, per (location, product); {@code createdAt} is informational and
 * {@code movedAt} is business time, for display and reporting only.
 */
@Entity
@Immutable
@Table(name = "stock_movement")
public class StockMovement extends TenantEntity {

    @Column(name = "location_id", nullable = false, updatable = false)
    private UUID locationId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    /** Position in this (location, product)'s ledger: 1, 2, 3 … assigned under the balance row lock. */
    @Column(name = "seq", nullable = false, updatable = false)
    private long seq;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32, updatable = false)
    private StockMovementType type;

    /** Signed: positive in, negative out. */
    @Column(name = "quantity", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal quantity;

    /** On an inflow, what was paid; on an outflow, the weighted average consumed. */
    @Column(name = "unit_cost", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal unitCost;

    /** Running balance of this (location, product) after this row, in posting order. */
    @Column(name = "balance_after", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal balanceAfter;

    @Enumerated(EnumType.STRING)
    @Column(name = "reference_type", nullable = false, length = 32, updatable = false)
    private StockReferenceType referenceType;

    @Column(name = "reference_id", nullable = false, updatable = false)
    private UUID referenceId;

    /** Human-readable, denormalised so the ledger renders without joins. */
    @Column(name = "reference_number", length = 40, updatable = false)
    private String referenceNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", length = 32, updatable = false)
    private StockMovementReason reason;

    /** Unused in v1; here so batch and expiry are a feature later, not a migration. */
    @Column(name = "batch_no", length = 64, updatable = false)
    private String batchNo;

    @Column(name = "expiry_date", updatable = false)
    private LocalDate expiryDate;

    @Column(name = "moved_at", nullable = false, updatable = false)
    private Instant movedAt;

    protected StockMovement() {
    }

    StockMovement(UUID locationId, UUID productId, long seq, StockMovementType type, BigDecimal quantity,
            BigDecimal unitCost, BigDecimal balanceAfter, StockReferenceType referenceType, UUID referenceId, String referenceNumber,
            StockMovementReason reason, Instant movedAt) {
        this.locationId = locationId;
        this.productId = productId;
        this.seq = seq;
        this.type = type;
        this.quantity = quantity;
        this.unitCost = unitCost;
        this.balanceAfter = balanceAfter;
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.referenceNumber = referenceNumber;
        this.reason = reason;
        this.movedAt = movedAt;
    }

    public UUID getLocationId() {
        return locationId;
    }

    public UUID getProductId() {
        return productId;
    }

    public long getSeq() {
        return seq;
    }

    public StockMovementType getType() {
        return type;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public BigDecimal getUnitCost() {
        return unitCost;
    }

    public BigDecimal getBalanceAfter() {
        return balanceAfter;
    }

    public StockReferenceType getReferenceType() {
        return referenceType;
    }

    public UUID getReferenceId() {
        return referenceId;
    }

    public String getReferenceNumber() {
        return referenceNumber;
    }

    public StockMovementReason getReason() {
        return reason;
    }

    public String getBatchNo() {
        return batchNo;
    }

    public LocalDate getExpiryDate() {
        return expiryDate;
    }

    public Instant getMovedAt() {
        return movedAt;
    }
}
