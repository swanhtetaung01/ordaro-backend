package app.trillopos.finance;

import static app.trillopos.inventory.StockPostingTest.stockIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.ZoneId;
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

import app.trillopos.catalog.ProductService;
import app.trillopos.catalog.ProductService.ProductCommand;
import app.trillopos.catalog.ProductUnit;
import app.trillopos.crm.Customer;
import app.trillopos.crm.CustomerRepository;
import app.trillopos.crm.CustomerType;
import app.trillopos.finance.ReceivableService.ManualCommand;
import app.trillopos.finance.ReceivableService.SettleCommand;
import app.trillopos.finance.ReceivableService.SettlementResult;
import app.trillopos.inventory.StockBalanceRepository;
import app.trillopos.inventory.StockDocumentService;
import app.trillopos.org.LocationType;
import app.trillopos.sales.PaymentMethod;
import app.trillopos.sales.PriceType;
import app.trillopos.sales.SaleChannel;
import app.trillopos.sales.SaleCheckout;
import app.trillopos.sales.SaleService;
import app.trillopos.sales.SaleService.CartCommand;
import app.trillopos.sales.SaleService.CompletionResult;
import app.trillopos.sales.SaleService.LineCommand;
import app.trillopos.sales.SaleService.PaymentCommand;
import app.trillopos.sales.SaleService.SaleDetails;
import app.trillopos.sales.SaleStatus;
import app.trillopos.sales.ShiftService;
import app.trillopos.shared.web.ApiException;
import app.trillopos.support.IntegrationTest;
import app.trillopos.support.Shops;
import app.trillopos.support.Shops.Shop;

/** Step 5a: CREDIT payments open receivables (spec §9.2 step 7); repayments settle them (§7). */
class CreditSaleTest extends IntegrationTest {

    private static final ZoneId YANGON = ZoneId.of("Asia/Yangon");

    @Autowired
    Shops shops;

    @Autowired
    SaleCheckout checkout;

    @Autowired
    SaleService sales;

    @Autowired
    ShiftService shiftService;

    @Autowired
    ReceivableService receivables;

    @Autowired
    ReceivableRepository receivableRows;

    @Autowired
    CustomerRepository customers;

    @Autowired
    StockDocumentService documents;

    @Autowired
    StockBalanceRepository balances;

    @Autowired
    ProductService productService;

    Shop shop;
    UUID main;
    UUID oil;
    UUID shift;

    /** 20 oil at 600 on hand (retail 1000) and an open shift with a 50,000 float. */
    @BeforeEach
    void openShop() {
        shop = shops.newShop();
        main = shop.mainLocationId();
        oil = shops.product(shop, "Cooking Oil 1L");
        shops.run(shop, () -> documents.createAndPost(stockIn(main, oil, "20", "600")));
        shift = shops.as(shop, () -> shiftService.open(main, new BigDecimal("50000")).getId());
    }

    @Test
    void aCreditSaleCompletesAndOpensAReceivableDueAfterTheCustomersTerms() {
        UUID ko = customer("Ko Aung", "10000", 30);
        CompletionResult sold = checkout("c-1", cart(ko, "3"), credit("3000"));

        assertThat(sold.details().sale().getStatus()).isEqualTo(SaleStatus.COMPLETED);
        assertThat(sold.details().sale().getCustomerId()).isEqualTo(ko);
        assertThat(sold.details().sale().getPaidAmount()).isEqualByComparingTo("3000"); // credit counts as paid
        assertThat(sold.details().sale().getDueAmount()).isEqualByComparingTo("0");

        Receivable r = receivableOf(sold);
        assertThat(r.getStatus()).isEqualTo(ReceivableStatus.OPEN);
        assertThat(r.getCustomerId()).isEqualTo(ko);
        assertThat(r.getCounterpartyType()).isEqualTo(CounterpartyType.CUSTOMER);
        assertThat(r.getOriginalAmount()).isEqualByComparingTo("3000");
        assertThat(r.getOutstandingAmount()).isEqualByComparingTo("3000");
        assertThat(r.getReferenceNumber()).isEqualTo(sold.details().sale().getReceiptNumber());
        assertThat(r.getLocationId()).isEqualTo(main);
        assertThat(r.getDueDate()).isEqualTo(r.getIssuedAt().atZone(YANGON).toLocalDate().plusDays(30));
        assertThat(onHand()).isEqualByComparingTo("17");
    }

