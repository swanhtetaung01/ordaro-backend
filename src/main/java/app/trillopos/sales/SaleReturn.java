package app.trillopos.sales;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;

import app.trillopos.shared.persistence.TenantEntity;

/**
 * A return against a completed sale (spec §6): its own document, never a negative sale. Written
 * once; a mistaken return is corrected by a new sale, not by editing this row.
 */
@Entity
@Immutable
@Table(name = "sale_return")
public class SaleReturn extends TenantEntity {

    @Column(name = "original_sale_id", nullable = false)
    private UUID originalSaleId;

    /** The STORE handling the return: numbers it and receives restocked goods. */
    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    /** Set when cash left a drawer. */
    @Column(name = "cashier_shift_id")
    private UUID cashierShiftId;

    @Column(name = "return_number", nullable = false, length = 40)
    private String returnNumber;

    /** CREDIT means "reduce the customer's receivable"; no money moves. */
    @Enumerated(EnumType.STRING)
    @Column(name = "refund_method", nullable = false, length = 32)
    private PaymentMethod refundMethod;

    @Column(name = "refund_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal refundAmount;

    @Column(name = "tax_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal taxAmount;

    @Column(name = "reference_no", length = 100)
    private String referenceNo;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "returned_at", nullable = false)
    private Instant returnedAt;

    @Column(name = "idempotency_key", length = 100)
    private String idempotencyKey;

    protected SaleReturn() {
    }

    /** {@code id} is pre-assigned so the movements and lines can reference it before the header is saved. */
    SaleReturn(UUID id, UUID originalSaleId, UUID locationId, UUID cashierShiftId, String returnNumber,
            PaymentMethod refundMethod, BigDecimal refundAmount, BigDecimal taxAmount, String referenceNo,
            String reason, Instant returnedAt, String idempotencyKey) {
        assignId(id);
        this.originalSaleId = originalSaleId;
        this.locationId = locationId;
        this.cashierShiftId = cashierShiftId;
        this.returnNumber = returnNumber;
        this.refundMethod = refundMethod;
        this.refundAmount = refundAmount;
        this.taxAmount = taxAmount;
        this.referenceNo = referenceNo;
        this.reason = reason;
        this.returnedAt = returnedAt;
        this.idempotencyKey = idempotencyKey;
    }

    public UUID getOriginalSaleId() {
        return originalSaleId;
    }

    public UUID getLocationId() {
        return locationId;
    }

    public UUID getCashierShiftId() {
        return cashierShiftId;
    }

    public String getReturnNumber() {
        return returnNumber;
    }

    public PaymentMethod getRefundMethod() {
        return refundMethod;
    }

    public BigDecimal getRefundAmount() {
        return refundAmount;
    }

    public BigDecimal getTaxAmount() {
        return taxAmount;
    }

    public String getReferenceNo() {
        return referenceNo;
    }

    public String getReason() {
        return reason;
    }

    public Instant getReturnedAt() {
        return returnedAt;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }
}
