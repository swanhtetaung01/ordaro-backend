package app.trillopos.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;

import app.trillopos.catalog.ProductService;
import app.trillopos.catalog.ProductService.ProductCommand;
import app.trillopos.catalog.ProductUnit;
import app.trillopos.inventory.StockDocumentService.DocumentCommand;
import app.trillopos.inventory.StockDocumentService.DocumentWithLines;
import app.trillopos.inventory.StockDocumentService.LineCommand;
import app.trillopos.inventory.StockDocumentService.OpeningStock;
import app.trillopos.org.LocationType;
import app.trillopos.shared.web.ApiException;
import app.trillopos.support.IntegrationTest;
import app.trillopos.support.Shops;
import app.trillopos.support.Shops.Shop;

/** Spec §5 and §9.1 through the real posting path, one transaction per document. */
public class StockPostingTest extends IntegrationTest {

    /** 2026-06-01 09:30 in Yangon: a fixed business time so document years are deterministic. */
    static final Instant JUNE = Instant.parse("2026-06-01T03:00:00Z");

    @Autowired
    Shops shops;

    @Autowired
    StockDocumentService documents;

    @Autowired
    StockBalanceRepository balances;

    @Autowired
    StockMovementRepository movements;

    @Autowired
    StockBalanceRebuilder rebuilder;

    @Autowired
    ProductService products;

    Shop shop;
    UUID main;
    UUID oil;

    @BeforeEach
    void freshShop() {
        shop = shops.newShop();
        main = shop.mainLocationId();
        oil = shops.product(shop, "Cooking Oil 1L");
    }