    @Test
    void cashAndCreditSplitOpensAReceivableForTheCreditPartOnly() {
        UUID ko = customer("Ko Aung", "10000", 7);
        CompletionResult sold = checkout("split", cart(ko, "3"), List.of(
                new PaymentCommand(PaymentMethod.CASH, new BigDecimal("1000"), new BigDecimal("1000"), null),
                new PaymentCommand(PaymentMethod.CREDIT, new BigDecimal("2000"), null, null)));

        assertThat(receivableOf(sold).getOriginalAmount()).isEqualByComparingTo("2000");
        assertThat(sold.details().payments()).extracting(p -> p.getMethod())
                .containsExactly(PaymentMethod.CASH, PaymentMethod.CREDIT);
    }

    @Test
    void creditBeyondTheLimitIsRefusedAndLeavesNothingBehind() {
        UUID noCredit = customer("Walk-in regular", "0", 0);
        assertCode(() -> checkout("zero", cart(noCredit, "1"), credit("1000")), "credit_limit_exceeded");

        UUID ko = customer("Ko Aung", "5000", 30);
        checkout("first", cart(ko, "3"), credit("3000"));
        assertCode(() -> checkout("second", cart(ko, "3"), credit("3000")), "credit_limit_exceeded");

        assertThat(onHand()).isEqualByComparingTo("17"); // only the first sale took stock
        assertThat(shops.as(shop, () -> sales.findByKey(main, "second"))).isNull();
        // the refused sale gave its receipt number back
        assertThat(checkout("cash", cart(null, "1"), cash("1000")).details().sale().getReceiptNumber())
                .endsWith("-000002");
        assertCode(() -> checkout("two-credits", cart(ko, "2"), List.of(
                new PaymentCommand(PaymentMethod.CREDIT, new BigDecimal("1000"), null, null),
                new PaymentCommand(PaymentMethod.CREDIT, new BigDecimal("1000"), null, null))), "invalid_payment");
    }

    /**
     * Two registers, one customer, each sale fits the limit alone but not together: one wins. The
     * baskets hold different products, so no stock lock serializes them — only the customer lock.
     */
    @Test
    void twoRegistersCannotBothPassTheCreditCheck() throws Exception {
        UUID ko = customer("Ko Aung", "5000", 30);
        UUID sugar = shops.product(shop, "Sugar 1kg");
        shops.run(shop, () -> documents.createAndPost(stockIn(main, sugar, "10", "500")));
        CartCommand sugarCart = new CartCommand(main, SaleChannel.POS, shift, null, null,
                List.of(new LineCommand(sugar, new BigDecimal("3"), null)), ko);
        List<Object> outcomes = runTogether(List.of(
                () -> attempt(() -> checkout("reg-1", cart(ko, "3"), credit("3000"))),
                () -> attempt(() -> checkout("reg-2", sugarCart, credit("3000")))));

        assertThat(outcomes).filteredOn(o -> o instanceof CompletionResult).hasSize(1);
        assertThat(outcomes).filteredOn(o -> "credit_limit_exceeded".equals(o)).hasSize(1);
        assertThat(shops.as(shop, () -> receivables.outstandingFor(ko))).isEqualByComparingTo("3000");
    }

    @Test
    void theCustomersDefaultPriceListAppliesUnlessTheCartNamesOne() {
        UUID rice = shops.as(shop, () -> productService.create(new ProductCommand(null, null, "Rice", null, null,
                ProductUnit.BAG, null, null, new BigDecimal("21000"), new BigDecimal("19500"), false, null, null,
                null, null, null, null, null)).getId());
        shops.run(shop, () -> documents.createAndPost(stockIn(main, rice, "5", "17000")));
        UUID trader = shops.as(shop, () -> {
            Customer c = new Customer("Golden Trader", CustomerType.B2B, PriceType.WHOLESALE);
            return customers.save(c).getId();
        });

        CompletionResult wholesale = checkout("ws", new CartCommand(main, SaleChannel.POS, shift, null, null,
                List.of(new LineCommand(rice, BigDecimal.ONE, null)), trader), cash("19500"));
        assertThat(wholesale.details().sale().getPriceType()).isEqualTo(PriceType.WHOLESALE);
        assertThat(wholesale.details().lines().getFirst().getUnitPrice()).isEqualByComparingTo("19500");

        CompletionResult retail = checkout("rt", new CartCommand(main, SaleChannel.POS, shift, PriceType.RETAIL, null,
                List.of(new LineCommand(rice, BigDecimal.ONE, null)), trader), cash("21000"));
        assertThat(retail.details().lines().getFirst().getUnitPrice()).isEqualByComparingTo("21000");
    }

