package app.trillopos.org;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import app.trillopos.shared.persistence.TenantEntity;

/**
 * A place that holds stock (spec §12 Locations, V1 shape): a {@code STORE} or a
 * {@code WAREHOUSE}. Only a store has registers, cashier shifts and receipt sequences — enforced
 * in the service (step 3), not in the schema. The UI still says "shop" for a store.
 */
@Entity
@Table(name = "location")
public class Location extends TenantEntity {

    /** Short code, unique per organization; first segment of every document number. */
    @Column(name = "code", nullable = false, length = 16)
    private String code;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    private LocationType type;

    @Column(name = "address", length = 500)
    private String address;

    @Column(name = "phone", length = 20)
    private String phone;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    protected Location() {
    }

    public Location(String code, String name, LocationType type) {
        this.code = code;
        this.name = name;
        this.type = type;
    }

    public boolean isStore() {
        return type == LocationType.STORE;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public LocationType getType() {
        return type;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
