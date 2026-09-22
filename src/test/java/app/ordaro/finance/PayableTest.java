package app.ordaro.finance;

import static app.ordaro.inventory.StockPostingTest.stockIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import app.ordaro.catalog.Supplier;
import app.ordaro.catalog.SupplierRepository;
import app.ordaro.finance.PayableService.ManualCommand;
import app.ordaro.finance.PayableService.PayCommand;
import app.ordaro.finance.PayableService.PaymentResult;
import app.ordaro.inventory.StockBalanceRepository;
import app.ordaro.inventory.StockDocumentService;
import app.ordaro.inventory.StockDocumentService.DocumentCommand;
import app.ordaro.inventory.StockDocumentService.DocumentWithLines;
import app.ordaro.inventory.StockDocumentService.LineCommand;
import app.ordaro.inventory.StockDocumentStatus;
import app.ordaro.inventory.StockDocumentType;
import app.ordaro.sales.PaymentMethod;
import app.ordaro.sales.ShiftService;
import app.ordaro.shared.web.ApiException;
import app.ordaro.support.IntegrationTest;
import app.ordaro.support.Shops;
import app.ordaro.support.Shops.Shop;

/** Step 5b: a posted STOCK_IN opens a payable; payments settle it; voiding follows the payable (spec §7). */
class PayableTest extends IntegrationTest {

    private static final Instant JUNE = Instant.parse("2026-06-15T05:00:00Z");
    private static final ZoneId YANGON = ZoneId.of("Asia/Yangon");

    @Autowired
    Shops shops;

    @Autowired
    StockDocumentService documents;

    @Autowired
    PayableService payables;

    @Autowired
    PayableRepository payableRows;

    @Autowired
    SupplierRepository suppliers;

    @Autowired
    StockBalanceRepository balances;

    @Autowired
    ShiftService shiftService;

    Shop shop;
    UUID main;
    UUID oil;
    UUID rice;
    UUID goldenHarvest;

    @BeforeEach
    void openShop() {
        shop = shops.newShop();
        main = shop.mainLocationId();
        oil = shops.product(shop, "Cooking Oil 1L");
        rice = shops.product(shop, "Rice 5kg");
        goldenHarvest = shops.as(shop, () -> {
            Supplier s = new Supplier("Golden Harvest");
            s.setPaymentTermsDays(30);
            return suppliers.save(s).getId();
        });
    }

    @Test
    void aPostedStockInFromASupplierOpensAPayableForWhatTheGoodsCost() {
        DocumentWithLines grn = post(delivery(goldenHarvest, List.of(
                new LineCommand(oil, new BigDecimal("10"), new BigDecimal("3600"), null),
                new LineCommand(rice, new BigDecimal("2.5"), new BigDecimal("17000.333"), null))));

        Payable payable = payableOf(grn);
        assertThat(payable.getStatus()).isEqualTo(PayableStatus.OPEN);
        assertThat(payable.getSupplierId()).isEqualTo(goldenHarvest);
        assertThat(payable.getLocationId()).isEqualTo(main);
        assertThat(payable.getReferenceNumber()).isEqualTo(grn.document().getDocumentNumber());
        // 36,000 + 42,500.8325 → rounded to the minor unit
        assertThat(payable.getOriginalAmount()).isEqualByComparingTo("78500.83");
        assertThat(payable.getOutstandingAmount()).isEqualByComparingTo("78500.83");
        assertThat(payable.getDueDate())
                .isEqualTo(grn.document().getPostedAt().atZone(YANGON).toLocalDate().plusDays(30));
    }

    @Test
    void aStockInWithoutASupplierOrWithoutCostOpensNothing() {
        DocumentWithLines noSupplier = post(stockIn(main, oil, "5", "1000"));
        assertThat(shops.as(shop, () -> payables.forStockDocument(noSupplier.document().getId()))).isNull();

        DocumentWithLines free = post(delivery(goldenHarvest, List.of(
                new LineCommand(oil, new BigDecimal("5"), BigDecimal.ZERO, null))));
        assertThat(shops.as(shop, () -> payables.forStockDocument(free.document().getId()))).isNull();
        assertThat(shops.as(shop, () -> documents.voidDocument(free.document().getId())).document().getStatus())
                .isEqualTo(StockDocumentStatus.VOID);
    }