    @Test
    void aHeldCartKeepsItsCustomerAndCompletesOnCredit() {
        UUID ko = customer("Ko Aung", "10000", 30);
        SaleDetails held = shops.as(shop, () -> sales.saveCart(cart(ko, "2"), true));
        assertThat(held.sale().getCustomerId()).isEqualTo(ko);

        CompletionResult paid = shops.inTenant(shop, () -> checkout.completeCart(held.sale().getId(), "held",
                credit("2000")));
        assertThat(paid.details().sale().getCustomerId()).isEqualTo(ko);
        assertThat(receivableOf(paid).getOutstandingAmount()).isEqualByComparingTo("2000");
    }

    @Test
    void repaymentsSettleTheReceivablePartThenWhole() {
        UUID ko = customer("Ko Aung", "10000", 30);
        Receivable r = receivableOf(checkout("c", cart(ko, "5"), credit("5000")));

        SettlementResult part = settle(r.getId(), "2000", PaymentMethod.KBZ_PAY, null, "rp-1");
        assertThat(part.details().receivable().getStatus()).isEqualTo(ReceivableStatus.PARTIALLY_SETTLED);
        assertThat(part.details().receivable().getOutstandingAmount()).isEqualByComparingTo("3000");

        assertCode(() -> settle(r.getId(), "3000.01", PaymentMethod.CASH, null, null), "amount_exceeds_outstanding");
        assertCode(() -> settle(r.getId(), "100", PaymentMethod.CREDIT, null, null), "invalid_method");

        SettlementResult rest = settle(r.getId(), "3000", PaymentMethod.CASH, null, "rp-2");
        assertThat(rest.details().receivable().getStatus()).isEqualTo(ReceivableStatus.SETTLED);
        assertThat(rest.details().receivable().getSettledAmount()).isEqualByComparingTo("5000");
        assertThat(rest.details().settlements()).hasSize(2);
        assertCode(() -> settle(r.getId(), "1", PaymentMethod.CASH, null, null), "receivable_closed");

        // the sale itself never changes: its paid/due are frozen history
        SaleDetails sale = shops.as(shop, () -> sales.find(r.getSourceId()));
        assertThat(sale.sale().getDueAmount()).isEqualByComparingTo("0");
        assertThat(sale.payments()).hasSize(1);
    }

    @Test
    void aRetriedRepaymentWithTheSameKeyIsRecordedOnce() {
        UUID ko = customer("Ko Aung", "10000", 30);
        Receivable r = receivableOf(checkout("c", cart(ko, "5"), credit("5000")));

        SettlementResult first = settle(r.getId(), "1000", PaymentMethod.WAVE_PAY, null, "same-key");
        SettlementResult again = settle(r.getId(), "1000", PaymentMethod.WAVE_PAY, null, "same-key");

        assertThat(again.replayed()).isTrue();
        assertThat(again.settlement().getId()).isEqualTo(first.settlement().getId());
        assertThat(again.details().receivable().getOutstandingAmount()).isEqualByComparingTo("4000");
    }

    /** A debtor paying cash at the register puts cash in that drawer. */
    @Test
    void aCashRepaymentAtTheRegisterIsInTheDrawer() {
        UUID ko = customer("Ko Aung", "10000", 30);
        Receivable r = receivableOf(checkout("c", cart(ko, "4"), credit("4000")));
        checkout("cash-sale", cart(null, "1"), cash("1000"));

        settle(r.getId(), "2500", PaymentMethod.CASH, shift, null);
        settle(r.getId(), "500", PaymentMethod.KBZ_PAY, shift, null); // a wallet repayment is not drawer cash

        ShiftService.Drawer drawer = shops.as(shop, () -> shiftService.drawer(shift));
        assertThat(drawer.cashSales()).isEqualByComparingTo("1000");
        assertThat(drawer.cashRepayments()).isEqualByComparingTo("2500");
        assertThat(drawer.expectedCash()).isEqualByComparingTo("53500");
        assertThat(shops.as(shop, () -> shiftService.close(shift, new BigDecimal("53500"))).getVariance())
                .isEqualByComparingTo("0");
        assertCode(() -> settle(r.getId(), "100", PaymentMethod.CASH, shift, null), "shift_closed");

        UUID branch = shops.location(shop, "B2", LocationType.STORE);
        UUID branchShift = shops.as(shop, () -> shiftService.open(branch, BigDecimal.ZERO).getId());
        assertCode(() -> settle(r.getId(), "100", PaymentMethod.CASH, branchShift, null), "shift_elsewhere");
    }

