package app.ordaro.crm;

/** Column {@code customer.type}. Walk-ins are not customers; they are a null on the sale. */
public enum CustomerType {
    MEMBER,
    B2B
}
