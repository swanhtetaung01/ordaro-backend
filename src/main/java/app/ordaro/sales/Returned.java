package app.ordaro.sales;

import java.math.BigDecimal;
import java.util.UUID;

/** How much of one sale line has already been returned. */
public record Returned(UUID saleLineId, BigDecimal quantity) {
}
