package app.ordaro.sales;

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
 * A sale header (spec §6). While DRAFT or HELD it is a parked cart: provisional totals, no
 * receipt number, no stock movement, no cost. Completion (§9.2) fixes everything at once.
 */
@Entity
@Table(name = "sale")
public class Sale extends TenantEntity {

    /** Must be a STORE: its RCP sequence numbers the receipt. */
    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    @Column(name = "cashier_shift_id")
    private UUID cashierShiftId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 32)
    private SaleChannel channel;

    @Column(name = "order_id")
    private UUID orderId;

    /** A walk-in is null, never a "Walk-in customer" row. */
    @Column(name = "customer_id")
    private UUID customerId;

    /** Client-supplied, unique per location: a retried completion returns the original. */
    @Column(name = "idempotency_key", length = 100)
    private String idempotencyKey;

    @Column(name = "receipt_number", length = 40)
    private String receiptNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private SaleStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "price_type", nullable = false, length = 32)
    private PriceType priceType;

    /** Snapshot of the organization setting: old receipts never re-interpret themselves. */
    @Column(name = "tax_inclusive", nullable = false)
    private boolean taxInclusive;

    @Column(name = "subtotal", nullable = false, precision = 19, scale = 4)
    private BigDecimal subtotal = BigDecimal.ZERO;

    @Column(name = "line_discount_total", nullable = false, precision = 19, scale = 4)
    private BigDecimal lineDiscountTotal = BigDecimal.ZERO;

    @Column(name = "cart_discount_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal cartDiscountAmount = BigDecimal.ZERO;

    /** Always Σ SaleLine.taxAmount, never computed on the header. */
    @Column(name = "tax_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal taxAmount = BigDecimal.ZERO;

    @Column(name = "rounding_adjustment", nullable = false, precision = 19, scale = 4)
    private BigDecimal roundingAdjustment = BigDecimal.ZERO;

    @Column(name = "total", nullable = false, precision = 19, scale = 4)
    private BigDecimal total = BigDecimal.ZERO;

    /** Frozen at completion as history; who owes money is answered by Receivable, never these. */
    @Column(name = "paid_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal paidAmount = BigDecimal.ZERO;

    @Column(name = "due_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal dueAmount = BigDecimal.ZERO;

    /** Business time, fixed at completion. */
    @Column(name = "sold_at")
    private Instant soldAt;

    @Column(name = "voided_at")
    private Instant voidedAt;

    protected Sale() {
    }

    Sale(UUID locationId, SaleChannel channel, UUID cashierShiftId, UUID customerId, PriceType priceType,
            boolean taxInclusive, SaleStatus status) {
        this.locationId = locationId;
        this.channel = channel;
        this.cashierShiftId = cashierShiftId;
        this.customerId = customerId;
        this.priceType = priceType;
        this.taxInclusive = taxInclusive;
        this.status = status;
    }

    void reviseCart(SaleChannel channel, UUID cashierShiftId, UUID customerId, PriceType priceType,
            boolean taxInclusive) {
        requireOpenCart();
        this.channel = channel;
        this.cashierShiftId = cashierShiftId;
        this.customerId = customerId;
        this.priceType = priceType;
        this.taxInclusive = taxInclusive;
    }

    void applyTotals(SaleCalculator.Totals totals) {
        this.subtotal = totals.subtotal();
        this.lineDiscountTotal = totals.lineDiscountTotal();
        this.cartDiscountAmount = totals.cartDiscountAmount();
        this.taxAmount = totals.taxAmount();
        this.roundingAdjustment = totals.roundingAdjustment();
        this.total = totals.total();
    }

    /** Claims the idempotency key before anything else is written (spec §9.2 step 0). */
    void claimIdempotencyKey(String key) {
        requireOpenCart();
        this.idempotencyKey = key;
    }

    void hold() {
        requireOpenCart();
        this.status = SaleStatus.HELD;
    }

    void complete(String receiptNumber, Instant soldAt, BigDecimal paidAmount) {
        requireOpenCart();
        this.receiptNumber = receiptNumber;
        this.soldAt = soldAt;
        this.paidAmount = paidAmount;
        this.dueAmount = total.subtract(paidAmount);
        this.status = SaleStatus.COMPLETED;
    }

    /** A return moves a completed sale to PARTIALLY_REFUNDED, or REFUNDED once every unit is back (spec §6). */
    void markRefunded(boolean everythingReturned) {
        if (!tookMoney()) {
            throw new IllegalStateException("only a completed sale is refunded; this one is " + status);
        }
        this.status = everythingReturned ? SaleStatus.REFUNDED : SaleStatus.PARTIALLY_REFUNDED;
    }

    /** COMPLETED or later: money was taken and stock moved. */
    public boolean tookMoney() {
        return status == SaleStatus.COMPLETED || status == SaleStatus.PARTIALLY_REFUNDED
                || status == SaleStatus.REFUNDED;
    }

    /** Only a parked cart can be voided: nothing was posted and no number was used. */
    void voidCart(Instant at) {
        requireOpenCart();
        this.status = SaleStatus.VOID;
        this.voidedAt = at;
    }

    boolean isOpenCart() {
        return status == SaleStatus.DRAFT || status == SaleStatus.HELD;
    }

    private void requireOpenCart() {
        if (!isOpenCart()) {
            throw new IllegalStateException("the sale is " + status);
        }
    }

    public UUID getLocationId() {
        return locationId;
    }

    public UUID getCashierShiftId() {
        return cashierShiftId;
    }

    public SaleChannel getChannel() {
        return channel;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getReceiptNumber() {
        return receiptNumber;
    }

    public SaleStatus getStatus() {
        return status;
    }

    public PriceType getPriceType() {
        return priceType;
    }

    public boolean isTaxInclusive() {
        return taxInclusive;
    }

    public BigDecimal getSubtotal() {
        return subtotal;
    }

    public BigDecimal getLineDiscountTotal() {
        return lineDiscountTotal;
    }

    public BigDecimal getCartDiscountAmount() {
        return cartDiscountAmount;
    }

    public BigDecimal getTaxAmount() {
        return taxAmount;
    }

    public BigDecimal getRoundingAdjustment() {
        return roundingAdjustment;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public BigDecimal getPaidAmount() {
        return paidAmount;
    }

    public BigDecimal getDueAmount() {
        return dueAmount;
    }

    public Instant getSoldAt() {
        return soldAt;
    }

    public Instant getVoidedAt() {
        return voidedAt;
    }
}
