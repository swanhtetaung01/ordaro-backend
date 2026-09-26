package app.trillopos.finance;

/** Column {@code receivable.status}. OVERDUE is a query on the due date, never a status. */
public enum ReceivableStatus {
    OPEN,
    PARTIALLY_SETTLED,
    SETTLED,
    WRITTEN_OFF
}
