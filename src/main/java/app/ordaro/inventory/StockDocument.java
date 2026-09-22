package app.ordaro.inventory;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import app.ordaro.shared.persistence.TenantEntity;

/**
 * Header of a stock-in, stock-out, adjustment, transfer or opening-stock document (spec §5).
 * A DRAFT writes no movements — that is the difference between "Save draft" and "Post". The
 * number is assigned on posting, so an abandoned draft burns no number.
 */
@Entity
@Table(name = "stock_document")
public class StockDocument extends TenantEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    private StockDocumentType type;

    /** The location whose stock this changes; for a TRANSFER, the source. Owns the number sequence. */
    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    /** TRANSFER only: the destination. Both legs post in one transaction; no in-transit state in v1. */
    @Column(name = "counterparty_location_id")
    private UUID counterpartyLocationId;

    /** STOCK_IN only. Posting opens a payable to this supplier (spec §7). */
    @Column(name = "supplier_id")
    private UUID supplierId;

    @Column(name = "document_number", length = 40)
    private String documentNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private StockDocumentStatus status;

    /** Business time: becomes every movement's {@code movedAt} and picks the number's year. */
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "posted_at")
    private Instant postedAt;

    @Column(name = "voided_at")
    private Instant voidedAt;

    protected StockDocument() {
    }

    StockDocument(StockDocumentType type, UUID locationId, UUID counterpartyLocationId, UUID supplierId,
            Instant occurredAt, String note) {
        this.type = type;
        this.status = StockDocumentStatus.DRAFT;
        revise(type, locationId, counterpartyLocationId, supplierId, occurredAt, note);
    }

    void revise(StockDocumentType type, UUID locationId, UUID counterpartyLocationId, UUID supplierId,
            Instant occurredAt, String note) {
        requireStatus(StockDocumentStatus.DRAFT);
        this.type = type;
        this.locationId = locationId;
        this.counterpartyLocationId = counterpartyLocationId;
        this.supplierId = supplierId;
        this.occurredAt = occurredAt;
        this.note = note;
    }

    void markPosted(String documentNumber, Instant now) {
        requireStatus(StockDocumentStatus.DRAFT);
        this.documentNumber = documentNumber;
        this.status = StockDocumentStatus.POSTED;
        this.postedAt = now;
    }

    void markVoided(Instant now) {
        if (status == StockDocumentStatus.VOID) {
            throw new IllegalStateException("already void");
        }
        this.status = StockDocumentStatus.VOID;
        this.voidedAt = now;
    }

    private void requireStatus(StockDocumentStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("document is " + status + ", expected " + expected);
        }
    }

    public StockDocumentType getType() {
        return type;
    }

    public UUID getLocationId() {
        return locationId;
    }

    public UUID getCounterpartyLocationId() {
        return counterpartyLocationId;
    }

    public UUID getSupplierId() {
        return supplierId;
    }

    public String getDocumentNumber() {
        return documentNumber;
    }

    public StockDocumentStatus getStatus() {
        return status;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getNote() {
        return note;
    }

    public Instant getPostedAt() {
        return postedAt;
    }

    public Instant getVoidedAt() {
        return voidedAt;
    }
}
