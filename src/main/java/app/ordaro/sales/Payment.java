package app.ordaro.sales;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import app.ordaro.shared.persistence.TenantEntity;

/** One tender applied to a sale; several rows is a split payment (spec §6). */
@Entity
@Table(name = "payment")
public class Payment extends TenantEntity {

    @Column(name = "sale_id", nullable = false, updatable = false)
    private UUID saleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, length = 32, updatable = false)
    private PaymentMethod method;

    /** The amount applied to the sale. */
    @Column(name = "amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal amount;

    /** Cash only: what the customer handed over, and the change, as the receipt shows them. */
    @Column(name = "tendered_amount", precision = 19, scale = 4, updatable = false)
    private BigDecimal tenderedAmount;

    @Column(name = "change_amount", precision = 19, scale = 4, updatable = false)
    private BigDecimal changeAmount;

    /** Wallet transaction id, for reconciling against the KBZPay (etc.) statement. */
    @Column(name = "reference_no", length = 100, updatable = false)
    private String referenceNo;

    protected Payment() {
    }

    Payment(UUID saleId, PaymentMethod method, BigDecimal amount, BigDecimal tenderedAmount, String referenceNo) {
        this.saleId = saleId;
        this.method = method;
        this.amount = amount;
        this.tenderedAmount = tenderedAmount;
        this.changeAmount = tenderedAmount == null ? null : tenderedAmount.subtract(amount);
        this.referenceNo = referenceNo;
    }

    public UUID getSaleId() {
        return saleId;
    }

    public PaymentMethod getMethod() {
        return method;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getTenderedAmount() {
        return tenderedAmount;
    }

    public BigDecimal getChangeAmount() {
        return changeAmount;
    }

    public String getReferenceNo() {
        return referenceNo;
    }
}
