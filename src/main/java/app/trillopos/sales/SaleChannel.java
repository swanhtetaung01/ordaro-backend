package app.trillopos.sales;

/** POS sales belong to a cashier shift; ONLINE and ORDER sales have no register. */
public enum SaleChannel {
    POS,
    ONLINE,
    ORDER
}
