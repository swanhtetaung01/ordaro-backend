package app.ordaro.finance;

/** Column {@code payable.status}. CANCELLED is what voiding an unpaid stock-in sets (spec §7). */
public enum PayableStatus {
    OPEN,
    PARTIALLY_SETTLED,
    SETTLED,
    CANCELLED
}
