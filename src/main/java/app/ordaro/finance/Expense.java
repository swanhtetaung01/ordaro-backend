package app.ordaro.finance;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import app.ordaro.shared.persistence.TenantEntity;

/**
 * Money out that is not a supplier debt (spec §7). Per location. {@code cashierShiftId} set ⇒
 * cash left that drawer. A mistake is voided, never deleted: the count already happened.
 */
@Entity
@Table(name = "expense")
public class Expense extends TenantEntity {

    @Column(name = "category_id", nullable = false, updatable = false)
    private UUID categoryId;

    @Column(name = "location_id", nullable = false, updatable = false)
    private UUID locationId;

    @Column(name = "cashier_shift_id", updatable = false)
    private UUID cashierShiftId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, length = 32, updatable = false)
    private ExpenseMethod method;

    @Column(name = "paid_at", nullable = false, updatable = false)
    private Instant paidAt;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "reference_no", length = 100)
    private String referenceNo;

    @Column(name = "voided_at")
    private Instant voidedAt;

    protected Expense() {
    }

    Expense(UUID categoryId, UUID locationId, UUID cashierShiftId, BigDecimal amount, ExpenseMethod method,
            Instant paidAt, String description, String referenceNo) {
        this.categoryId = categoryId;
        this.locationId = locationId;
        this.cashierShiftId = cashierShiftId;
        this.amount = amount;
        this.method = method;
        this.paidAt = paidAt;
        this.description = description;
        this.referenceNo = referenceNo;
    }

    void voidExpense(Instant at) {
        if (voidedAt != null) {
            throw new IllegalStateException("already void");
        }
        voidedAt = at;
    }

    public boolean isVoid() {
        return voidedAt != null;
    }

    public UUID getCategoryId() {
        return categoryId;
    }

    public UUID getLocationId() {
        return locationId;
    }

    public UUID getCashierShiftId() {
        return cashierShiftId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public ExpenseMethod getMethod() {
        return method;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public String getDescription() {
        return description;
    }

    public String getReferenceNo() {
        return referenceNo;
    }

    public Instant getVoidedAt() {
        return voidedAt;
    }
}
