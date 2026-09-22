package app.ordaro.reports;

import static app.ordaro.inventory.StockPostingTest.stockIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import app.ordaro.catalog.ProductService;
import app.ordaro.catalog.ProductService.ProductCommand;
import app.ordaro.catalog.ProductUnit;
import app.ordaro.finance.ExpenseCategory;
import app.ordaro.finance.ExpenseCategoryRepository;
import app.ordaro.finance.ExpenseMethod;
import app.ordaro.finance.ExpenseService;
import app.ordaro.finance.ExpenseService.ExpenseCommand;
import app.ordaro.inventory.StockDocumentService;
import app.ordaro.org.LocationType;
import app.ordaro.reports.ReportService.DayTotal;
import app.ordaro.reports.ReportService.LowStockRow;
import app.ordaro.reports.ReportService.ProductTotal;
import app.ordaro.reports.ReportService.Range;
import app.ordaro.reports.ReportService.Summary;
import app.ordaro.sales.PaymentMethod;
import app.ordaro.sales.ReturnService;
import app.ordaro.sales.ReturnService.ReturnCommand;
import app.ordaro.sales.SaleChannel;
import app.ordaro.sales.SaleCheckout;
import app.ordaro.sales.SaleService;
import app.ordaro.sales.SaleService.CartCommand;
import app.ordaro.sales.SaleService.CompletionResult;
import app.ordaro.sales.SaleService.LineCommand;
import app.ordaro.sales.SaleService.PaymentCommand;
import app.ordaro.sales.ShiftService;
import app.ordaro.shared.web.ApiException;
import app.ordaro.support.IntegrationTest;
import app.ordaro.support.Shops;
import app.ordaro.support.Shops.Shop;

/** Step 6a: the dashboard's numbers, against rows whose arithmetic is worked out by hand. */
class ReportTest extends IntegrationTest {

    private static final ZoneId YANGON = ZoneId.of("Asia/Yangon");

    @Autowired
    Shops shops;

    @Autowired
    ReportService reports;

    @Autowired
    SaleCheckout checkout;

    @Autowired
    ReturnService returns;

    @Autowired
    ShiftService shiftService;

    @Autowired
    ExpenseService expenses;

    @Autowired
    ExpenseCategoryRepository categories;

    @Autowired
    StockDocumentService documents;

    @Autowired
    ProductService productService;

    Shop shop;
    UUID main;
    UUID oil;
    UUID shift;

    /** Oil: 20 on hand at 600, sells at 1000 with 5 % inclusive tax, reorder point 5. */
    @BeforeEach
    void openShop() {
        shop = shops.newShop();
        main = shop.mainLocationId();
        oil = shops.as(shop, () -> productService.create(new ProductCommand(null, null, "Cooking Oil 1L", null, null,
                ProductUnit.PIECE, null, null, new BigDecimal("1000"), null, null, null, 5, null, null, null, null,
                null)).getId());
        shops.run(shop, () -> documents.createAndPost(stockIn(main, oil, "20", "600")));
        shift = shops.as(shop, () -> shiftService.open(main, new BigDecimal("50000")).getId());
    }

