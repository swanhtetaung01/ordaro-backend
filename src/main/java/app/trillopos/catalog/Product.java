package app.trillopos.catalog;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import app.trillopos.shared.persistence.TenantEntity;

/**
 * A flat SKU: each size is its own product (spec §4). There is deliberately no
 * {@code averageCost} here — cost lives per location on {@code StockBalance} (step 2).
 */
@Entity
@Table(name = "product")
public class Product extends TenantEntity {

    /** Unique per organization, not reusable after archive. */
    @Column(name = "sku", nullable = false, length = 64)
    private String sku;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "category_id")
    private UUID categoryId;

    @Column(name = "default_supplier_id")
    private UUID defaultSupplierId;

    @Enumerated(EnumType.STRING)
    @Column(name = "unit", nullable = false, length = 32)
    private ProductUnit unit;

    @Column(name = "size_label", length = 32)
    private String sizeLabel;

    @Column(name = "product_group_key", length = 64)
    private String productGroupKey;

    @Column(name = "retail_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal retailPrice;

    /** Null means not sold wholesale; the POS falls back to retail. */
    @Column(name = "wholesale_price", precision = 19, scale = 4)
    private BigDecimal wholesalePrice;

    @Column(name = "taxable", nullable = false)
    private boolean taxable = true;

    /** False for services and rentals; their sale lines carry unitCost = 0. */
    @Column(name = "track_inventory", nullable = false)
    private boolean trackInventory = true;

    @Column(name = "reorder_point", nullable = false)
    private int reorderPoint;

    @Column(name = "sell_in_pos", nullable = false)
    private boolean sellInPos = true;

    @Column(name = "sell_online", nullable = false)
    private boolean sellOnline;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    protected Product() {
    }

    public Product(String sku, String name, ProductUnit unit, BigDecimal retailPrice) {
        this.sku = sku;
        this.name = name;
        this.unit = unit;
        this.retailPrice = retailPrice;
    }

    /** An offline client may have created the product already; keep its id. */
    public Product withId(UUID id) {
        assignId(id);
        return this;
    }

    public String getSku() {
        return sku;
    }

    public void setSku(String sku) {
        this.sku = sku;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public UUID getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(UUID categoryId) {
        this.categoryId = categoryId;
    }

    public UUID getDefaultSupplierId() {
        return defaultSupplierId;
    }

    public void setDefaultSupplierId(UUID defaultSupplierId) {
        this.defaultSupplierId = defaultSupplierId;
    }

    public ProductUnit getUnit() {
        return unit;
    }

    public void setUnit(ProductUnit unit) {
        this.unit = unit;
    }

    public String getSizeLabel() {
        return sizeLabel;
    }

    public void setSizeLabel(String sizeLabel) {
        this.sizeLabel = sizeLabel;
    }

    public String getProductGroupKey() {
        return productGroupKey;
    }

    public void setProductGroupKey(String productGroupKey) {
        this.productGroupKey = productGroupKey;
    }

    public BigDecimal getRetailPrice() {
        return retailPrice;
    }

    public void setRetailPrice(BigDecimal retailPrice) {
        this.retailPrice = retailPrice;
    }

    public BigDecimal getWholesalePrice() {
        return wholesalePrice;
    }

    public void setWholesalePrice(BigDecimal wholesalePrice) {
        this.wholesalePrice = wholesalePrice;
    }

    public boolean isTaxable() {
        return taxable;
    }

    public void setTaxable(boolean taxable) {
        this.taxable = taxable;
    }

    public boolean isTrackInventory() {
        return trackInventory;
    }

    public void setTrackInventory(boolean trackInventory) {
        this.trackInventory = trackInventory;
    }

    public int getReorderPoint() {
        return reorderPoint;
    }

    public void setReorderPoint(int reorderPoint) {
        this.reorderPoint = reorderPoint;
    }

    public boolean isSellInPos() {
        return sellInPos;
    }

    public void setSellInPos(boolean sellInPos) {
        this.sellInPos = sellInPos;
    }

    public boolean isSellOnline() {
        return sellOnline;
    }

    public void setSellOnline(boolean sellOnline) {
        this.sellOnline = sellOnline;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
