package app.trillopos.inventory;

/** Required on STOCK_OUT and ADJUSTMENT, and on every reversal written by a void. */
public enum StockMovementReason {
    DAMAGED,
    EXPIRED,
    INTERNAL_USE,
    SAMPLE,
    THEFT,
    COUNT_CORRECTION,
    VOID_REVERSAL
}