    @Test
    void aWriteOffClosesTheDebtAndFreesTheCredit() {
        UUID ko = customer("Ko Aung", "5000", 30);
        Receivable r = receivableOf(checkout("c", cart(ko, "5"), credit("5000")));
        settle(r.getId(), "1000", PaymentMethod.CASH, null, null);

        Receivable written = shops.as(shop, () -> receivables.writeOff(r.getId(), "moved away")).receivable();
        assertThat(written.getStatus()).isEqualTo(ReceivableStatus.WRITTEN_OFF);
        assertThat(written.getWrittenOffAmount()).isEqualByComparingTo("4000");
        assertThat(written.getSettledAmount()).isEqualByComparingTo("1000");
        assertThat(written.getOutstandingAmount()).isEqualByComparingTo("0");
        assertThat(checkout("again", cart(ko, "5"), credit("5000")).details().sale().getStatus())
                .isEqualTo(SaleStatus.COMPLETED);
    }

    @Test
    void aManualDebtCountsAgainstTheLimit() {
        UUID ko = customer("Ko Aung", "5000", 30);
        shops.run(shop, () -> receivables.createManual(new ManualCommand(ko, main, new BigDecimal("4500"), null, null,
                "notebook balance")));
        assertCode(() -> checkout("over", cart(ko, "1"), credit("1000")), "credit_limit_exceeded");
        assertThat(checkout("fits", cart(ko, "1"), List.of(
                new PaymentCommand(PaymentMethod.CASH, new BigDecimal("500"), null, null),
                new PaymentCommand(PaymentMethod.CREDIT, new BigDecimal("500"), null, null))).details().sale()
                .getStatus()).isEqualTo(SaleStatus.COMPLETED);
    }

    @Test
    void anArchivedCustomerCannotBuyAndAnotherShopCannotSeeTheDebt() {
        UUID ko = customer("Ko Aung", "5000", 30);
        Receivable r = receivableOf(checkout("c", cart(ko, "1"), credit("1000")));

        Shop other = shops.newShop();
        assertCode(() -> shops.as(other, () -> receivables.find(r.getId())), "receivable_not_found");
        assertCode(() -> shops.as(other, () -> receivables.settle(r.getId(), new SettleCommand(BigDecimal.ONE,
                PaymentMethod.CASH, other.mainLocationId(), null, null, null, null, null))), "receivable_not_found");

        shops.run(shop, () -> customers.findById(ko).orElseThrow().archive(java.time.Instant.now()));
        assertCode(() -> checkout("gone", cart(ko, "1"), cash("1000")), "customer_archived");
    }

    // ───────────────────────────────────────────────────────────── helpers

    private UUID customer(String name, String creditLimit, int termDays) {
        return shops.as(shop, () -> {
            Customer c = new Customer(name, CustomerType.MEMBER, PriceType.RETAIL);
            c.setCreditLimit(new BigDecimal(creditLimit));
            c.setCreditTermDays(termDays);
            return customers.save(c).getId();
        });
    }

    private CartCommand cart(UUID customerId, String quantity) {
        return new CartCommand(main, SaleChannel.POS, shift, null, null,
                List.of(new LineCommand(oil, new BigDecimal(quantity), null)), customerId);
    }

    private CompletionResult checkout(String key, CartCommand cart, List<PaymentCommand> payments) {
        return shops.inTenant(shop, () -> checkout.checkout(key, cart, payments));
    }

    private SettlementResult settle(UUID receivableId, String amount, PaymentMethod method, UUID shiftId,
            String key) {
        return shops.as(shop, () -> receivables.settle(receivableId, new SettleCommand(new BigDecimal(amount), method,
                main, shiftId, null, null, null, key)));
    }

    private Receivable receivableOf(CompletionResult sold) {
        return shops.as(shop, () -> receivableRows.findBySourceTypeAndSourceId(ReceivableSourceType.SALE,
                sold.details().sale().getId()).orElseThrow());
    }

    private BigDecimal onHand() {
        return shops.as(shop, () -> balances.findByLocationIdAndProductId(main, oil).orElseThrow().getQuantity());
    }

    private static List<PaymentCommand> credit(String amount) {
        return List.of(new PaymentCommand(PaymentMethod.CREDIT, new BigDecimal(amount), null, null));
    }

    private static List<PaymentCommand> cash(String amount) {
        return List.of(new PaymentCommand(PaymentMethod.CASH, new BigDecimal(amount), null, null));
    }

    private static Object attempt(Callable<CompletionResult> call) {
        try {
            return call.call();
        } catch (ApiException e) {
            return e.code();
        } catch (Exception e) {
            return e;
        }
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
