package app.trillopos.inventory;

/** Column {@code stock_movement.type}. The sign each type carries is enforced by a CHECK. */
public enum StockMovementType {
    OPENING,
    STOCK_IN,
    STOCK_OUT,
    SALE,
    SALE_RETURN,
    TRANSFER_IN,
    TRANSFER_OUT,
    ADJUSTMENT
}
