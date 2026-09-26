package app.trillopos.catalog;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import app.trillopos.shared.persistence.TenantEntity;

/** Per-location settings for a product (was ShopProduct). Unique on (location, product). */
@Entity
@Table(name = "location_product")
public class LocationProduct extends TenantEntity {

    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    /** Null falls back to {@code Product.reorderPoint}. */
    @Column(name = "reorder_point")
    private Integer reorderPoint;

    @Column(name = "shelf_location", length = 32)
    private String shelfLocation;

    protected LocationProduct() {
    }

    public LocationProduct(UUID locationId, UUID productId) {
        this.locationId = locationId;
        this.productId = productId;
    }

    public UUID getLocationId() {
        return locationId;
    }

    public UUID getProductId() {
        return productId;
    }

    public Integer getReorderPoint() {
        return reorderPoint;
    }

    public void setReorderPoint(Integer reorderPoint) {
        this.reorderPoint = reorderPoint;
    }

    public String getShelfLocation() {
        return shelfLocation;
    }

    public void setShelfLocation(String shelfLocation) {
        this.shelfLocation = shelfLocation;
    }
}
