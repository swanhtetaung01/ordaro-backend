package app.trillopos.sales;

import static app.trillopos.inventory.StockPostingTest.stockIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;

import app.trillopos.catalog.ProductService;
import app.trillopos.catalog.ProductService.ProductCommand;
import app.trillopos.catalog.ProductUnit;
import app.trillopos.crm.Customer;
import app.trillopos.crm.CustomerRepository;
import app.trillopos.crm.CustomerType;
import app.trillopos.finance.Receivable;
import app.trillopos.finance.ReceivableService;
import app.trillopos.finance.ReceivableStatus;
import app.trillopos.inventory.StockBalance;
import app.trillopos.inventory.StockBalanceRebuilder;
import app.trillopos.inventory.StockBalanceRepository;
import app.trillopos.inventory.StockDocumentService;
import app.trillopos.inventory.StockMovement;
import app.trillopos.inventory.StockMovementRepository;
import app.trillopos.inventory.StockMovementType;
import app.trillopos.sales.ReturnService.LineCommand;
import app.trillopos.sales.ReturnService.ReturnCommand;
import app.trillopos.sales.ReturnService.ReturnResult;
import app.trillopos.sales.SaleService.CartCommand;
import app.trillopos.sales.SaleService.CompletionResult;
import app.trillopos.sales.SaleService.PaymentCommand;
import app.trillopos.shared.web.ApiException;
import app.trillopos.support.IntegrationTest;
import app.trillopos.support.Shops;
import app.trillopos.support.Shops.Shop;

/** Step 5c: returns (spec §6), restocking through the ledger (§9.1) and gross profit (§9.3). */
class SaleReturnTest extends IntegrationTest {

    @Autowired
    Shops shops;

    @Autowired
    SaleCheckout checkout;

    @Autowired
    SaleService sales;

    @Autowired
    ReturnService returns;

    @Autowired
    ShiftService shiftService;

    @Autowired
    ReceivableService receivables;

    @Autowired
    CustomerRepository customers;

    @Autowired
    StockDocumentService documents;

    @Autowired
    StockBalanceRepository balances;

    @Autowired
    StockMovementRepository movements;

    @Autowired
    StockBalanceRebuilder rebuilder;

    @Autowired
    ProductService productService;

    @Autowired
    JdbcTemplate jdbc;

    Shop shop;
    UUID main;
    UUID oil;
    UUID shift;

    /** 10 oil at 600 on hand (retail 1000, 5 % inclusive tax) and an open shift with a 50,000 float. */
    @BeforeEach
    void openShop() {
        shop = shops.newShop();
        main = shop.mainLocationId();
        oil = shops.product(shop, "Cooking Oil 1L");
        shops.run(shop, () -> documents.createAndPost(stockIn(main, oil, "10", "600")));
        shift = shops.as(shop, () -> shiftService.open(main, new BigDecimal("50000")).getId());
    }

    @Test
    void aPartialReturnRefundsProRataRestocksAtTheOriginalCostAndMarksTheSale() {
        CompletionResult sold = checkout("s1", cart(oil, "3"), cash("3000"));
        // a later purchase at a higher price moves the average; the return must still come back at 600
        shops.run(shop, () -> documents.createAndPost(stockIn(main, oil, "7", "1000")));
        assertThat(balance(oil).getAverageCost()).isEqualByComparingTo("800"); // 7 @ 600 + 7 @ 1000

        ReturnResult r = ret(sold, PaymentMethod.CASH, shift, "r1", line(sold, 0, "1", null));

        SaleReturn header = r.details().saleReturn();
        assertThat(header.getReturnNumber()).matches("MAIN-RTN-\\d{2}-000001");
        assertThat(header.getRefundAmount()).isEqualByComparingTo("1000");
        assertThat(header.getTaxAmount()).isEqualByComparingTo("47.62"); // 142.86 × 1 / 3
        assertThat(header.getCashierShiftId()).isEqualTo(shift);
        SaleReturnLine line = r.details().lines().getFirst();
        assertThat(line.getUnitCost()).isEqualByComparingTo("600");
        assertThat(line.isRestock()).isTrue();

        assertThat(balance(oil).getQuantity()).isEqualByComparingTo("15");
        assertThat(balance(oil).getAverageCost()).isEqualByComparingTo("786.6667"); // (14 × 800 + 600) / 15
        StockMovement back = ledger(oil).getFirst();
        assertThat(back.getType()).isEqualTo(StockMovementType.SALE_RETURN);
        assertThat(back.getQuantity()).isEqualByComparingTo("1");
        assertThat(back.getUnitCost()).isEqualByComparingTo("600");
        assertThat(back.getReferenceNumber()).isEqualTo(header.getReturnNumber());
        assertThat(back.getReferenceId()).isEqualTo(header.getId());
        assertThat(shops.as(shop, () -> rebuilder.verify()).clean()).isTrue();

        Sale sale = shops.as(shop, () -> sales.find(sold.details().sale().getId())).sale();
        assertThat(sale.getStatus()).isEqualTo(SaleStatus.PARTIALLY_REFUNDED);
        assertThat(sale.getPaidAmount()).isEqualByComparingTo("3000"); // frozen history
    }