    @Test
    void aDraftWritesNothingAndPostingNumbersTheDocument() {
        DocumentWithLines draft = shops.as(shop, () -> documents.createDraft(stockIn(main, oil, "10", "1000")));
        assertThat(draft.document().getStatus()).isEqualTo(StockDocumentStatus.DRAFT);
        assertThat(draft.document().getDocumentNumber()).isNull();
        assertThat(balance(main, oil)).isNull();
        assertThat(ledger(main, oil)).isEmpty();

        DocumentWithLines posted = shops.as(shop, () -> documents.post(draft.document().getId()));
        assertThat(posted.document().getStatus()).isEqualTo(StockDocumentStatus.POSTED);
        assertThat(posted.document().getDocumentNumber()).isEqualTo("MAIN-GRN-26-000001");
        assertThat(post(stockIn(main, oil, "1", "1000")).document().getDocumentNumber())
                .isEqualTo("MAIN-GRN-26-000002");

        assertThatThrownBy(() -> shops.as(shop, () -> documents.post(draft.document().getId())))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("document_not_draft"));
    }

    @Test
    void aDraftCanBeReplacedUntilItIsPosted() {
        UUID rice = shops.product(shop, "Rice");
        DocumentWithLines draft = shops.as(shop, () -> documents.createDraft(stockIn(main, oil, "10", "1000")));
        UUID id = draft.document().getId();

        DocumentWithLines replaced = shops.as(shop, () -> documents.replaceDraft(id, stockIn(main, rice, "4", "2500")));
        assertThat(replaced.lines()).singleElement().satisfies(l -> {
            assertThat(l.getProductId()).isEqualTo(rice);
            assertThat(l.getPosition()).isEqualTo(1);
        });
        shops.run(shop, () -> documents.post(id));

        assertThat(balance(main, rice).getQuantity()).isEqualByComparingTo("4");
        assertThat(balance(main, oil)).isNull();
        assertThatThrownBy(() -> shops.as(shop, () -> documents.replaceDraft(id, stockIn(main, oil, "1", "1"))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("document_not_draft"));
    }

    @Test
    void inflowsBlendAndOutflowsCarryTheAverage() {
        post(stockIn(main, oil, "10", "1000"));
        post(stockIn(main, oil, "5", "1300"));
        post(stockOut(main, oil, "3", StockMovementReason.DAMAGED));

        StockBalance balance = balance(main, oil);
        assertThat(balance.getQuantity()).isEqualByComparingTo("12");
        assertThat(balance.getAverageCost()).isEqualByComparingTo("1100");

        List<StockMovement> ledger = ledgerOldestFirst(main, oil);
        assertThat(ledger).extracting(StockMovement::getType).containsExactly(StockMovementType.STOCK_IN,
                StockMovementType.STOCK_IN, StockMovementType.STOCK_OUT);
        assertThat(ledger).extracting(m -> m.getQuantity().stripTrailingZeros().toPlainString())
                .containsExactly("10", "5", "-3");
        assertThat(ledger).extracting(m -> m.getBalanceAfter().stripTrailingZeros().toPlainString())
                .containsExactly("10", "15", "12");
        assertThat(ledger).extracting(StockMovement::getSeq).containsExactly(1L, 2L, 3L);
        assertThat(balance(main, oil).getLastSeq()).isEqualTo(3L);
        // the outflow consumed the average, not either purchase price
        assertThat(ledger.get(2).getUnitCost()).isEqualByComparingTo("1100");
        assertThat(ledger.get(2).getReason()).isEqualTo(StockMovementReason.DAMAGED);
        assertThat(ledger.get(0).getCreatedBy()).isEqualTo(shop.membershipId());
    }

    @Test
    void anOutflowBeyondStockIsRefusedAndBurnsNoNumber() {
        post(stockIn(main, oil, "2", "1000"));
        assertThatThrownBy(() -> post(stockOut(main, oil, "3", StockMovementReason.THEFT)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("insufficient_stock"));

        assertThat(balance(main, oil).getQuantity()).isEqualByComparingTo("2");
        assertThat(ledger(main, oil)).hasSize(1);
        // the refused posting rolled its number back: the book does not skip
        assertThat(post(stockOut(main, oil, "1", StockMovementReason.THEFT)).document().getDocumentNumber())
                .isEqualTo("MAIN-OUT-26-000001");
    }

    @Test
    void withNegativeStockAllowedAnOversellGoesNegativeAndTheNextInflowSetsTheAverage() {
        shops.allowNegativeStock(shop, true);
        post(stockIn(main, oil, "2", "900"));
        post(stockOut(main, oil, "6", StockMovementReason.INTERNAL_USE));
        assertThat(balance(main, oil).getQuantity()).isEqualByComparingTo("-4");
        assertThat(balance(main, oil).getAverageCost()).isEqualByComparingTo("900");

        post(stockIn(main, oil, "10", "1200"));
        assertThat(balance(main, oil).getQuantity()).isEqualByComparingTo("6");
        assertThat(balance(main, oil).getAverageCost()).isEqualByComparingTo("1200");
    }

    /** The adjustment trap: a count of 39 against 42 stores −3, not 39. */
    @Test
    void anAdjustmentStoresTheSignedDifference() {
        post(stockIn(main, oil, "42", "500"));
        post(adjustment(main, oil, "-3", null, StockMovementReason.COUNT_CORRECTION));
        assertThat(balance(main, oil).getQuantity()).isEqualByComparingTo("39");
        assertThat(balance(main, oil).getAverageCost()).isEqualByComparingTo("500");

        post(adjustment(main, oil, "1", "800", StockMovementReason.COUNT_CORRECTION));
        assertThat(balance(main, oil).getQuantity()).isEqualByComparingTo("40");
        assertThat(balance(main, oil).getAverageCost()).isEqualByComparingTo("507.5");

        assertThatThrownBy(() -> post(adjustment(main, oil, "-1", null, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("reason_required"));
        assertThatThrownBy(() -> post(adjustment(main, oil, "2", null, StockMovementReason.COUNT_CORRECTION)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("unit_cost_required"));
    }

    @Test
    void aTransferMovesStockAtTheSourceCostAndConservesValue() {
        UUID warehouse = shops.location(shop, "WH", LocationType.WAREHOUSE);
        post(stockIn(main, oil, "10", "1000"));
        post(stockIn(warehouse, oil, "10", "1600"));

        DocumentWithLines transfer = post(transfer(main, warehouse, oil, "5"));
        assertThat(transfer.document().getDocumentNumber()).isEqualTo("MAIN-TFR-26-000001");

        assertThat(balance(main, oil).getQuantity()).isEqualByComparingTo("5");
        assertThat(balance(main, oil).getAverageCost()).isEqualByComparingTo("1000");
        assertThat(balance(warehouse, oil).getQuantity()).isEqualByComparingTo("15");
        // 10 @ 1600 + 5 @ 1000 (the source's cost) = 21,000 / 15
        assertThat(balance(warehouse, oil).getAverageCost()).isEqualByComparingTo("1400");

        BigDecimal before = new BigDecimal("26000");
        BigDecimal after = value(balance(main, oil)).add(value(balance(warehouse, oil)));
        assertThat(after).isEqualByComparingTo(before);

        List<StockMovement> legs = shops.as(shop,
                () -> movements.findByReference(StockReferenceType.STOCK_DOCUMENT, transfer.document().getId()));
        assertThat(legs).extracting(StockMovement::getType)
                .containsExactlyInAnyOrder(StockMovementType.TRANSFER_OUT, StockMovementType.TRANSFER_IN);
        StockMovement in = legs.stream().filter(m -> m.getType() == StockMovementType.TRANSFER_IN).findFirst()
                .orElseThrow();
        assertThat(in.getLocationId()).isEqualTo(warehouse);
        assertThat(in.getUnitCost()).isEqualByComparingTo("1000");
        // each (location, product) has its own sequence: both legs are the 2nd movement at their location
        assertThat(legs).extracting(StockMovement::getSeq).containsOnly(2L);
    }

    @Test
    void aVoidReversesAtTheCurrentAverageNotAsAnExactUndo() {
        DocumentWithLines first = post(stockIn(main, oil, "10", "1000"));
        post(stockIn(main, oil, "10", "2000"));
        post(stockOut(main, oil, "5", StockMovementReason.SAMPLE));
        assertThat(balance(main, oil).getAverageCost()).isEqualByComparingTo("1500");

        DocumentWithLines voided = shops.as(shop, () -> documents.voidDocument(first.document().getId()));
        assertThat(voided.document().getStatus()).isEqualTo(StockDocumentStatus.VOID);

        List<StockMovement> reversal = shops.as(shop,
                () -> movements.findByReference(StockReferenceType.STOCK_DOCUMENT, first.document().getId()));
        assertThat(reversal).hasSize(2);
        StockMovement undo = reversal.get(1);
        assertThat(undo.getType()).isEqualTo(StockMovementType.STOCK_OUT);
        assertThat(undo.getQuantity()).isEqualByComparingTo("-10");
        assertThat(undo.getUnitCost()).isEqualByComparingTo("1500");
        assertThat(undo.getReason()).isEqualTo(StockMovementReason.VOID_REVERSAL);
        assertThat(undo.getReferenceNumber()).isEqualTo(first.document().getDocumentNumber());

        assertThat(balance(main, oil).getQuantity()).isEqualByComparingTo("5");
        assertThat(balance(main, oil).getAverageCost()).isEqualByComparingTo("1500");

        assertThatThrownBy(() -> shops.as(shop, () -> documents.voidDocument(first.document().getId())))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("document_void"));
    }

    @Test
    void aVoidThatWouldTakeStockNegativeIsRefused() {
        DocumentWithLines received = post(stockIn(main, oil, "10", "1000"));
        post(stockOut(main, oil, "8", StockMovementReason.DAMAGED));

        assertThatThrownBy(() -> shops.as(shop, () -> documents.voidDocument(received.document().getId())))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("insufficient_stock"));
        assertThat(balance(main, oil).getQuantity()).isEqualByComparingTo("2");
    }

    @Test
    void voidingATransferReversesBothLegs() {
        UUID warehouse = shops.location(shop, "WH", LocationType.WAREHOUSE);
        post(stockIn(main, oil, "10", "1000"));
        DocumentWithLines transfer = post(transfer(main, warehouse, oil, "4"));

        shops.run(shop, () -> documents.voidDocument(transfer.document().getId()));

        assertThat(balance(main, oil).getQuantity()).isEqualByComparingTo("10");
        assertThat(balance(warehouse, oil).getQuantity()).isEqualByComparingTo("0");
        assertThat(balance(main, oil).getAverageCost()).isEqualByComparingTo("1000");
    }

    @Test
    void voidingADraftWritesNoMovements() {
        DocumentWithLines draft = shops.as(shop, () -> documents.createDraft(stockIn(main, oil, "10", "1000")));
        shops.run(shop, () -> documents.voidDocument(draft.document().getId()));
        assertThat(ledger(main, oil)).isEmpty();
        assertThatThrownBy(() -> shops.as(shop, () -> documents.post(draft.document().getId())))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void openingStockFromTheAddProductFormIsAnAutoPostedOpeningDocument() {
        UUID rice = shops.product(shop, "Rice 5kg",
                List.of(new OpeningStock(main, new BigDecimal("24"), new BigDecimal("17500"))));

        StockBalance balance = balance(main, rice);
        assertThat(balance.getQuantity()).isEqualByComparingTo("24");
        assertThat(balance.getAverageCost()).isEqualByComparingTo("17500");
        StockMovement opening = ledgerOldestFirst(main, rice).getFirst();
        assertThat(opening.getType()).isEqualTo(StockMovementType.OPENING);
        assertThat(opening.getReferenceNumber()).startsWith("MAIN-OPN-");
    }

    /** Asia/Yangon is UTC+6:30: 17:15Z on 31 Dec is 23:45 local, 17:45Z is already next year. */
    @Test
    void theNumberYearIsTheBusinessYearInTheOrganizationsTimezone() {
        Instant lastMinutesOf2026 = Instant.parse("2026-12-31T17:15:00Z");
        Instant firstMinutesOf2027 = Instant.parse("2026-12-31T17:45:00Z");
        assertThat(post(stockIn(main, oil, "1", "1000", lastMinutesOf2026)).document().getDocumentNumber())
                .isEqualTo("MAIN-GRN-26-000001");
        assertThat(post(stockIn(main, oil, "1", "1000", firstMinutesOf2027)).document().getDocumentNumber())
                .isEqualTo("MAIN-GRN-27-000001");
    }

    @Test
    void theBusinessTimeIsMovedAtButNotTheLedgerOrder() {
        Instant saturday = Instant.parse("2026-05-30T04:00:00Z");
        post(stockIn(main, oil, "10", "1000", JUNE));
        post(stockIn(main, oil, "10", "3000", saturday)); // entered later, dated earlier

        List<StockMovement> ledger = ledgerOldestFirst(main, oil);
        assertThat(ledger.get(1).getMovedAt()).isEqualTo(saturday);
        assertThat(ledger).extracting(m -> m.getBalanceAfter().stripTrailingZeros().toPlainString())
                .containsExactly("10", "20");
        assertThat(balance(main, oil).getLastMovementAt()).isEqualTo(JUNE);
        assertThat(shops.as(shop, () -> rebuilder.verify()).clean()).isTrue();
    }

    @Test
    void documentsAreValidatedAgainstTheirType() {
        UUID deliveryFee = shops.as(shop, () -> products.create(new ProductCommand(null, null, "Delivery fee", null,
                null, ProductUnit.PIECE, null, null, new BigDecimal("2000"), null, null, false, null, null, null,
                null, null, null)).getId());
        assertRejected(stockIn(main, deliveryFee, "1", "0"), "product_not_stockable");
        assertRejected(new DocumentCommand(StockDocumentType.TRANSFER, main, null, null, JUNE, null,
                List.of(new LineCommand(oil, BigDecimal.ONE, null, null))), "destination_required");
        assertRejected(new DocumentCommand(StockDocumentType.TRANSFER, main, main, null, JUNE, null,
                List.of(new LineCommand(oil, BigDecimal.ONE, null, null))), "same_location");
        assertRejected(stockIn(main, oil, "-1", "1000"), "invalid_quantity");
        assertRejected(stockIn(main, oil, "1.00001", "1000"), "invalid_quantity");
        assertRejected(stockIn(main, oil, "1", null), "unit_cost_required");
        assertRejected(new DocumentCommand(StockDocumentType.STOCK_OUT, main, null, null, JUNE, null,
                List.of(new LineCommand(oil, BigDecimal.ONE, null, StockMovementReason.VOID_REVERSAL))),
                "invalid_reason");
        assertRejected(new DocumentCommand(StockDocumentType.STOCK_IN, main, null, null, JUNE, null, List.of()),
                "invalid_lines");
        assertRejected(stockIn(main, UUID.randomUUID(), "1", "1000"), "product_not_found");
    }

    // ───────────────────────────────────────────────────────────── helpers

    private void assertRejected(DocumentCommand command, String code) {
        assertThatThrownBy(() -> shops.as(shop, () -> documents.createAndPost(command)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    private DocumentWithLines post(DocumentCommand command) {
        return shops.as(shop, () -> documents.createAndPost(command));
    }

    private StockBalance balance(UUID location, UUID product) {
        return shops.as(shop, () -> balances.findByLocationIdAndProductId(location, product).orElse(null));
    }

    private List<StockMovement> ledger(UUID location, UUID product) {
        return shops.as(shop, () -> movements.ledger(location, product, Pageable.unpaged()));
    }

    private List<StockMovement> ledgerOldestFirst(UUID location, UUID product) {
        return ledger(location, product).reversed();
    }

    private static BigDecimal value(StockBalance balance) {
        return balance.getQuantity().multiply(balance.getAverageCost());
    }

    public static DocumentCommand stockIn(UUID location, UUID product, String quantity, String unitCost) {
        return stockIn(location, product, quantity, unitCost, JUNE);
    }

    public static DocumentCommand stockIn(UUID location, UUID product, String quantity, String unitCost, Instant at) {
        return new DocumentCommand(StockDocumentType.STOCK_IN, location, null, null, at, null, List.of(
                new LineCommand(product, new BigDecimal(quantity), unitCost == null ? null : new BigDecimal(unitCost),
                        null)));
    }

    static DocumentCommand stockOut(UUID location, UUID product, String quantity, StockMovementReason reason) {
        return new DocumentCommand(StockDocumentType.STOCK_OUT, location, null, null, JUNE, null,
                List.of(new LineCommand(product, new BigDecimal(quantity), null, reason)));
    }

    static DocumentCommand adjustment(UUID location, UUID product, String difference, String unitCost,
            StockMovementReason reason) {
        return new DocumentCommand(StockDocumentType.ADJUSTMENT, location, null, null, JUNE, null, List.of(
                new LineCommand(product, new BigDecimal(difference),
                        unitCost == null ? null : new BigDecimal(unitCost), reason)));
    }

    static DocumentCommand transfer(UUID from, UUID to, UUID product, String quantity) {
        return new DocumentCommand(StockDocumentType.TRANSFER, from, to, null, JUNE, null,
                List.of(new LineCommand(product, new BigDecimal(quantity), null, null)));
    }
}
