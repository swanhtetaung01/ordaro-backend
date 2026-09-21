package app.ordaro.sales;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import app.ordaro.shared.web.ApiException;

/**
 * The money arithmetic of a sale (spec §9.2 step 4 and §6 <em>Line arithmetic</em>), with no
 * database. In order:
 * <ol>
 * <li>each line's amount, {@code quantity × unitPrice}, rounded to the currency's minor unit;</li>
 * <li>line discounts;</li>
 * <li>the cart discount allocated pro-rata by each line's post-line-discount amount, rounded to
 * the minor unit, the remainder on the last line — Σ allocated equals the cart discount exactly;</li>
 * <li>tax per line, rounded per line; inclusive: {@code lineTotal = net},
 * {@code tax = net − net / (1 + rate)}; exclusive: {@code tax = net × rate},
 * {@code lineTotal = net + tax};</li>
 * <li>Σ lineTotal rounded to {@code roundTotalToNearest} when set, the difference kept in
 * {@code roundingAdjustment}.</li>
 * </ol>
 * The header's tax is Σ line tax, never computed on the header.
 */
public final class SaleCalculator {

    /** @param taxRate a percentage, e.g. 5.0000 for 5 %; 0 when the product is not taxable */
    public record LineInput(UUID productId, String productName, String sku, BigDecimal quantity,
            BigDecimal unitPrice, BigDecimal discountAmount, BigDecimal taxRate) {
    }

    public record PricedLine(UUID productId, String productName, String sku, BigDecimal quantity,
            BigDecimal unitPrice, BigDecimal discountAmount, BigDecimal cartDiscountAllocated, BigDecimal taxRate,
            BigDecimal taxAmount, BigDecimal lineTotal) {
    }

    public record Totals(BigDecimal subtotal, BigDecimal lineDiscountTotal, BigDecimal cartDiscountAmount,
            BigDecimal taxAmount, BigDecimal roundingAdjustment, BigDecimal total) {
    }

    public record Priced(List<PricedLine> lines, Totals totals) {
    }

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private SaleCalculator() {
    }

    /**
     * @param minorDigits      decimals of the currency's minor unit (MMK, THB: 2)
     * @param roundToNearest   {@code Organization.roundTotalToNearest}, or null
     */
    public static Priced price(List<LineInput> inputs, BigDecimal cartDiscount, boolean taxInclusive,
            int minorDigits, BigDecimal roundToNearest) {
        if (inputs.isEmpty()) {
            throw ApiException.badRequest("empty_sale", "a sale has at least one line");
        }
        BigDecimal cart = money(cartDiscount == null ? BigDecimal.ZERO : cartDiscount, minorDigits, "cartDiscountAmount");
        if (cart.signum() < 0) {
            throw ApiException.badRequest("invalid_discount", "a cart discount cannot be negative");
        }

        int n = inputs.size();
        BigDecimal[] amount = new BigDecimal[n];
        BigDecimal[] base = new BigDecimal[n];
        BigDecimal baseSum = BigDecimal.ZERO;
        for (int i = 0; i < n; i++) {
            LineInput line = inputs.get(i);
            amount[i] = round(line.quantity().multiply(line.unitPrice()), minorDigits);
            BigDecimal discount = money(line.discountAmount() == null ? BigDecimal.ZERO : line.discountAmount(),
                    minorDigits, "discountAmount");
            if (discount.signum() < 0 || discount.compareTo(amount[i]) > 0) {
                throw ApiException.badRequest("invalid_discount",
                        "a line discount is between zero and the line amount");
            }
            base[i] = amount[i].subtract(discount);
            baseSum = baseSum.add(base[i]);
        }
        if (cart.compareTo(baseSum) > 0) {
            throw ApiException.badRequest("invalid_discount", "the cart discount exceeds the basket");
        }

        BigDecimal[] allocated = allocate(cart, base, baseSum, minorDigits);

        List<PricedLine> lines = new ArrayList<>(n);
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal lineDiscounts = BigDecimal.ZERO;
        BigDecimal taxTotal = BigDecimal.ZERO;
        BigDecimal lineTotals = BigDecimal.ZERO;
        for (int i = 0; i < n; i++) {
            LineInput line = inputs.get(i);
            BigDecimal net = base[i].subtract(allocated[i]);
            BigDecimal rate = line.taxRate();
            BigDecimal tax;
            BigDecimal lineTotal;
            if (taxInclusive) {
                // net − net / (1 + r) = net × rate / (100 + rate), with rate as a percentage
                tax = round(net.multiply(rate).divide(HUNDRED.add(rate), minorDigits + 6, RoundingMode.HALF_UP),
                        minorDigits);
                lineTotal = net;
            } else {
                tax = round(net.multiply(rate).divide(HUNDRED), minorDigits);
                lineTotal = net.add(tax);
            }
            BigDecimal discount = amount[i].subtract(base[i]);
            lines.add(new PricedLine(line.productId(), line.productName(), line.sku(), line.quantity(),
                    line.unitPrice(), discount, allocated[i], rate, tax, lineTotal));
            subtotal = subtotal.add(amount[i]);
            lineDiscounts = lineDiscounts.add(discount);
            taxTotal = taxTotal.add(tax);
            lineTotals = lineTotals.add(lineTotal);
        }

        BigDecimal total = lineTotals;
        if (roundToNearest != null && roundToNearest.signum() > 0) {
            total = lineTotals.divide(roundToNearest, 0, RoundingMode.HALF_UP).multiply(roundToNearest);
        }
        return new Priced(lines, new Totals(subtotal, lineDiscounts, cart, taxTotal, total.subtract(lineTotals),
                total));
    }

