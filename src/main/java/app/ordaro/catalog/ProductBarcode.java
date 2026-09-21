package app.ordaro.catalog;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import app.ordaro.shared.persistence.TenantEntity;

/**
 * Its own table, not a column: suppliers reissue packaging and the old barcode must keep
 * scanning. Unique on (organization, barcode) — the hottest lookup in the system — and not
 * reusable after archive.
 */
@Entity
@Table(name = "product_barcode")
public class ProductBarcode extends TenantEntity {

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "barcode", nullable = false, length = 64)
    private String barcode;

    protected ProductBarcode() {
    }

    public ProductBarcode(UUID productId, String barcode) {
        this.productId = productId;
        this.barcode = barcode;
    }

    public UUID getProductId() {
        return productId;
    }

    public String getBarcode() {
        return barcode;
    }
}