    @Test
    void aDraftOpensNothingUntilItIsPosted() {
        DocumentWithLines draft = shops.as(shop, () -> documents.createDraft(delivery(goldenHarvest, List.of(
                new LineCommand(oil, new BigDecimal("10"), new BigDecimal("3600"), null)))));
        assertThat(shops.as(shop, () -> payables.forStockDocument(draft.document().getId()))).isNull();

        shops.run(shop, () -> documents.post(draft.document().getId()));
        assertThat(shops.as(shop, () -> payables.forStockDocument(draft.document().getId())).getOriginalAmount())
                .isEqualByComparingTo("36000");
    }

    @Test
    void paymentsSettleThePayablePartThenWholeAndARetryIsRecordedOnce() {
        Payable payable = payableOf(post(delivery(goldenHarvest, List.of(
                new LineCommand(oil, new BigDecimal("10"), new BigDecimal("3600"), null)))));

        PaymentResult part = pay(payable.getId(), "16000", PaymentMethod.BANK_TRANSFER, null, "p-1");
        assertThat(part.details().payable().getStatus()).isEqualTo(PayableStatus.PARTIALLY_SETTLED);
        assertThat(part.details().payable().getOutstandingAmount()).isEqualByComparingTo("20000");

        PaymentResult again = pay(payable.getId(), "16000", PaymentMethod.BANK_TRANSFER, null, "p-1");
        assertThat(again.replayed()).isTrue();
        assertThat(again.settlement().getId()).isEqualTo(part.settlement().getId());
        assertThat(again.details().payable().getOutstandingAmount()).isEqualByComparingTo("20000");

        assertCode(() -> pay(payable.getId(), "20000.01", PaymentMethod.CASH, null, null), "amount_exceeds_outstanding");
        assertCode(() -> pay(payable.getId(), "1", PaymentMethod.CREDIT, null, null), "invalid_method");

        PaymentResult rest = pay(payable.getId(), "20000", PaymentMethod.CASH, null, "p-2");
        assertThat(rest.details().payable().getStatus()).isEqualTo(PayableStatus.SETTLED);
        assertThat(rest.details().settlements()).hasSize(2);
        assertCode(() -> pay(payable.getId(), "1", PaymentMethod.CASH, null, null), "payable_closed");
    }

    /** Paying the supplier from the till is cash out of that drawer. */
    @Test
    void aSupplierPaidFromTheDrawerLowersTheExpectedCash() {
        Payable payable = payableOf(post(delivery(goldenHarvest, List.of(
                new LineCommand(oil, new BigDecimal("10"), new BigDecimal("3600"), null)))));
        UUID shift = shops.as(shop, () -> shiftService.open(main, new BigDecimal("100000")).getId());

        pay(payable.getId(), "30000", PaymentMethod.CASH, shift, null);
        pay(payable.getId(), "5000", PaymentMethod.KBZ_PAY, shift, null); // a wallet payment is not drawer cash

        ShiftService.Drawer drawer = shops.as(shop, () -> shiftService.drawer(shift));
        assertThat(drawer.cashSupplierPayments()).isEqualByComparingTo("30000");
        assertThat(drawer.expectedCash()).isEqualByComparingTo("70000");
        assertThat(shops.as(shop, () -> shiftService.close(shift, new BigDecimal("70000"))).getVariance())
                .isEqualByComparingTo("0");
        assertCode(() -> pay(payable.getId(), "100", PaymentMethod.CASH, shift, null), "shift_closed");
    }

    @Test
    void voidingAnUnpaidStockInCancelsItsPayable() {
        DocumentWithLines grn = post(delivery(goldenHarvest, List.of(
                new LineCommand(oil, new BigDecimal("10"), new BigDecimal("3600"), null))));

        shops.run(shop, () -> documents.voidDocument(grn.document().getId()));

        Payable payable = payableOf(grn);
        assertThat(payable.getStatus()).isEqualTo(PayableStatus.CANCELLED);
        assertThat(payable.getOutstandingAmount()).isEqualByComparingTo("0");
        assertThat(payable.getCancelledAt()).isNotNull();
        assertThat(onHand(oil)).isEqualByComparingTo("0");
        assertCode(() -> pay(payable.getId(), "1", PaymentMethod.CASH, null, null), "payable_closed");
    }

