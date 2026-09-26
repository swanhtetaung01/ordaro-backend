package app.trillopos.sales;

/** Which list the unit price comes from. WHOLESALE falls back to retail when a product has no wholesale price. */
public enum PriceType {
    RETAIL,
    WHOLESALE
}
