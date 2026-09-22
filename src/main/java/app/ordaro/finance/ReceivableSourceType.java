package app.ordaro.finance;

/** Column {@code receivable.source_type}: what opened the debt. MANUAL is a debt carried over from before Ordaro. */
public enum ReceivableSourceType {
    SALE,
    ORDER,
    MANUAL
}