    /**
     * Sell 5 (5,000 charged, 238.10 tax, 4,761.90 net, 3,000 cost), return 2 — one restocked, one
     * damaged — and pay 1,500 of rent. Gross profit: 1,761.90 − (1,904.76 − 600) = 457.14.
     */
    @Test
    void theSummaryAddsUpTheSalesAndTheReturnsSides() {
        CompletionResult sold = checkout("s1", cart(oil, "5"), cash("5000"));
        ret(sold, "1", true);
        ret(sold, "1", false);
        UUID rent = shops.as(shop, () -> categories.save(new ExpenseCategory("Rent")).getId());
        shops.run(shop, () -> expenses.record(new ExpenseCommand(rent, main, null, new BigDecimal("1500"),
                ExpenseMethod.BANK_TRANSFER, null, "October", null)));

        Summary summary = summary(null);
        assertThat(summary.salesCount()).isEqualTo(1);
        assertThat(summary.grossSales()).isEqualByComparingTo("5000");
        assertThat(summary.taxCollected()).isEqualByComparingTo("238.10");
        assertThat(summary.netRevenue()).isEqualByComparingTo("4761.90");
        assertThat(summary.cogs()).isEqualByComparingTo("3000");
        assertThat(summary.returnCount()).isEqualTo(2);
        assertThat(summary.refundAmount()).isEqualByComparingTo("2000");
        assertThat(summary.revenueReversed()).isEqualByComparingTo("1904.76");
        assertThat(summary.cogsReversed()).isEqualByComparingTo("600"); // only the restocked unit
        assertThat(summary.grossProfit()).isEqualByComparingTo("457.14");
        assertThat(summary.expenses()).isEqualByComparingTo("1500");
        assertThat(summary.from()).isEqualTo(LocalDate.now(YANGON));
    }

