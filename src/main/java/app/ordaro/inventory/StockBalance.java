package app.ordaro.inventory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import app.ordaro.shared.persistence.TenantEntity;

/**
 * The per-(location, product) projection of the ledger (spec §5): current quantity and the
 * weighted-average cost. Changed only by {@link StockLedger} under a row lock, in the same
 * transaction as the movement that explains it, and rebuildable from the ledger. Rows are
 * created lazily by the posting path, never by application code.
 */
@Entity
@Table(name = "stock_balance")
public class StockBalance extends TenantEntity {

    @Column(name = "location_id", nullable = false, updatable = false)
    private UUID locationId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(name = "quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal quantity;

    @Column(name = "average_cost", nullable = false, precision = 19, scale = 4)
    private BigDecimal averageCost;

    /** Drives the dead-stock report. The latest business time seen, never moved backwards. */
    @Column(name = "last_movement_at")
    private Instant lastMovementAt;

    protected StockBalance() {
    }

    void applyInflow(BigDecimal inQuantity, BigDecimal unitCost) {
        averageCost = WeightedAverage.afterInflow(quantity, averageCost, inQuantity, unitCost);
        quantity = quantity.add(inQuantity);
    }

    /** Outflows never change the average (spec §9.1). */
    void applyOutflow(BigDecimal signedQuantity) {
        quantity = quantity.add(signedQuantity);
    }

    void touch(Instant movedAt) {
        if (lastMovementAt == null || movedAt.isAfter(lastMovementAt)) {
            lastMovementAt = movedAt;
        }
    }

    /** Only for the rebuild job, under the row lock, with values replayed from the ledger. */
    void correctTo(BigDecimal quantity, BigDecimal averageCost) {
        this.quantity = quantity;
        this.averageCost = averageCost;
    }

    public UUID getLocationId() {
        return locationId;
    }

    public UUID getProductId() {
        return productId;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public BigDecimal getAverageCost() {
        return averageCost;
    }

    public Instant getLastMovementAt() {
        return lastMovementAt;
    }
}
