package app.trillopos.sales;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;

import app.trillopos.shared.persistence.TenantEntity;

/**
 * One returned line, tied to the exact sale line (spec §6). {@code unitCost} is copied from it:
 * when {@code restock} is true the SALE_RETURN inflow is at that cost and §9.1 blends it in.
 */
@Entity
@Immutable
@Table(name = "sale_return_line")
public class SaleReturnLine extends TenantEntity {

    @Column(name = "return_id", nullable = false)
    private UUID returnId;

    @Column(name = "sale_line_id", nullable = false)
    private UUID saleLineId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal quantity;

    /** Tax-inclusive, pro-rata of the original line total. */
    @Column(name = "refund_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal refundAmount;

    @Column(name = "tax_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal taxAmount;

    @Column(name = "unit_cost", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitCost;

    /** False for damaged goods: the refund happens, the stock movement and the COGS reversal do not. */
    @Column(name = "restock", nullable = false)
    private boolean restock;

    protected SaleReturnLine() {
    }

    SaleReturnLine(UUID returnId, UUID saleLineId, UUID productId, BigDecimal quantity, BigDecimal refundAmount,
            BigDecimal taxAmount, BigDecimal unitCost, boolean restock) {
        this.returnId = returnId;
        this.saleLineId = saleLineId;
        this.productId = productId;
        this.quantity = quantity;
        this.refundAmount = refundAmount;
        this.taxAmount = taxAmount;
        this.unitCost = unitCost;
        this.restock = restock;
    }

    public UUID getReturnId() {
        return returnId;
    }

    public UUID getSaleLineId() {
        return saleLineId;
    }

    public UUID getProductId() {
        return productId;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public BigDecimal getRefundAmount() {
        return refundAmount;
    }

    public BigDecimal getTaxAmount() {
        return taxAmount;
    }

    public BigDecimal getUnitCost() {
        return unitCost;
    }

    public boolean isRestock() {
        return restock;
    }
}
