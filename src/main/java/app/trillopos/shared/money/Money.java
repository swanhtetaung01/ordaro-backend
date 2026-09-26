package app.trillopos.shared.money;

import java.math.BigDecimal;
import java.util.Currency;

import app.trillopos.shared.web.ApiException;

/** Amounts of money are {@link BigDecimal}, positive where a document says so, in the currency's minor unit. */
public final class Money {

    private Money() {
    }

    /** MMK and THB both have 2. */
    public static int minorDigits(String currencyCode) {
        return Currency.getInstance(currencyCode).getDefaultFractionDigits();
    }

    /** A positive amount with no more decimals than the currency has. */
    public static BigDecimal requirePositive(BigDecimal amount, int minorDigits, String field) {
        if (amount == null || amount.signum() <= 0 || amount.stripTrailingZeros().scale() > minorDigits) {
            throw ApiException.badRequest("invalid_amount",
                    field + " must be positive with at most " + minorDigits + " decimals");
        }
        return amount;
    }
}
