package app.trillopos.sales;

import static app.trillopos.inventory.StockPostingTest.stockIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;

import app.trillopos.catalog.ProductService;
import app.trillopos.catalog.ProductService.ProductCommand;
import app.trillopos.catalog.ProductUnit;
import app.trillopos.inventory.StockBalance;
import app.trillopos.inventory.StockBalanceRebuilder;
import app.trillopos.inventory.StockBalanceRepository;
import app.trillopos.inventory.StockDocumentService;
import app.trillopos.inventory.StockMovement;
import app.trillopos.inventory.StockMovementRepository;
import app.trillopos.inventory.StockMovementType;
import app.trillopos.org.LocationType;
import app.trillopos.org.OrganizationRepository;
import app.trillopos.sales.SaleService.CartCommand;
import app.trillopos.sales.SaleService.CompletionResult;
import app.trillopos.sales.SaleService.LineCommand;
import app.trillopos.sales.SaleService.PaymentCommand;
import app.trillopos.sales.SaleService.SaleDetails;
import app.trillopos.shared.web.ApiException;
import app.trillopos.support.IntegrationTest;
import app.trillopos.support.Shops;
import app.trillopos.support.Shops.Shop;

/** Spec §9.2 — the completion transaction — through the real services and ledger. */
class SaleCompletionTest extends IntegrationTest {

    @Autowired
    Shops shops;

    @Autowired
    SaleService sales;

    @Autowired
    SaleCheckout checkout;

    @Autowired
    ShiftService shiftService;

    @Autowired
    StockDocumentService documents;

    @Autowired
    ProductService productService;

    @Autowired
    StockBalanceRepository balances;

    @Autowired
    StockMovementRepository movements;

    @Autowired
    StockBalanceRebuilder rebuilder;

    @Autowired
    OrganizationRepository organizations;

    Shop shop;
    UUID main;
    UUID oil;
    UUID shift;

    /** A shop with 10 oil at 600 on hand (retail 1000, 5 % inclusive tax) and an open shift. */
    @BeforeEach
    void openShop() {
        shop = shops.newShop();
        main = shop.mainLocationId();
        oil = shops.product(shop, "Cooking Oil 1L");
        shops.run(shop, () -> documents.createAndPost(stockIn(main, oil, "10", "600")));
        shift = shops.as(shop, () -> shiftService.open(main, new BigDecimal("50000")).getId());
    }

    @Test
    void aSaleCompletesThroughTheLedgerAndSnapshotsTheCost() {
        CompletionResult result = checkout("k-1", cart(oil, "3"), cash("3000", "5000"));
        assertThat(result.replayed()).isFalse();
        Sale sale = result.details().sale();

        assertThat(sale.getStatus()).isEqualTo(SaleStatus.COMPLETED);
        assertThat(sale.getReceiptNumber()).matches("MAIN-RCP-\\d{2}-000001");
        assertThat(sale.getTotal()).isEqualByComparingTo("3000");
        assertThat(sale.getTaxAmount()).isEqualByComparingTo("142.86"); // 3000 × 5 / 105, per line
        assertThat(sale.getPaidAmount()).isEqualByComparingTo("3000");
        assertThat(sale.getDueAmount()).isEqualByComparingTo("0");
        assertThat(sale.isTaxInclusive()).isTrue();

        SaleLine line = result.details().lines().getFirst();
        assertThat(line.getUnitCost()).isEqualByComparingTo("600");
        assertThat(line.getProductName()).isEqualTo("Cooking Oil 1L");
        assertThat(line.getTaxAmount()).isEqualByComparingTo(sale.getTaxAmount());
        assertThat(result.details().payments().getFirst().getChangeAmount()).isEqualByComparingTo("2000");

        assertThat(balance(oil).getQuantity()).isEqualByComparingTo("7");
        StockMovement movement = ledger(oil).getFirst();
        assertThat(movement.getType()).isEqualTo(StockMovementType.SALE);
        assertThat(movement.getQuantity()).isEqualByComparingTo("-3");
        assertThat(movement.getUnitCost()).isEqualByComparingTo("600");
        assertThat(movement.getReferenceNumber()).isEqualTo(sale.getReceiptNumber());
        assertThat(movement.getReferenceId()).isEqualTo(sale.getId());
        assertThat(movement.getSeq()).isEqualTo(2L);
        assertThat(shops.as(shop, () -> rebuilder.verify()).clean()).isTrue();
    }

