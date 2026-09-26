package app.trillopos.org;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import app.trillopos.shared.persistence.BaseEntity;

/** The tenant root (spec §3). Extends {@link BaseEntity}, not a tenant entity: it is the tenant. */
@Entity
@Table(name = "organization")
public class Organization extends BaseEntity {

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    /** Public-link identifier only (`/s/{slug}`), not a subdomain. */
    @Column(name = "slug", nullable = false, length = 64)
    private String slug;

    @Enumerated(EnumType.STRING)
    @Column(name = "business_type", nullable = false, length = 32)
    private BusinessType businessType;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Column(name = "tax_inclusive_pricing", nullable = false)
    private boolean taxInclusivePricing;

    @Column(name = "default_tax_rate", nullable = false, precision = 7, scale = 4)
    private BigDecimal defaultTaxRate;

    @Column(name = "round_total_to_nearest", precision = 19, scale = 4)
    private BigDecimal roundTotalToNearest;

    @Column(name = "allow_negative_stock", nullable = false)
    private boolean allowNegativeStock;

    @Enumerated(EnumType.STRING)
    @Column(name = "costing_method", nullable = false, length = 32)
    private CostingMethod costingMethod;

    /** Where "today" starts and the receipt-sequence year rolls over. Asia/Yangon is UTC+6:30. */
    @Column(name = "timezone", nullable = false, length = 64)
    private String timezone;

    /** What a new customer's credit limit starts at (V10); 0 means no credit until the owner sets one. */
    @Column(name = "default_credit_limit", nullable = false, precision = 19, scale = 4)
    private BigDecimal defaultCreditLimit = BigDecimal.ZERO;

    @Column(name = "default_credit_term_days", nullable = false)
    private int defaultCreditTermDays;

    protected Organization() {
    }

    /** A new business with Myanmar retail defaults (spec §12, sign-up). */
    public Organization(UUID id, String name, String slug) {
        assignId(id);
        this.name = name;
        this.slug = slug;
        this.businessType = BusinessType.RETAIL;
        this.currencyCode = "MMK";
        this.taxInclusivePricing = true;
        this.defaultTaxRate = new BigDecimal("5.0000");
        this.allowNegativeStock = false;
        this.costingMethod = CostingMethod.WEIGHTED_AVERAGE;
        this.timezone = "Asia/Yangon";
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public BusinessType getBusinessType() {
        return businessType;
    }

    public void setBusinessType(BusinessType businessType) {
        this.businessType = businessType;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public void setCurrencyCode(String currencyCode) {
        this.currencyCode = currencyCode;
    }

    public boolean isTaxInclusivePricing() {
        return taxInclusivePricing;
    }

    public void setTaxInclusivePricing(boolean taxInclusivePricing) {
        this.taxInclusivePricing = taxInclusivePricing;
    }

    public BigDecimal getDefaultTaxRate() {
        return defaultTaxRate;
    }

    public void setDefaultTaxRate(BigDecimal defaultTaxRate) {
        this.defaultTaxRate = defaultTaxRate;
    }

    public BigDecimal getRoundTotalToNearest() {
        return roundTotalToNearest;
    }

    public void setRoundTotalToNearest(BigDecimal roundTotalToNearest) {
        this.roundTotalToNearest = roundTotalToNearest;
    }

    public boolean isAllowNegativeStock() {
        return allowNegativeStock;
    }

    public void setAllowNegativeStock(boolean allowNegativeStock) {
        this.allowNegativeStock = allowNegativeStock;
    }

    public CostingMethod getCostingMethod() {
        return costingMethod;
    }

    public BigDecimal getDefaultCreditLimit() {
        return defaultCreditLimit;
    }

    public void setDefaultCreditLimit(BigDecimal defaultCreditLimit) {
        this.defaultCreditLimit = defaultCreditLimit;
    }

    public int getDefaultCreditTermDays() {
        return defaultCreditTermDays;
    }

    public void setDefaultCreditTermDays(int defaultCreditTermDays) {
        this.defaultCreditTermDays = defaultCreditTermDays;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }
}
