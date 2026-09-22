package app.ordaro.finance;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import app.ordaro.shared.persistence.TenantEntity;

/**
 * A debt the organization owes a supplier, plus the payments against it (spec §7). Opened when
 * a STOCK_IN from a supplier is posted; a stock-in paid on the spot is still a payable with an
 * immediate settlement. The database checks that the amounts and the status agree.
 */
@Entity
@Table(name = "payable")
public class Payable extends TenantEntity {

    @Column(name = "supplier_id", nullable = false, updatable = false)
    private UUID supplierId;

    /** The location that received the goods. */
    @Column(name = "location_id", nullable = false, updatable = false)
    private UUID locationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 32, updatable = false)
    private PayableSourceType sourceType;

    @Column(name = "source_id", updatable = false)
    private UUID sourceId;

    /** The GRN number, for a stock-in. */
    @Column(name = "reference_number", length = 40, updatable = false)
    private String referenceNumber;

    @Column(name = "original_amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal originalAmount;

    @Column(name = "settled_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal settledAmount = BigDecimal.ZERO;

    @Column(name = "outstanding_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal outstandingAmount;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private PayableStatus status;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "note", length = 500)
    private String note;

    protected Payable() {
    }

    private Payable(UUID supplierId, UUID locationId, PayableSourceType sourceType, UUID sourceId,
            String referenceNumber, BigDecimal amount, Instant issuedAt, LocalDate dueDate, String note) {
        this.supplierId = supplierId;
        this.locationId = locationId;
        this.sourceType = sourceType;
        this.sourceId = sourceId;
        this.referenceNumber = referenceNumber;
        this.originalAmount = amount;
        this.outstandingAmount = amount;
        this.issuedAt = issuedAt;
        this.dueDate = dueDate;
        this.status = PayableStatus.OPEN;
        this.note = note;
    }

    static Payable forStockIn(UUID supplierId, UUID locationId, UUID documentId, String documentNumber,
            BigDecimal amount, Instant postedAt, LocalDate dueDate) {
        return new Payable(supplierId, locationId, PayableSourceType.STOCK_DOCUMENT, documentId, documentNumber,
                amount, postedAt, dueDate, null);
    }

    static Payable manual(UUID supplierId, UUID locationId, BigDecimal amount, Instant issuedAt, LocalDate dueDate,
            String note) {
        return new Payable(supplierId, locationId, PayableSourceType.MANUAL, null, null, amount, issuedAt, dueDate,
                note);
    }

    void settle(BigDecimal amount) {
        if (!isOpen() || amount.compareTo(outstandingAmount) > 0) {
            throw new IllegalStateException("settlement exceeds what is outstanding");
        }
        settledAmount = settledAmount.add(amount);
        outstandingAmount = outstandingAmount.subtract(amount);
        status = outstandingAmount.signum() == 0 ? PayableStatus.SETTLED : PayableStatus.PARTIALLY_SETTLED;
    }

    /** Only an unpaid payable is cancelled: its stock-in was voided and nothing was paid. */
    void cancel(Instant at) {
        if (status != PayableStatus.OPEN || settledAmount.signum() != 0) {
            throw new IllegalStateException("only an unpaid payable is cancelled");
        }
        outstandingAmount = BigDecimal.ZERO;
        status = PayableStatus.CANCELLED;
        cancelledAt = at;
    }

    public boolean isOpen() {
        return status == PayableStatus.OPEN || status == PayableStatus.PARTIALLY_SETTLED;
    }

    public UUID getSupplierId() {
        return supplierId;
    }

    public UUID getLocationId() {
        return locationId;
    }

    public PayableSourceType getSourceType() {
        return sourceType;
    }

    public UUID getSourceId() {
        return sourceId;
    }

    public String getReferenceNumber() {
        return referenceNumber;
    }

    public BigDecimal getOriginalAmount() {
        return originalAmount;
    }

    public BigDecimal getSettledAmount() {
        return settledAmount;
    }

    public BigDecimal getOutstandingAmount() {
        return outstandingAmount;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public PayableStatus getStatus() {
        return status;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public String getNote() {
        return note;
    }
}
