package app.ordaro.inventory;

import java.math.BigDecimal;

import app.ordaro.shared.numbering.DocumentSequenceType;

public enum StockDocumentType {
    OPENING(DocumentSequenceType.OPN),
    STOCK_IN(DocumentSequenceType.GRN),
    STOCK_OUT(DocumentSequenceType.OUT),
    ADJUSTMENT(DocumentSequenceType.ADJ),
    TRANSFER(DocumentSequenceType.TFR);

    private final DocumentSequenceType sequence;

    StockDocumentType(DocumentSequenceType sequence) {
        this.sequence = sequence;
    }

    public DocumentSequenceType sequence() {
        return sequence;
    }

    /** Lines carry what was paid: OPENING, STOCK_IN, and a positive ADJUSTMENT. */
    public boolean requiresUnitCost(BigDecimal quantity) {
        return this == OPENING || this == STOCK_IN || (this == ADJUSTMENT && quantity.signum() > 0);
    }

    public boolean requiresReason() {
        return this == STOCK_OUT || this == ADJUSTMENT;
    }
}
