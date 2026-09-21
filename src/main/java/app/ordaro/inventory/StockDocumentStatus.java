package app.ordaro.inventory;

/** A DRAFT writes no movements. Posting is one-way; a void writes reversing movements. */
public enum StockDocumentStatus {
    DRAFT,
    POSTED,
    VOID
}
