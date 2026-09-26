package app.trillopos.finance;

/** Column {@code expense.method}: the Payment enum without CREDIT (spec §7) — an expense is paid, not credited. */
public enum ExpenseMethod {
    CASH,
    KBZ_PAY,
    WAVE_PAY,
    AYA_PAY,
    CB_PAY,
    BANK_TRANSFER,
    OTHER
}
