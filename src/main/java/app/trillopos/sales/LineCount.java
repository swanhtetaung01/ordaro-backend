package app.trillopos.sales;

import java.util.UUID;

/** How many lines one sale has, for a list that does not load them. */
public record LineCount(UUID saleId, long lines) {
}