    /** 3 × 1000 with a 1-Kyat line discount: 2999 refunds as 999.67, 999.66, 999.67 — cumulative rounding, Σ exact. */
    @Test
    void refundsAcrossSeveralReturnsAddUpToTheLineTotalExactly() {
        CompletionResult sold = checkout("odd", new CartCommand(main, SaleChannel.POS, shift, null, null,
                List.of(new SaleService.LineCommand(oil, new BigDecimal("3"), BigDecimal.ONE))), cash("2999"));
        assertThat(sold.details().lines().getFirst().getLineTotal()).isEqualByComparingTo("2999");

        ReturnResult first = ret(sold, PaymentMethod.CASH, null, "o1", line(sold, 0, "1", null));
        ReturnResult second = ret(sold, PaymentMethod.CASH, null, "o2", line(sold, 0, "1", null));
        ReturnResult third = ret(sold, PaymentMethod.CASH, null, "o3", line(sold, 0, "1", null));

        assertThat(first.details().saleReturn().getRefundAmount()).isEqualByComparingTo("999.67");
        assertThat(second.details().saleReturn().getRefundAmount()).isEqualByComparingTo("999.66");
        assertThat(third.details().saleReturn().getRefundAmount()).isEqualByComparingTo("999.67");
        assertThat(first.details().saleReturn().getRefundAmount().add(second.details().saleReturn().getRefundAmount())
                .add(third.details().saleReturn().getRefundAmount())).isEqualByComparingTo("2999");
        assertThat(first.details().saleReturn().getTaxAmount().add(second.details().saleReturn().getTaxAmount())
                .add(third.details().saleReturn().getTaxAmount()))
                .isEqualByComparingTo(sold.details().sale().getTaxAmount());
        assertThat(shops.as(shop, () -> sales.find(sold.details().sale().getId())).sale().getStatus())
                .isEqualTo(SaleStatus.REFUNDED);
        assertThat(balance(oil).getQuantity()).isEqualByComparingTo("10");
        assertCode(() -> ret(sold, PaymentMethod.CASH, null, "o4", line(sold, 0, "1", null)), "sale_fully_refunded");
    }

    @Test
    void youCannotReturnMoreThanWasSold() {
        CompletionResult sold = checkout("s2", cart(oil, "3"), cash("3000"));
        ret(sold, PaymentMethod.CASH, null, "a", line(sold, 0, "2", null));
        assertCode(() -> ret(sold, PaymentMethod.CASH, null, "b", line(sold, 0, "2", null)), "return_exceeds_sold");
        assertCode(() -> ret(sold, PaymentMethod.CASH, null, "c",
                List.of(new LineCommand(sold.details().lines().getFirst().getId(), BigDecimal.ONE, null),
                        new LineCommand(sold.details().lines().getFirst().getId(), BigDecimal.ONE, null))),
                "return_exceeds_sold");
        assertThat(balance(oil).getQuantity()).isEqualByComparingTo("9");
    }

