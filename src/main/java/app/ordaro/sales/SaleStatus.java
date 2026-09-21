package app.ordaro.sales;

/** VOID only from DRAFT or HELD; a COMPLETED sale is undone by a Return (step 5), which moves it to PARTIALLY_REFUNDED or REFUNDED. */
public enum SaleStatus {
    DRAFT,
    HELD,
    COMPLETED,
    VOID,
    PARTIALLY_REFUNDED,
    REFUNDED
}
