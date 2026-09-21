package app.ordaro.sales;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import app.ordaro.shared.persistence.TenantEntity;

/**
 * A sale line: copies of what the product was at the moment of sale (spec §6). On a completed
 * sale, {@code unitCost} is the location's weighted average at that instant — the snapshot every
 * margin figure reads and never recomputes. On a parked cart the line is provisional and
 * {@code unitCost} is 0 until completion rewrites it.
 */
@Entity
@Table(name = "sale_line")
public class SaleLine extends TenantEntity {

    @Column(name = "sale_id", nullable = false, updatable = false)
    private UUID saleId;

    @Column(name = "position", nullable = false, updatable = false)
    private int position;

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(name = "product_name", nullable = false, length = 200, updatable = false)
    private String productName;

    @Column(name = "sku", nullable = false, length = 64, updatable = false)
    private String sku;

    @Column(name = "quantity", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal quantity;

    @Column(name = "unit_price", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal unitPrice;

    @Column(name = "discount_amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal discountAmount;

    @Column(name = "cart_discount_allocated", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal cartDiscountAllocated;

    @Column(name = "tax_rate", nullable = false, precision = 7, scale = 4, updatable = false)
    private BigDecimal taxRate;

    @Column(name = "tax_amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal taxAmount;

    @Column(name = "unit_cost", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal unitCost;

    @Column(name = "line_total", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal lineTotal;

    protected SaleLine() {
    }

    SaleLine(UUID saleId, int position, SaleCalculator.PricedLine priced, BigDecimal unitCost) {
        this.saleId = saleId;
        this.position = position;
        this.productId = priced.productId();
        this.productName = priced.productName();
        this.sku = priced.sku();
        this.quantity = priced.quantity();
        this.unitPrice = priced.unitPrice();
        this.discountAmount = priced.discountAmount();
        this.cartDiscountAllocated = priced.cartDiscountAllocated();
        this.taxRate = priced.taxRate();
        this.taxAmount = priced.taxAmount();
        this.lineTotal = priced.lineTotal();
        this.unitCost = unitCost;
    }

    public UUID getSaleId() {
        return saleId;
    }

    public int getPosition() {
        return position;
    }

    public UUID getProductId() {
        return productId;
    }

    public String getProductName() {
        return productName;
    }

    public String getSku() {
        return sku;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public BigDecimal getDiscountAmount() {
        return discountAmount;
    }

    public BigDecimal getCartDiscountAllocated() {
        return cartDiscountAllocated;
    }

    public BigDecimal getTaxRate() {
        return taxRate;
    }

    public BigDecimal getTaxAmount() {
        return taxAmount;
    }

    public BigDecimal getUnitCost() {
        return unitCost;
    }

    public BigDecimal getLineTotal() {
        return lineTotal;
    }
}
