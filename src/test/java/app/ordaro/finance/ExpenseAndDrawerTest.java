package app.ordaro.finance;

import static app.ordaro.inventory.StockPostingTest.stockIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import app.ordaro.catalog.Supplier;
import app.ordaro.catalog.SupplierRepository;
import app.ordaro.crm.Customer;
import app.ordaro.crm.CustomerRepository;
import app.ordaro.crm.CustomerType;
import app.ordaro.finance.ExpenseService.ExpenseCommand;
import app.ordaro.finance.PayableService.PayCommand;
import app.ordaro.finance.ReceivableService.SettleCommand;
import app.ordaro.inventory.StockDocumentService;
import app.ordaro.inventory.StockDocumentService.DocumentCommand;
import app.ordaro.inventory.StockDocumentService.LineCommand;
import app.ordaro.inventory.StockDocumentType;
import app.ordaro.sales.CashierShift;
import app.ordaro.sales.PaymentMethod;
import app.ordaro.sales.PriceType;
import app.ordaro.sales.ReturnService;
import app.ordaro.sales.ReturnService.ReturnCommand;
import app.ordaro.sales.SaleChannel;
import app.ordaro.sales.SaleCheckout;
import app.ordaro.sales.SaleService;
import app.ordaro.sales.SaleService.CartCommand;
import app.ordaro.sales.SaleService.CompletionResult;
import app.ordaro.sales.SaleService.PaymentCommand;
import app.ordaro.sales.ShiftService;
import app.ordaro.sales.ShiftService.Drawer;
import app.ordaro.shared.web.ApiException;
import app.ordaro.support.IntegrationTest;
import app.ordaro.support.Shops;
import app.ordaro.support.Shops.Shop;

/** Step 5d: expenses, and the complete drawer rule (spec §6 "What moves drawer cash"). */
class ExpenseAndDrawerTest extends IntegrationTest {

    @Autowired
    Shops shops;

    @Autowired
    SaleCheckout checkout;

    @Autowired
    ShiftService shiftService;

    @Autowired
    ExpenseService expenses;

    @Autowired
    ExpenseCategoryRepository categories;

    @Autowired
    ReceivableService receivables;

    @Autowired
    PayableService payables;

    @Autowired
    ReturnService returns;

    @Autowired
    CustomerRepository customers;

    @Autowired
    SupplierRepository suppliers;

    @Autowired
    StockDocumentService documents;

    Shop shop;
    UUID main;
    UUID oil;
    UUID shift;
    UUID transport;

    @BeforeEach
    void openShop() {
        shop = shops.newShop();
        main = shop.mainLocationId();
        oil = shops.product(shop, "Cooking Oil 1L");
        shops.run(shop, () -> documents.createAndPost(stockIn(main, oil, "20", "600")));
        shift = shops.as(shop, () -> shiftService.open(main, new BigDecimal("50000")).getId());
        transport = shops.as(shop, () -> categories.save(new ExpenseCategory("Transport")).getId());
    }

    @Test
    void anExpenseIsRecordedAndACashOneFromTheDrawerLowersTheExpectedCash() {
        Expense taxi = record(transport, "3500", ExpenseMethod.CASH, shift);
        record(transport, "12000", ExpenseMethod.KBZ_PAY, null); // paid from the owner's wallet, not the drawer

        assertThat(taxi.getCashierShiftId()).isEqualTo(shift);
        Drawer drawer = shops.as(shop, () -> shiftService.drawer(shift));
        assertThat(drawer.cashExpenses()).isEqualByComparingTo("3500");
        assertThat(drawer.expectedCash()).isEqualByComparingTo("46500");

        assertCode(() -> record(transport, "100", ExpenseMethod.WAVE_PAY, shift), "unexpected_shift");
        assertCode(() -> record(UUID.randomUUID(), "100", ExpenseMethod.CASH, null), "expense_category_not_found");
        assertCode(() -> record(transport, "0", ExpenseMethod.CASH, null), "invalid_amount");
    }

    @Test
    void voidingADrawerExpenseGivesTheCashBackWhileTheShiftIsOpenAndNeverAfter() {
        Expense taxi = record(transport, "3500", ExpenseMethod.CASH, shift);
        shops.run(shop, () -> expenses.voidExpense(taxi.getId()));
        assertThat(shops.as(shop, () -> expenses.find(taxi.getId())).isVoid()).isTrue();
        assertThat(shops.as(shop, () -> shiftService.drawer(shift)).expectedCash()).isEqualByComparingTo("50000");
        assertCode(() -> shops.as(shop, () -> expenses.voidExpense(taxi.getId())), "expense_void");

        Expense rent = record(transport, "1000", ExpenseMethod.CASH, shift);
        shops.run(shop, () -> shiftService.close(shift, new BigDecimal("49000")));
        assertCode(() -> shops.as(shop, () -> expenses.voidExpense(rent.getId())), "shift_closed");
    }

