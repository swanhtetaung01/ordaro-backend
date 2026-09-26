package app.trillopos.finance;

/** Column {@code receivable.source_type}: what opened the debt. MANUAL is a debt carried over from before TrilloPOS. */
public enum ReceivableSourceType {
    SALE,
    ORDER,
    MANUAL
}