    /** The vertical-slice promise: retry the same completion, the balance drops once, one receipt. */
    @Test
    void aRetriedCompletionReturnsTheOriginalAndPostsNothing() {
        CompletionResult first = checkout("retry-me", cart(oil, "2"), cash("2000", null));
        CompletionResult again = checkout("retry-me", cart(oil, "5"), cash("5000", null));

        assertThat(again.replayed()).isTrue();
        assertThat(again.details().sale().getId()).isEqualTo(first.details().sale().getId());
        assertThat(again.details().sale().getTotal()).isEqualByComparingTo("2000");
        assertThat(balance(oil).getQuantity()).isEqualByComparingTo("8");
        assertThat(ledger(oil)).hasSize(2);
        assertThat(checkout("next", cart(oil, "1"), cash("1000", null)).details().sale().getReceiptNumber())
                .endsWith("-000002");
    }

    @Test
    void concurrentRetriesOfOneKeyCompleteOnce() throws Exception {
        List<CompletionResult> results = runTogether(List.of(
                () -> checkout("double-tap", cart(oil, "2"), cash("2000", null)),
                () -> checkout("double-tap", cart(oil, "2"), cash("2000", null))));

        assertThat(results).extracting(r -> r.details().sale().getId()).containsOnly(
                results.getFirst().details().sale().getId());
        assertThat(results).filteredOn(CompletionResult::replayed).hasSize(1);
        assertThat(balance(oil).getQuantity()).isEqualByComparingTo("8");
        assertThat(ledger(oil)).hasSize(2);
    }

    @Test
    void aSaleBeyondStockIsRefusedAndLeavesNothingBehind() {
        assertCode(() -> checkout("too-many", cart(oil, "11"), cash("11000", null)), "insufficient_stock");

        assertThat(shops.as(shop, () -> sales.findByKey(main, "too-many"))).isNull();
        assertThat(balance(oil).getQuantity()).isEqualByComparingTo("10");
        // the refused sale gave its receipt number back
        assertThat(checkout("fits", cart(oil, "1"), cash("1000", null)).details().sale().getReceiptNumber())
                .endsWith("-000001");
    }

    @Test
    void withNegativeStockAllowedTheSaleGoesThrough() {
        shops.allowNegativeStock(shop, true);
        checkout("oversell", cart(oil, "12"), cash("12000", null));
        assertThat(balance(oil).getQuantity()).isEqualByComparingTo("-2");
    }

    /** Last month's margin never changes when a supplier raises a price today. */
    @Test
    void theCostSnapshotSurvivesLaterPurchases() {
        CompletionResult sold = checkout("before", cart(oil, "2"), cash("2000", null));
        shops.run(shop, () -> documents.createAndPost(stockIn(main, oil, "8", "1500")));
        assertThat(balance(oil).getAverageCost()).isEqualByComparingTo("1050"); // 8 @ 600 + 8 @ 1500

        SaleDetails reread = shops.as(shop, () -> sales.find(sold.details().sale().getId()));
        assertThat(reread.lines().getFirst().getUnitCost()).isEqualByComparingTo("600");
        CompletionResult after = checkout("after", cart(oil, "1"), cash("1000", null));
        assertThat(after.details().lines().getFirst().getUnitCost()).isEqualByComparingTo("1050");
    }

    @Test
    void aProductThatDoesNotTrackInventorySellsAtZeroCostWithNoMovement() {
        UUID delivery = shops.as(shop, () -> productService.create(new ProductCommand(null, null, "Delivery", null,
                null, ProductUnit.PIECE, null, null, new BigDecimal("2000"), null, false, false, null, null, null,
                null, null, null)).getId());
        CompletionResult result = checkout("svc", cart(delivery, "1"), cash("2000", null));
        assertThat(result.details().lines().getFirst().getUnitCost()).isEqualByComparingTo("0");
        assertThat(result.details().sale().getTaxAmount()).isEqualByComparingTo("0");
        assertThat(ledger(delivery)).isEmpty();
    }