    /** Every cash flow of §6 in one shift, and the close agrees with the running drawer. */
    @Test
    void theDrawerRuleIsComplete() {
        UUID ko = shops.as(shop, () -> {
            Customer c = new Customer("Ko Aung", CustomerType.MEMBER, PriceType.RETAIL);
            c.setCreditLimit(new BigDecimal("20000"));
            return customers.save(c).getId();
        });
        UUID golden = shops.as(shop, () -> suppliers.save(new Supplier("Golden Harvest")).getId());

        // + 50,000 float
        // + cash sales: 3,000 (a wallet sale and a credit sale add nothing)
        CompletionResult cashSale = checkout("c", cart(null, "3"), List.of(
                new PaymentCommand(PaymentMethod.CASH, new BigDecimal("3000"), new BigDecimal("5000"), null)));
        checkout("w", cart(null, "1"), List.of(new PaymentCommand(PaymentMethod.KBZ_PAY, new BigDecimal("1000"),
                null, "K")));
        CompletionResult creditSale = checkout("cr", cart(ko, "5"), List.of(
                new PaymentCommand(PaymentMethod.CREDIT, new BigDecimal("5000"), null, null)));
        // + cash repayment of the credit sale: 2,000
        Receivable owed = shops.as(shop, () -> receivables.forSale(creditSale.details().sale().getId()));
        shops.run(shop, () -> receivables.settle(owed.getId(), new SettleCommand(new BigDecimal("2000"),
                PaymentMethod.CASH, main, shift, null, null, null, null)));
        // − cash refund of one unit of the cash sale: 1,000
        shops.run(shop, () -> returns.create(new ReturnCommand(cashSale.details().sale().getId(), main, shift,
                PaymentMethod.CASH, null, null, List.of(new ReturnService.LineCommand(
                        cashSale.details().lines().getFirst().getId(), BigDecimal.ONE, null)), null)));
        // − cash expense: 3,500 (a voided one adds nothing back)
        record(transport, "3500", ExpenseMethod.CASH, shift);
        Expense mistake = record(transport, "900", ExpenseMethod.CASH, shift);
        shops.run(shop, () -> expenses.voidExpense(mistake.getId()));
        // − supplier paid in cash from the drawer: 6,000 of a 12,000 delivery
        var grn = shops.as(shop, () -> documents.createAndPost(new DocumentCommand(StockDocumentType.STOCK_IN, main,
                null, golden, Instant.parse("2026-06-15T05:00:00Z"), null,
                List.of(new LineCommand(oil, new BigDecimal("20"), new BigDecimal("600"), null)))));
        Payable bill = shops.as(shop, () -> payables.forStockDocument(grn.document().getId()));
        shops.run(shop, () -> payables.pay(bill.getId(), new PayCommand(new BigDecimal("6000"), PaymentMethod.CASH,
                main, shift, null, null, null, null)));

        Drawer drawer = shops.as(shop, () -> shiftService.drawer(shift));
        assertThat(drawer.openingFloat()).isEqualByComparingTo("50000");
        assertThat(drawer.cashSales()).isEqualByComparingTo("3000");
        assertThat(drawer.cashRepayments()).isEqualByComparingTo("2000");
        assertThat(drawer.cashRefunds()).isEqualByComparingTo("1000");
        assertThat(drawer.cashExpenses()).isEqualByComparingTo("3500");
        assertThat(drawer.cashSupplierPayments()).isEqualByComparingTo("6000");
        assertThat(drawer.expectedCash()).isEqualByComparingTo("44500"); // 50000 + 3000 + 2000 − 1000 − 3500 − 6000

        CashierShift closed = shops.as(shop, () -> shiftService.close(shift, new BigDecimal("44000")));
        assertThat(closed.getExpectedCash()).isEqualByComparingTo("44500");
        assertThat(closed.getVariance()).isEqualByComparingTo("-500");
        // the stored figure never changes, whatever happens to the rows later
        assertThat(shops.as(shop, () -> shiftService.drawer(shift)).expectedCash()).isEqualByComparingTo("44500");
    }

    @Test
    void anotherShopCannotSeeOrVoidTheExpense() {
        Expense taxi = record(transport, "3500", ExpenseMethod.CASH, null);
        Shop other = shops.newShop();
        assertCode(() -> shops.as(other, () -> expenses.find(taxi.getId())), "expense_not_found");
        assertCode(() -> shops.as(other, () -> expenses.voidExpense(taxi.getId())), "expense_not_found");
        assertThat(shops.as(other, () -> expenses.between(null, Instant.EPOCH, Instant.now().plusSeconds(60), 50)))
                .isEmpty();
    }

    // ───────────────────────────────────────────────────────────── helpers

    private Expense record(UUID categoryId, String amount, ExpenseMethod method, UUID shiftId) {
        return shops.as(shop, () -> expenses.record(new ExpenseCommand(categoryId, main, shiftId,
                new BigDecimal(amount), method, null, "test", null)));
    }

    private CompletionResult checkout(String key, CartCommand cart, List<PaymentCommand> payments) {
        return shops.inTenant(shop, () -> checkout.checkout(key, cart, payments));
    }

    private CartCommand cart(UUID customerId, String quantity) {
        return new CartCommand(main, SaleChannel.POS, shift, null, null,
                List.of(new SaleService.LineCommand(oil, new BigDecimal(quantity), null)), customerId);
    }

    private static void assertCode(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo(code));
    }
}
