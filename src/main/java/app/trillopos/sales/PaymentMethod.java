package app.trillopos.sales;

/** Several rows on one sale is a split payment. CREDIT closes the sale and opens a Receivable (step 5). */
public enum PaymentMethod {
    CASH,
    KBZ_PAY,
    WAVE_PAY,
    AYA_PAY,
    CB_PAY,
    BANK_TRANSFER,
    CREDIT,
    OTHER
}
