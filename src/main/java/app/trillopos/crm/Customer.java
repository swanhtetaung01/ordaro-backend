package app.trillopos.crm;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import app.trillopos.sales.PriceType;
import app.trillopos.shared.persistence.TenantEntity;

/**
 * A named buyer (spec §8). The register's customer selector reads {@code defaultPriceType} to
 * switch the product grid; a CREDIT payment is checked against {@code creditLimit} with this row
 * locked, so two registers cannot both pass the check.
 */
@Entity
@Table(name = "customer")
public class Customer extends TenantEntity {

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    /** E.164, unique per organization. */
    @Column(name = "phone", length = 20)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    private CustomerType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "default_price_type", nullable = false, length = 32)
    private PriceType defaultPriceType;

    /** 0 means no credit. */
    @Column(name = "credit_limit", nullable = false, precision = 19, scale = 4)
    private BigDecimal creditLimit = BigDecimal.ZERO;

    /** Sets a receivable's due date. */
    @Column(name = "credit_term_days", nullable = false)
    private int creditTermDays;

    /** A plain counter in v1 (spec §10). */
    @Column(name = "loyalty_points", nullable = false)
    private int loyaltyPoints;

    @Column(name = "address", length = 500)
    private String address;

    @Column(name = "note", length = 500)
    private String note;

    protected Customer() {
    }

    public Customer(String name, CustomerType type, PriceType defaultPriceType) {
        this.name = name;
        this.type = type;
        this.defaultPriceType = defaultPriceType;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public CustomerType getType() {
        return type;
    }

    public void setType(CustomerType type) {
        this.type = type;
    }

    public PriceType getDefaultPriceType() {
        return defaultPriceType;
    }

    public void setDefaultPriceType(PriceType defaultPriceType) {
        this.defaultPriceType = defaultPriceType;
    }

    public BigDecimal getCreditLimit() {
        return creditLimit;
    }

    public void setCreditLimit(BigDecimal creditLimit) {
        this.creditLimit = creditLimit;
    }

    public int getCreditTermDays() {
        return creditTermDays;
    }

    public void setCreditTermDays(int creditTermDays) {
        this.creditTermDays = creditTermDays;
    }

    public int getLoyaltyPoints() {
        return loyaltyPoints;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