    /**
     * Pro-rata by base, rounded to the minor unit; the remainder goes on the last line. If the
     * rounding of earlier lines left the last line unable to absorb it (the remainder would be
     * negative or larger than its base), the excess moves one minor unit at a time to lines
     * that can take it, from the end — so no line is ever discounted below zero.
     */
    private static BigDecimal[] allocate(BigDecimal cart, BigDecimal[] base, BigDecimal baseSum, int minorDigits) {
        int n = base.length;
        BigDecimal[] allocated = new BigDecimal[n];
        BigDecimal given = BigDecimal.ZERO;
        for (int i = 0; i < n - 1; i++) {
            allocated[i] = baseSum.signum() == 0 ? BigDecimal.ZERO.setScale(minorDigits)
                    : cart.multiply(base[i]).divide(baseSum, minorDigits, RoundingMode.HALF_UP);
            given = given.add(allocated[i]);
        }
        allocated[n - 1] = cart.subtract(given);

        BigDecimal unit = BigDecimal.ONE.movePointLeft(minorDigits);
        int last = n - 1;
        for (int i = last - 1; i >= 0; i--) {
            // earlier lines rounded up too far: take units back from them
            while (allocated[last].signum() < 0 && allocated[i].compareTo(unit) >= 0) {
                allocated[i] = allocated[i].subtract(unit);
                allocated[last] = allocated[last].add(unit);
            }
            // earlier lines rounded down too far: give them the units the last line cannot hold
            while (allocated[last].compareTo(base[last]) > 0 && allocated[i].add(unit).compareTo(base[i]) <= 0) {
                allocated[i] = allocated[i].add(unit);
                allocated[last] = allocated[last].subtract(unit);
            }
        }
        return allocated;
    }

    private static BigDecimal money(BigDecimal value, int minorDigits, String field) {
        if (value.stripTrailingZeros().scale() > minorDigits) {
            throw ApiException.badRequest("invalid_amount", field + " has more decimals than the currency");
        }
        return value.setScale(minorDigits, RoundingMode.UNNECESSARY);
    }

    private static BigDecimal round(BigDecimal value, int minorDigits) {
        return value.setScale(minorDigits, RoundingMode.HALF_UP);
    }
}