    /** The goods would be gone while the payment stood; v1 has no supplier refund to net it against. */
    @Test
    void aPaidStockInCannotBeVoided() {
        DocumentWithLines grn = post(delivery(goldenHarvest, List.of(
                new LineCommand(oil, new BigDecimal("10"), new BigDecimal("3600"), null))));
        pay(payableOf(grn).getId(), "1000", PaymentMethod.CASH, null, null);

        assertCode(() -> shops.as(shop, () -> documents.voidDocument(grn.document().getId())), "payable_settled");

        assertThat(shops.as(shop, () -> documents.find(grn.document().getId())).document().getStatus())
                .isEqualTo(StockDocumentStatus.POSTED);
        assertThat(onHand(oil)).isEqualByComparingTo("10");
        assertThat(payableOf(grn).getStatus()).isEqualTo(PayableStatus.PARTIALLY_SETTLED);
    }

    @Test
    void aManualDebtAndTheOverdueList() {
        shops.run(shop, () -> payables.createManual(new ManualCommand(goldenHarvest, main, new BigDecimal("50000"),
                Instant.parse("2026-01-10T03:00:00Z"), null, "January invoices")));
        Payable current = payableOf(post(delivery(goldenHarvest, List.of(
                new LineCommand(oil, new BigDecimal("1"), new BigDecimal("3600"), null)))));

        List<Payable> overdue = shops.as(shop, () -> payables.search(null, true, true, 50));
        assertThat(overdue).extracting(Payable::getSourceType).containsExactly(PayableSourceType.MANUAL);
        assertThat(overdue.getFirst().getDueDate()).isEqualTo(java.time.LocalDate.of(2026, 2, 9)); // + 30 days
        assertThat(shops.as(shop, () -> payables.search(goldenHarvest, true, false, 50)))
                .extracting(Payable::getId).contains(current.getId());
        assertThat(shops.as(shop, () -> payables.search(null, false, false, 50))).hasSize(2);
    }

    @Test
    void anotherShopCannotSeeOrPayTheDebt() {
        Payable payable = payableOf(post(delivery(goldenHarvest, List.of(
                new LineCommand(oil, new BigDecimal("1"), new BigDecimal("3600"), null)))));
        Shop other = shops.newShop();

        assertCode(() -> shops.as(other, () -> payables.find(payable.getId())), "payable_not_found");
        assertCode(() -> shops.as(other, () -> payables.pay(payable.getId(), new PayCommand(BigDecimal.ONE,
                PaymentMethod.CASH, other.mainLocationId(), null, null, null, null, null))), "payable_not_found");
        assertThat(shops.as(other, () -> payables.search(null, false, false, 50))).isEmpty();
    }

    // ───────────────────────────────────────────────────────────── helpers

    private DocumentCommand delivery(UUID supplierId, List<LineCommand> lines) {
        return new DocumentCommand(StockDocumentType.STOCK_IN, main, null, supplierId, JUNE, null, lines);
    }

    private DocumentWithLines post(DocumentCommand command) {
        return shops.as(shop, () -> documents.createAndPost(command));
    }

    private PaymentResult pay(UUID payableId, String amount, PaymentMethod method, UUID shift, String key) {
        return shops.as(shop, () -> payables.pay(payableId, new PayCommand(new BigDecimal(amount), method, main,
                shift, null, null, null, key)));
    }

    private Payable payableOf(DocumentWithLines grn) {
        return shops.as(shop, () -> payableRows.findBySourceTypeAndSourceId(PayableSourceType.STOCK_DOCUMENT,
                grn.document().getId()).orElseThrow());
    }

    private BigDecimal onHand(UUID product) {
        return shops.as(shop, () -> balances.findByLocationIdAndProductId(main, product)
                .map(b -> b.getQuantity()).orElse(BigDecimal.ZERO));
    }

    private static void assertCode(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo(code));
    }
}