    @Test
    void aHeldCartBurnsNoNumberAndOnlyAParkedCartCanBeVoided() {
        SaleDetails held = shops.as(shop, () -> sales.saveCart(cart(oil, "2"), true));
        assertThat(held.sale().getStatus()).isEqualTo(SaleStatus.HELD);
        assertThat(held.sale().getReceiptNumber()).isNull();
        assertThat(held.lines().getFirst().getUnitCost()).isEqualByComparingTo("0");
        assertThat(balance(oil).getQuantity()).isEqualByComparingTo("10");

        SaleDetails parked = shops.as(shop, () -> sales.saveCart(cart(oil, "1"), false));
        shops.run(shop, () -> sales.voidCart(parked.sale().getId()));
        assertCode(() -> shops.inTenant(shop, () -> checkout.completeCart(parked.sale().getId(), "void-then-pay",
                cash("1000", null))), "sale_not_open");

        CompletionResult paid = shops.inTenant(shop, () -> checkout.completeCart(held.sale().getId(), "held-1",
                cash("2000", null)));
        assertThat(paid.details().sale().getStatus()).isEqualTo(SaleStatus.COMPLETED);
        assertThat(paid.details().sale().getReceiptNumber()).endsWith("-000001");
        assertThat(paid.details().lines().getFirst().getUnitCost()).isEqualByComparingTo("600");
        assertThat(balance(oil).getQuantity()).isEqualByComparingTo("8");

        assertCode(() -> shops.as(shop, () -> sales.voidCart(held.sale().getId())), "sale_not_voidable");
        // completing the same cart again with the same key is a replay, not a second sale
        CompletionResult replay = shops.inTenant(shop, () -> checkout.completeCart(held.sale().getId(), "held-1",
                cash("2000", null)));
        assertThat(replay.replayed()).isTrue();
        assertThat(balance(oil).getQuantity()).isEqualByComparingTo("8");
    }

    @Test
    void paymentsMustCoverTheTotalExactly() {
        assertCode(() -> checkout("short", cart(oil, "2"), cash("1500", null)), "payment_mismatch");
        assertCode(() -> checkout("credit", cart(oil, "1"),
                List.of(new PaymentCommand(PaymentMethod.CREDIT, new BigDecimal("1000"), null, null))),
                "customer_required"); // a walk-in never gets credit
        assertCode(() -> checkout("tender", cart(oil, "1"), cash("1000", "900")), "insufficient_tender");
        assertCode(() -> checkout("wallet-tender", cart(oil, "1"),
                List.of(new PaymentCommand(PaymentMethod.KBZ_PAY, new BigDecimal("1000"), new BigDecimal("1000"),
                        "T1"))), "invalid_payment");

        CompletionResult split = checkout("split", cart(oil, "3"), List.of(
                new PaymentCommand(PaymentMethod.CASH, new BigDecimal("1000"), new BigDecimal("1000"), null),
                new PaymentCommand(PaymentMethod.KBZ_PAY, new BigDecimal("2000"), null, "KBZ-778812")));
        assertThat(split.details().payments()).hasSize(2);
        assertThat(split.details().sale().getPaidAmount()).isEqualByComparingTo("3000");
    }

    @Test
    void closingAShiftCountsOnlyCashAndStoresTheVariance() {
        checkout("c1", cart(oil, "2"), cash("2000", "5000"));
        checkout("w1", cart(oil, "1"), List.of(new PaymentCommand(PaymentMethod.WAVE_PAY, new BigDecimal("1000"),
                null, "WAVE-1")));
        shops.run(shop, () -> sales.saveCart(cart(oil, "4"), true)); // a held cart took no money

        CashierShift closed = shops.as(shop, () -> shiftService.close(shift, new BigDecimal("51950")));
        assertThat(closed.getStatus()).isEqualTo(ShiftStatus.CLOSED);
        assertThat(closed.getExpectedCash()).isEqualByComparingTo("52000"); // float + cash applied, not tendered
        assertThat(closed.getVariance()).isEqualByComparingTo("-50");

        assertCode(() -> checkout("late", cart(oil, "1"), cash("1000", null)), "shift_closed");
        assertCode(() -> shops.as(shop, () -> shiftService.close(shift, BigDecimal.ONE)), "shift_closed");
    }

    @Test
    void oneOpenShiftPerLocationAndAPosSaleNeedsOne() {
        assertCode(() -> shops.as(shop, () -> shiftService.open(main, BigDecimal.ZERO)), "shift_already_open");
        assertCode(() -> checkout("no-shift", new CartCommand(main, SaleChannel.POS, null, null, null,
                List.of(new LineCommand(oil, BigDecimal.ONE, null))), cash("1000", null)), "shift_required");
    }

    /** Only a STORE has registers, shifts and receipt sequences (spec §12 Locations). */
    @Test
    void aWarehouseCannotOpenAShiftOrSell() {
        UUID warehouse = shops.location(shop, "WH", LocationType.WAREHOUSE);
        assertCode(() -> shops.as(shop, () -> shiftService.open(warehouse, BigDecimal.ZERO)), "not_a_store");
        assertCode(() -> checkout("wh", new CartCommand(warehouse, SaleChannel.ONLINE, null, null, null,
                List.of(new LineCommand(oil, BigDecimal.ONE, null))), cash("1000", null)), "not_a_store");
    }

