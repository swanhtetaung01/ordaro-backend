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
 * A debt owed to the organization, plus the settlements against it (spec §7). {@code
 * outstandingAmount} is the live truth for "who owes me money"; the sale's {@code dueAmount} is
 * frozen history. Settlements write here only, never back to the sale. The database checks that
 * {@code settled + writtenOff + outstanding = original} and that the status agrees with them.
 */
@Entity
@Table(name = "receivable")
public class Receivable extends TenantEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "counterparty_type", nullable = false, length = 32, updatable = false)
    private CounterpartyType counterpartyType;

    /** Required for a CUSTOMER receivable: no credit to a nameless walk-in. */
    @Column(name = "customer_id", updatable = false)
    private UUID customerId;

    /** Where the credit was given. */
    @Column(name = "location_id", nullable = false, updatable = false)
    private UUID locationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 32, updatable = false)
    private ReceivableSourceType sourceType;

    @Column(name = "source_id", updatable = false)
    private UUID sourceId;

    /** The receipt number, for a SALE. */
    @Column(name = "reference_number", length = 40, updatable = false)
    private String referenceNumber;

    @Column(name = "original_amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal originalAmount;

    @Column(name = "settled_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal settledAmount = BigDecimal.ZERO;

    @Column(name = "written_off_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal writtenOffAmount = BigDecimal.ZERO;

    @Column(name = "outstanding_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal outstandingAmount;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ReceivableStatus status;

    @Column(name = "written_off_at")
    private Instant writtenOffAt;

    @Column(name = "note", length = 500)
    private String note;

    protected Receivable() {
    }

    private Receivable(UUID customerId, UUID locationId, ReceivableSourceType sourceType, UUID sourceId,
            String referenceNumber, BigDecimal amount, Instant issuedAt, LocalDate dueDate, String note) {
        this.counterpartyType = CounterpartyType.CUSTOMER;
        this.customerId = customerId;
        this.locationId = locationId;
        this.sourceType = sourceType;
        this.sourceId = sourceId;
        this.referenceNumber = referenceNumber;
        this.originalAmount = amount;
        this.outstandingAmount = amount;
        this.issuedAt = issuedAt;
        this.dueDate = dueDate;
        this.status = ReceivableStatus.OPEN;
        this.note = note;
    }

    /** Opened by a CREDIT payment when the sale completes (spec §9.2 step 7). */
    static Receivable forSale(UUID customerId, UUID locationId, UUID saleId, String receiptNumber,
            BigDecimal amount, Instant soldAt, LocalDate dueDate) {
        return new Receivable(customerId, locationId, ReceivableSourceType.SALE, saleId, receiptNumber, amount,
                soldAt, dueDate, null);
    }

    /** A debt carried over from before the shop used Ordaro. */
    static Receivable manual(UUID customerId, UUID locationId, BigDecimal amount, Instant issuedAt,
            LocalDate dueDate, String note) {
        return new Receivable(customerId, locationId, ReceivableSourceType.MANUAL, null, null, amount, issuedAt,
                dueDate, note);
    }

    void settle(BigDecimal amount) {
        if (!isOpen() || amount.compareTo(outstandingAmount) > 0) {
            throw new IllegalStateException("settlement exceeds what is outstanding");
        }
        settledAmount = settledAmount.add(amount);
        outstandingAmount = outstandingAmount.subtract(amount);
        status = outstandingAmount.signum() == 0 ? ReceivableStatus.SETTLED : ReceivableStatus.PARTIALLY_SETTLED;
    }

    /** What is still owed is given up. Kept as its own amount so settled money stays visible. */
    void writeOff(Instant at, String reason) {
        if (!isOpen()) {
            throw new IllegalStateException("only an open receivable is written off");
        }
        writtenOffAmount = outstandingAmount;
        outstandingAmount = BigDecimal.ZERO;
        status = ReceivableStatus.WRITTEN_OFF;
        writtenOffAt = at;
        if (reason != null) {
            note = reason;
        }
    }

    public boolean isOpen() {
        return status == ReceivableStatus.OPEN || status == ReceivableStatus.PARTIALLY_SETTLED;
    }

    public CounterpartyType getCounterpartyType() {
        return counterpartyType;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public UUID getLocationId() {
        return locationId;
    }

    public ReceivableSourceType getSourceType() {
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

    public BigDecimal getWrittenOffAmount() {
        return writtenOffAmount;
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

    public ReceivableStatus getStatus() {
        return status;
    }

    public Instant getWrittenOffAt() {
        return writtenOffAt;
    }

    public String getNote() {
        return note;
    }
}
