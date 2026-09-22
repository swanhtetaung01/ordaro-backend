package app.ordaro.finance;

import java.math.BigDecimal;
import java.util.UUID;

/** Σ outstanding receivables of one customer. */
public record Owed(UUID customerId, BigDecimal outstanding) {
}