    @Test
    void aSessionScopedToOneStoreCannotSellAtAnother() {
        UUID branch = shops.location(shop, "B2", LocationType.STORE);
        assertCode(() -> shops.asScoped(shop, main, () -> shiftService.open(branch, BigDecimal.ZERO)),
                "location_out_of_scope");
        CompletionResult atHome = shops.asScoped(shop, main, () -> checkout.checkout("scoped", cart(oil, "1"),
                cash("1000", null)));
        assertThat(atHome.details().sale().getStatus()).isEqualTo(SaleStatus.COMPLETED);
    }

    @Test
    void wholesaleUsesTheWholesalePriceOrFallsBackToRetail() {
        UUID rice = shops.as(shop, () -> productService.create(new ProductCommand(null, null, "Rice", null, null,
                ProductUnit.BAG, null, null, new BigDecimal("21000"), new BigDecimal("19500"), false, null, null,
                null, null, null, null, null)).getId());
        shops.run(shop, () -> documents.createAndPost(stockIn(main, rice, "5", "17000")));

        CompletionResult wholesale = checkout("ws", new CartCommand(main, SaleChannel.POS, shift, PriceType.WHOLESALE,
                null, List.of(new LineCommand(rice, new BigDecimal("2"), null), new LineCommand(oil, BigDecimal.ONE,
                        null))), cash("40000", null));
        assertThat(wholesale.details().lines()).extracting(l -> l.getUnitPrice().stripTrailingZeros().toPlainString())
                .containsExactly("19500", "1000");
    }

    @Test
    void theOrganizationsRoundingStepRoundsTheTotal() {
        shops.run(shop, () -> organizations.findById(shop.organizationId()).orElseThrow()
                .setRoundTotalToNearest(new BigDecimal("50")));
        // 3 × 1000 − 30 line discount = 2970 → 2950 (nearest 50, half up: 2970 is 20 above 2950)
        CompletionResult rounded = checkout("round", new CartCommand(main, SaleChannel.POS, shift, null, null,
                List.of(new LineCommand(oil, new BigDecimal("3"), new BigDecimal("30")))), cash("2950", null));
        assertThat(rounded.details().sale().getTotal()).isEqualByComparingTo("2950");
        assertThat(rounded.details().sale().getRoundingAdjustment()).isEqualByComparingTo("-20");
    }

    @Test
    void anOnlineSaleHasNoShiftAndOnlySellsOnlineProducts() {
        assertCode(() -> checkout("online-pos-only", new CartCommand(main, SaleChannel.ONLINE, null, null, null,
                List.of(new LineCommand(oil, BigDecimal.ONE, null))), cash("1000", null)), "product_not_for_sale");
        UUID webOnly = shops.as(shop, () -> productService.create(new ProductCommand(null, null, "Serum", null, null,
                ProductUnit.PIECE, null, null, new BigDecimal("15000"), null, null, null, null, null, true, null,
                null, null)).getId());
        shops.run(shop, () -> documents.createAndPost(stockIn(main, webOnly, "3", "9000")));

        CompletionResult online = checkout("web-1", new CartCommand(main, SaleChannel.ONLINE, null, null, null,
                List.of(new LineCommand(webOnly, BigDecimal.ONE, null))),
                List.of(new PaymentCommand(PaymentMethod.KBZ_PAY, new BigDecimal("15000"), null, "KBZ-1")));
        assertThat(online.details().sale().getCashierShiftId()).isNull();
        assertCode(() -> checkout("web-2", new CartCommand(main, SaleChannel.ONLINE, shift, null, null,
                List.of(new LineCommand(webOnly, BigDecimal.ONE, null))), cash("15000", null)), "unexpected_shift");
    }

    // ───────────────────────────────────────────────────────────── helpers

    private CompletionResult checkout(String key, CartCommand cart, List<PaymentCommand> payments) {
        return shops.inTenant(shop, () -> checkout.checkout(key, cart, payments));
    }

    private CartCommand cart(UUID product, String quantity) {
        return new CartCommand(main, SaleChannel.POS, shift, null, null,
                List.of(new LineCommand(product, new BigDecimal(quantity), null)));
    }

    private static List<PaymentCommand> cash(String amount, String tendered) {
        return List.of(new PaymentCommand(PaymentMethod.CASH, new BigDecimal(amount),
                tendered == null ? null : new BigDecimal(tendered), null));
    }

    private StockBalance balance(UUID product) {
        return shops.as(shop, () -> balances.findByLocationIdAndProductId(main, product).orElseThrow());
    }

    /** Newest first. */
    private List<StockMovement> ledger(UUID product) {
        return shops.as(shop, () -> movements.ledger(main, product, Pageable.unpaged()));
    }

    private static void assertCode(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo(code));
    }

    private static <T> List<T> runTogether(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = tasks.stream().map(task -> pool.submit(() -> {
                start.await();
                return task.call();
            })).toList();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