    /** Damaged goods: the money goes back, the stock does not, and the cost stays in COGS (§9.3). */
    @Test
    void aDamagedReturnRefundsWithoutRestockingAndGrossProfitReflectsIt() {
        CompletionResult sold = checkout("s3", cart(oil, "4"), cash("4000"));
        ret(sold, PaymentMethod.KBZ_PAY, null, "d", line(sold, 0, "1", false));
        ret(sold, PaymentMethod.CASH, null, "g", line(sold, 0, "1", true));

        assertThat(balance(oil).getQuantity()).isEqualByComparingTo("7"); // 10 − 4 + 1
        assertThat(ledger(oil)).extracting(StockMovement::getType).containsExactly(
                StockMovementType.SALE_RETURN, StockMovementType.SALE, StockMovementType.STOCK_IN);

        // §9.3, verbatim: sales side minus the returns side
        // raw SQL on the app pool: it only sees rows once the connection carries the tenant (RLS)
        Map<String, Object> s = shops.as(shop, () -> jdbc.queryForMap("""
                select sum(sl.line_total - sl.tax_amount) net_revenue, sum(sl.unit_cost * sl.quantity) cogs
                from sale_line sl join sale s on s.id = sl.sale_id
                where s.organization_id = ? and s.status in ('COMPLETED', 'PARTIALLY_REFUNDED', 'REFUNDED')""",
                shop.organizationId()));
        Map<String, Object> r = shops.as(shop, () -> jdbc.queryForMap("""
                select sum(rl.refund_amount - rl.tax_amount) revenue_reversed,
                       sum(case when rl.restock then rl.unit_cost * rl.quantity else 0 end) cogs_reversed
                from sale_return_line rl join sale_return r on r.id = rl.return_id
                where r.organization_id = ?""", shop.organizationId()));
        BigDecimal netRevenue = (BigDecimal) s.get("net_revenue");
        BigDecimal cogs = (BigDecimal) s.get("cogs");
        BigDecimal revenueReversed = (BigDecimal) r.get("revenue_reversed");
        BigDecimal cogsReversed = (BigDecimal) r.get("cogs_reversed");
        assertThat(netRevenue).isEqualByComparingTo("3809.52"); // 4000 − 190.48 tax
        assertThat(cogs).isEqualByComparingTo("2400");
        assertThat(revenueReversed).isEqualByComparingTo("1904.76"); // two units' net revenue came back
        assertThat(cogsReversed).isEqualByComparingTo("600"); // only the restocked unit's cost
        BigDecimal grossProfit = netRevenue.subtract(cogs).subtract(revenueReversed.subtract(cogsReversed));
        // sold 4 (1409.52 margin), gave back the revenue of 2, recovered the cost of 1: 1409.52 − 1904.76 + 600
        assertThat(grossProfit).isEqualByComparingTo("104.76");
    }

    @Test
    void aCashRefundFromTheRegisterComesOutOfTheDrawer() {
        CompletionResult sold = checkout("s4", cart(oil, "4"), cash("4000"));
        ret(sold, PaymentMethod.CASH, shift, "r", line(sold, 0, "2", null));
        ret(sold, PaymentMethod.WAVE_PAY, null, "w", line(sold, 0, "1", null)); // a wallet refund is not drawer cash

        ShiftService.Drawer drawer = shops.as(shop, () -> shiftService.drawer(shift));
        assertThat(drawer.cashSales()).isEqualByComparingTo("4000");
        assertThat(drawer.cashRefunds()).isEqualByComparingTo("2000");
        assertThat(drawer.expectedCash()).isEqualByComparingTo("52000");

        assertCode(() -> ret(sold, PaymentMethod.KBZ_PAY, shift, "x", line(sold, 0, "1", null)), "unexpected_shift");
        shops.run(shop, () -> shiftService.close(shift, new BigDecimal("52000")));
        CompletionResult later = checkout("s5", new CartCommand(main, SaleChannel.ONLINE, null, null, null,
                List.of(new SaleService.LineCommand(webOnly(), BigDecimal.ONE, null))),
                List.of(new PaymentCommand(PaymentMethod.KBZ_PAY, new BigDecimal("15000"), null, "K")));
        assertCode(() -> ret(later, PaymentMethod.CASH, shift, "y", line(later, 0, "1", null)), "shift_closed");
    }

    /** A return on a credit sale never pays out cash: it reduces what the customer owes. */
    @Test
    void aCreditRefundSettlesTheReceivableInstead() {
        UUID ko = customer("Ko Aung", "10000", 30);
        CompletionResult sold = checkout("cr", cart(ko, oil, "3"), credit("3000"));
        Receivable before = shops.as(shop, () -> receivables.forSale(sold.details().sale().getId()));
        assertThat(before.getOutstandingAmount()).isEqualByComparingTo("3000");

        ReturnResult r = ret(sold, PaymentMethod.CREDIT, null, "cr-1", line(sold, 0, "2", null));
        assertThat(r.details().saleReturn().getRefundAmount()).isEqualByComparingTo("2000");

        var details = shops.as(shop, () -> receivables.find(before.getId()));
        assertThat(details.receivable().getOutstandingAmount()).isEqualByComparingTo("1000");
        assertThat(details.receivable().getStatus()).isEqualTo(ReceivableStatus.PARTIALLY_SETTLED);
        assertThat(details.settlements()).singleElement().satisfies(s -> {
            assertThat(s.getMethod()).isEqualTo(PaymentMethod.CREDIT);
            assertThat(s.getReferenceNo()).isEqualTo(r.details().saleReturn().getReturnNumber());
        });
        // the customer repays the rest, then wants the last unit back: nothing is owed, so credit cannot carry it
        shops.run(shop, () -> receivables.settle(before.getId(), new ReceivableService.SettleCommand(
                new BigDecimal("1000"), PaymentMethod.CASH, main, null, null, null, null, null)));
        assertCode(() -> ret(sold, PaymentMethod.CREDIT, null, "cr-2", line(sold, 0, "1", null)),
                "refund_exceeds_outstanding");
        assertThat(ret(sold, PaymentMethod.CASH, null, "cr-3", line(sold, 0, "1", null)).details().saleReturn()
                .getRefundAmount()).isEqualByComparingTo("1000");

        CompletionResult walkIn = checkout("wi", cart(oil, "1"), cash("1000"));
        assertCode(() -> ret(walkIn, PaymentMethod.CREDIT, null, "wi-1", line(walkIn, 0, "1", null)),
                "customer_required");
    }

