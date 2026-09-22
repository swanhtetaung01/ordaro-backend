package app.ordaro.finance;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;

import app.ordaro.sales.PaymentMethod;
import app.ordaro.shared.persistence.TenantEntity;

/** One payment to a supplier. {@code cashierShiftId} set ⇒ paid from that drawer (spec §6). */
@Entity
@Immutable
@Table(name = "payable_settlement")
public class PayableSettlement extends TenantEntity {

    @Column(name = "payable_id", nullable = false)
    private UUID payableId;

    /** Where the money was paid from. */
    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    @Column(name = "cashier_shift_id")
    private UUID cashierShiftId;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, length = 32)
    private PaymentMethod method;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "reference_no", length = 100)
    private String referenceNo;

    @Column(name = "paid_at", nullable = false)
    private Instant paidAt;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "idempotency_key", length = 100)
    private String idempotencyKey;

    protected PayableSettlement() {
    }

    PayableSettlement(UUID payableId, UUID locationId, UUID cashierShiftId, PaymentMethod method, BigDecimal amount,
            String referenceNo, Instant paidAt, String note, String idempotencyKey) {
        this.payableId = payableId;
        this.locationId = locationId;
        this.cashierShiftId = cashierShiftId;
        this.method = method;
        this.amount = amount;
        this.referenceNo = referenceNo;
        this.paidAt = paidAt;
        this.note = note;
        this.idempotencyKey = idempotencyKey;
    }

    public UUID getPayableId() {
        return payableId;
    }

    public UUID getLocationId() {
        return locationId;
    }

    public UUID getCashierShiftId() {
        return cashierShiftId;
    }

    public PaymentMethod getMethod() {
        return method;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getReferenceNo() {
        return referenceNo;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public String getNote() {
        return note;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }
}
