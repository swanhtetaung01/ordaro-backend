package app.ordaro.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import app.ordaro.sales.SaleCalculator.LineInput;
import app.ordaro.sales.SaleCalculator.Priced;
import app.ordaro.sales.SaleCalculator.PricedLine;
import app.ordaro.shared.web.ApiException;

/** Spec §9.2 step 4 and §6 line arithmetic, without a database. Two minor digits (MMK, THB). */
class SaleCalculatorTest {

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    private static LineInput line(String quantity, String unitPrice, String discount, String taxRate) {
        return new LineInput(UUID.randomUUID(), "Item", "SKU", d(quantity), d(unitPrice),
                discount == null ? null : d(discount), d(taxRate));
    }

    @Test
    void taxInclusiveLinesCarryTheirTaxInsideTheTotal() {
        Priced p = SaleCalculator.price(List.of(line("2", "1050", null, "5")), null, true, 2, null);
        PricedLine l = p.lines().getFirst();
        assertThat(l.lineTotal()).isEqualByComparingTo("2100");
        assertThat(l.taxAmount()).isEqualByComparingTo("100"); // 2100 × 5 / 105
        assertThat(p.totals().total()).isEqualByComparingTo("2100");
        assertThat(p.totals().subtotal()).isEqualByComparingTo("2100");
    }

    @Test
    void taxExclusiveLinesAddTheirTax() {
        Priced p = SaleCalculator.price(List.of(line("2", "1000", null, "5")), null, false, 2, null);
        assertThat(p.lines().getFirst().taxAmount()).isEqualByComparingTo("100");
        assertThat(p.lines().getFirst().lineTotal()).isEqualByComparingTo("2100");
        assertThat(p.totals().total()).isEqualByComparingTo("2100");
    }

    /** Σ of per-line rounded tax, never the header's: 3 × round(0.654) = 1.95, not round(1.963) = 1.96. */
    @Test
    void taxIsRoundedPerLineAndTheHeaderIsTheirSum() {
        Priced p = SaleCalculator.price(List.of(line("1", "10", null, "7"), line("1", "10", null, "7"),
                line("1", "10", null, "7")), null, true, 2, null);
        assertThat(p.lines()).extracting(PricedLine::taxAmount).allSatisfy(t -> assertThat(t).isEqualByComparingTo("0.65"));
        assertThat(p.totals().taxAmount()).isEqualByComparingTo("1.95");
    }

    @Test
    void theCartDiscountIsAllocatedProRataWithTheRemainderOnTheLastLine() {
        Priced p = SaleCalculator.price(List.of(line("1", "100", null, "0"), line("1", "200", null, "0"),
                line("1", "300", null, "0")), d("100"), true, 2, null);
        assertThat(p.lines()).extracting(l -> l.cartDiscountAllocated().toPlainString())
                .containsExactly("16.67", "33.33", "50.00");
        assertThat(p.totals().cartDiscountAmount()).isEqualByComparingTo("100");
        assertThat(p.totals().total()).isEqualByComparingTo("500");
    }

    @Test
    void lineDiscountsComeBeforeTheCartDiscount() {
        // bases after line discounts: 90 and 100; cart 19 → 9.00 and 10.00
        Priced p = SaleCalculator.price(List.of(line("1", "100", "10", "0"), line("1", "100", null, "0")),
                d("19"), true, 2, null);
        assertThat(p.lines()).extracting(l -> l.cartDiscountAllocated().toPlainString()).containsExactly("9.00", "10.00");
        assertThat(p.totals().lineDiscountTotal()).isEqualByComparingTo("10");
        assertThat(p.totals().total()).isEqualByComparingTo("171");
    }

    @Test
    void theTotalRoundsToTheNearestStepAndKeepsTheDifference() {
        Priced p = SaleCalculator.price(List.of(line("1", "12430", null, "5")), null, true, 2, d("50"));
        assertThat(p.totals().total()).isEqualByComparingTo("12450");
        assertThat(p.totals().roundingAdjustment()).isEqualByComparingTo("20");
        // the line keeps its own total and tax; only the header rounds
        assertThat(p.lines().getFirst().lineTotal()).isEqualByComparingTo("12430");
    }

    @Test
    void fractionalQuantitiesRoundTheLineAmountHalfUp() {
        // 1.5 kg × 4092.31 = 6138.465 → 6138.47
        Priced p = SaleCalculator.price(List.of(line("1.5", "4092.31", null, "0")), null, true, 2, null);
        assertThat(p.lines().getFirst().lineTotal()).isEqualTo(d("6138.47"));
    }

    @Test
    void untaxedProductsCarryNoTax() {
        Priced p = SaleCalculator.price(List.of(line("3", "500", null, "0")), null, true, 2, null);
        assertThat(p.totals().taxAmount()).isEqualByComparingTo("0");
    }

    /** Rounding the first five lines up would leave the last one −0.02; units move back instead. */
    @Test
    void theAllocationNeverDiscountsALineBelowZero() {
        List<LineInput> tiny = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tiny.add(line("1", "0.01", null, "0"));
        }
        Priced p = SaleCalculator.price(tiny, d("0.03"), true, 2, null);
        assertAllocationSound(p, d("0.03"));
    }

    @Test
    void anyBasketAllocatesExactlyTheCartDiscountWithinEachLine() {
        Random random = new Random(7);
        for (int run = 0; run < 500; run++) {
            List<LineInput> lines = new ArrayList<>();
            BigDecimal basket = BigDecimal.ZERO;
            for (int i = 0; i < 1 + random.nextInt(8); i++) {
                BigDecimal price = new BigDecimal(1 + random.nextInt(5000)).movePointLeft(2);
                lines.add(new LineInput(UUID.randomUUID(), "x", "x", BigDecimal.ONE, price, null, BigDecimal.ZERO));
                basket = basket.add(price);
            }
            BigDecimal cart = new BigDecimal(random.nextInt(basket.movePointRight(2).intValue() + 1)).movePointLeft(2);
            assertAllocationSound(SaleCalculator.price(lines, cart, true, 2, null), cart);
        }
    }

    @Test
    void impossibleDiscountsAndAmountsAreRefused() {
        assertCode(() -> SaleCalculator.price(List.of(line("1", "100", "101", "0")), null, true, 2, null),
                "invalid_discount");
        assertCode(() -> SaleCalculator.price(List.of(line("1", "100", null, "0")), d("100.01"), true, 2, null),
                "invalid_discount");
        assertCode(() -> SaleCalculator.price(List.of(line("1", "100", "0.001", "0")), null, true, 2, null),
                "invalid_amount");
        assertCode(() -> SaleCalculator.price(List.of(), null, true, 2, null), "empty_sale");
    }

    private static void assertAllocationSound(Priced p, BigDecimal cart) {
        BigDecimal sum = BigDecimal.ZERO;
        for (PricedLine l : p.lines()) {
            BigDecimal base = l.quantity().multiply(l.unitPrice()).subtract(l.discountAmount());
            assertThat(l.cartDiscountAllocated()).isBetween(BigDecimal.ZERO, base);
            sum = sum.add(l.cartDiscountAllocated());
        }
        assertThat(sum).isEqualByComparingTo(cart);
    }

    private static void assertCode(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo(code));
    }
}
