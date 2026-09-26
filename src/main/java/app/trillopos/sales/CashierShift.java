package app.trillopos.sales;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import app.trillopos.shared.persistence.TenantEntity;

/**
 * A register session at one STORE, from opening float to cash count (spec §6). One OPEN shift
 * per location. The variance is stored at close and never recomputed.
 */
@Entity
@Table(name = "cashier_shift")
public class CashierShift extends TenantEntity {

    @Column(name = "location_id", nullable = false, updatable = false)
    private UUID locationId;

    /** The membership that opened it (spec: {@code userId}). */
    @Column(name = "opened_by", nullable = false, updatable = false)
    private UUID openedBy;

    @Column(name = "opened_at", nullable = false, updatable = false)
    private Instant openedAt;

    @Column(name = "opening_float", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal openingFloat;

    @Column(name = "closed_by")
    private UUID closedBy;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "expected_cash", precision = 19, scale = 4)
    private BigDecimal expectedCash;

    @Column(name = "counted_cash", precision = 19, scale = 4)
    private BigDecimal countedCash;

    @Column(name = "variance", precision = 19, scale = 4)
    private BigDecimal variance;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ShiftStatus status;

    protected CashierShift() {
    }

    CashierShift(UUID locationId, UUID openedBy, Instant openedAt, BigDecimal openingFloat) {
        this.locationId = locationId;
        this.openedBy = openedBy;
        this.openedAt = openedAt;
        this.openingFloat = openingFloat;
        this.status = ShiftStatus.OPEN;
    }

    void close(UUID closedBy, Instant closedAt, BigDecimal expectedCash, BigDecimal countedCash) {
        if (status != ShiftStatus.OPEN) {
            throw new IllegalStateException("the shift is already closed");
        }
        this.closedBy = closedBy;
        this.closedAt = closedAt;
        this.expectedCash = expectedCash;
        this.countedCash = countedCash;
        this.variance = countedCash.subtract(expectedCash);
        this.status = ShiftStatus.CLOSED;
    }

    public boolean isOpen() {
        return status == ShiftStatus.OPEN;
    }

    public UUID getLocationId() {
        return locationId;
    }

    public UUID getOpenedBy() {
        return openedBy;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public BigDecimal getOpeningFloat() {
        return openingFloat;
    }

    public UUID getClosedBy() {
        return closedBy;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public BigDecimal getExpectedCash() {
        return expectedCash;
    }

    public BigDecimal getCountedCash() {
        return countedCash;
    }

    public BigDecimal getVariance() {
        return variance;
    }

    public ShiftStatus getStatus() {
        return status;
    }
}