    @Test
    void anEmptyRangeIsAllZeroAndABackwardsRangeIsRefused() {
        Summary empty = shops.as(shop, () -> reports.summary(new Range(LocalDate.of(2020, 1, 1),
                LocalDate.of(2020, 1, 31)), null));
        assertThat(empty.salesCount()).isZero();
        assertThat(empty.grossSales()).isEqualByComparingTo("0");
        assertThat(empty.grossProfit()).isEqualByComparingTo("0");
        assertThat(empty.receivablesOutstanding()).isEqualByComparingTo("0");

        assertThatThrownBy(() -> shops.as(shop, () -> reports.range(LocalDate.now(), LocalDate.now().minusDays(1))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("invalid_range"));
    }

    @Test
    void creditSalesAndDebtsShowInTheirOwnFigures() {
        checkout("s1", cart(oil, "2"), cash("2000"));
        Summary summary = summary(null);
        assertThat(summary.receivablesOutstanding()).isEqualByComparingTo("0");
        assertThat(summary.payablesOutstanding()).isEqualByComparingTo("0");
        // a debt is live, not of the range: a receivable dated long ago still counts today
        assertThat(summary.grossSales()).isEqualByComparingTo("2000");
    }

    @Test
    void theChartHasOneRowPerDayWithSalesOrReturns() {
        CompletionResult sold = checkout("s1", cart(oil, "3"), cash("3000"));
        ret(sold, "1", true);

        List<DayTotal> days = shops.as(shop, () -> reports.salesByDay(reports.range(null, null), null));
        assertThat(days).hasSize(1);
        DayTotal today = days.getFirst();
        assertThat(today.day()).isEqualTo(LocalDate.now(YANGON));
        assertThat(today.salesCount()).isEqualTo(1);
        assertThat(today.grossSales()).isEqualByComparingTo("3000");
        assertThat(today.netRevenue()).isEqualByComparingTo("2857.14");
        // 2857.14 − 1800 cost − (952.38 − 600) = 704.76
        assertThat(today.grossProfit()).isEqualByComparingTo("704.76");
        assertThat(shops.as(shop, () -> reports.salesByDay(new Range(LocalDate.of(2020, 1, 1),
                LocalDate.of(2020, 1, 2)), null))).isEmpty();
    }

    @Test
    void topProductsRanksByNetRevenueAndCarriesTheMargin() {
        UUID rice = shops.as(shop, () -> productService.create(new ProductCommand(null, null, "Rice", null, null,
                ProductUnit.BAG, null, null, new BigDecimal("20000"), null, false, null, null, null, null, null,
                null, null)).getId());
        shops.run(shop, () -> documents.createAndPost(stockIn(main, rice, "10", "17000")));
        checkout("s1", cart(oil, "3"), cash("3000"));
        checkout("s2", cart(rice, "2"), cash("40000"));

        List<ProductTotal> top = shops.as(shop, () -> reports.topProducts(reports.range(null, null), null, 10));
        assertThat(top).extracting(ProductTotal::productName).containsExactly("Rice", "Cooking Oil 1L");
        assertThat(top.getFirst().quantity()).isEqualByComparingTo("2");
        assertThat(top.getFirst().netRevenue()).isEqualByComparingTo("40000"); // untaxed product
        assertThat(top.getFirst().grossProfit()).isEqualByComparingTo("6000");
        assertThat(top.getLast().grossProfit()).isEqualByComparingTo("1057.14"); // 2857.14 − 1800
    }

    @Test
    void lowStockUsesTheLocationsReorderPointWhenItHasOne() {
        checkout("s1", cart(oil, "16"), cash("16000")); // 4 left, reorder point 5
        List<LowStockRow> low = shops.as(shop, () -> reports.lowStock(null, 20));
        assertThat(low).singleElement().satisfies(row -> {
            assertThat(row.productName()).isEqualTo("Cooking Oil 1L");
            assertThat(row.quantity()).isEqualByComparingTo("4");
            assertThat(row.reorderPoint()).isEqualTo(5);
            assertThat(row.locationCode()).isEqualTo("MAIN");
        });

        shops.run(shop, () -> productService.setLocationSettings(oil, main, 2, null));
        assertThat(shops.as(shop, () -> reports.lowStock(null, 20))).isEmpty();
    }

    @Test
    void thePaymentMixSplitsCashFromWallets() {
        checkout("s1", cart(oil, "2"), cash("2000"));
        checkout("s2", cart(oil, "1"), List.of(new PaymentCommand(PaymentMethod.KBZ_PAY, new BigDecimal("1000"),
                null, "K-1")));
        assertThat(shops.as(shop, () -> reports.paymentMix(reports.range(null, null), null)))
                .extracting(r -> r.method() + " " + r.amount().stripTrailingZeros().toPlainString())
                .containsExactly("CASH 2000", "KBZ_PAY 1000");
    }

    @Test
    void figuresAreScopedToTheLocationAndTheTenant() {
        UUID branch = shops.location(shop, "B2", LocationType.STORE);
        checkout("s1", cart(oil, "2"), cash("2000"));

        assertThat(summary(main).grossSales()).isEqualByComparingTo("2000");
        assertThat(summary(branch).grossSales()).isEqualByComparingTo("0");
        // a session that may only act at the branch reports on the branch, whatever it asks for
        assertThat(shops.asScoped(shop, branch, () -> reports.summary(reports.range(null, null), null)).grossSales())
                .isEqualByComparingTo("0");

        Shop other = shops.newShop();
        assertThat(shops.as(other, () -> reports.summary(reports.range(null, null), null)).grossSales())
                .isEqualByComparingTo("0");
        assertThat(shops.as(other, () -> reports.lowStock(null, 20))).isEmpty();
    }

    // ───────────────────────────────────────────────────────────── helpers

    private Summary summary(UUID locationId) {
        return shops.as(shop, () -> reports.summary(reports.range(null, null), locationId));
    }

    private CompletionResult checkout(String key, CartCommand cart, List<PaymentCommand> payments) {
        return shops.inTenant(shop, () -> checkout.checkout(key, cart, payments));
    }

    private CartCommand cart(UUID product, String quantity) {
        return new CartCommand(main, SaleChannel.POS, shift, null, null,
                List.of(new LineCommand(product, new BigDecimal(quantity), null)));
    }

    private void ret(CompletionResult sold, String quantity, boolean restock) {
        shops.run(shop, () -> returns.create(new ReturnCommand(sold.details().sale().getId(), main, null,
                PaymentMethod.CASH, null, null, List.of(new ReturnService.LineCommand(
                        sold.details().lines().getFirst().getId(), new BigDecimal(quantity), restock)), null)));
    }

    private static List<PaymentCommand> cash(String amount) {
        return List.of(new PaymentCommand(PaymentMethod.CASH, new BigDecimal(amount), null, null));
    }
}