    @Test
    void aRetriedReturnWithTheSameKeyIsRecordedOnce() {
        CompletionResult sold = checkout("s6", cart(oil, "2"), cash("2000"));
        ReturnResult first = ret(sold, PaymentMethod.CASH, null, "same", line(sold, 0, "1", null));
        ReturnResult again = ret(sold, PaymentMethod.CASH, null, "same", line(sold, 0, "1", null));

        assertThat(again.replayed()).isTrue();
        assertThat(again.details().saleReturn().getId()).isEqualTo(first.details().saleReturn().getId());
        assertThat(balance(oil).getQuantity()).isEqualByComparingTo("9");
        assertThat(ledger(oil)).hasSize(3);
    }

    @Test
    void onlyACompletedSaleIsReturnedAndAnotherShopCannotSeeIt() {
        var held = shops.as(shop, () -> sales.saveCart(cart(oil, "1"), true));
        assertCode(() -> shops.as(shop, () -> returns.create(new ReturnCommand(held.sale().getId(), main, null,
                PaymentMethod.CASH, null, null, List.of(new LineCommand(held.lines().getFirst().getId(),
                        BigDecimal.ONE, null)), null))), "sale_not_completed");

        CompletionResult sold = checkout("s7", cart(oil, "1"), cash("1000"));
        Shop other = shops.newShop();
        assertCode(() -> shops.as(other, () -> returns.create(new ReturnCommand(sold.details().sale().getId(),
                other.mainLocationId(), null, PaymentMethod.CASH, null, null,
                List.of(new LineCommand(sold.details().lines().getFirst().getId(), BigDecimal.ONE, null)), null))),
                "sale_not_found");
        ReturnResult r = ret(sold, PaymentMethod.CASH, null, null, line(sold, 0, "1", null));
        assertCode(() -> shops.as(other, () -> returns.find(r.details().saleReturn().getId())), "return_not_found");
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

    private UUID webOnly() {
        UUID serum = shops.as(shop, () -> productService.create(new ProductCommand(null, null, "Serum", null, null,
                ProductUnit.PIECE, null, null, new BigDecimal("15000"), null, null, null, null, null, true, null,
                null, null)).getId());
        shops.run(shop, () -> documents.createAndPost(stockIn(main, serum, "3", "9000")));
        return serum;
    }

    private CompletionResult checkout(String key, CartCommand cart, List<PaymentCommand> payments) {
        return shops.inTenant(shop, () -> checkout.checkout(key, cart, payments));
    }

    private CartCommand cart(UUID product, String quantity) {
        return new CartCommand(main, SaleChannel.POS, shift, null, null,
                List.of(new SaleService.LineCommand(product, new BigDecimal(quantity), null)));
    }

    private CartCommand cart(UUID customerId, UUID product, String quantity) {
        return new CartCommand(main, SaleChannel.POS, shift, null, null,
                List.of(new SaleService.LineCommand(product, new BigDecimal(quantity), null)), customerId);
    }

    private ReturnResult ret(CompletionResult sold, PaymentMethod method, UUID shiftId, String key,
            List<LineCommand> lines) {
        return shops.as(shop, () -> returns.create(new ReturnCommand(sold.details().sale().getId(), main, shiftId,
                method, null, null, lines, key)));
    }

    private static List<LineCommand> line(CompletionResult sold, int index, String quantity, Boolean restock) {
        return List.of(new LineCommand(sold.details().lines().get(index).getId(), new BigDecimal(quantity), restock));
    }

    private static List<PaymentCommand> cash(String amount) {
        return List.of(new PaymentCommand(PaymentMethod.CASH, new BigDecimal(amount), null, null));
    }

    private static List<PaymentCommand> credit(String amount) {
        return List.of(new PaymentCommand(PaymentMethod.CREDIT, new BigDecimal(amount), null, null));
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
}
