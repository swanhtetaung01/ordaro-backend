package app.ordaro.inventory;

import static app.ordaro.inventory.StockPostingTest.adjustment;
import static app.ordaro.inventory.StockPostingTest.stockIn;
import static app.ordaro.inventory.StockPostingTest.stockOut;
import static app.ordaro.inventory.StockPostingTest.transfer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import app.ordaro.inventory.StockBalanceRebuilder.Report;
import app.ordaro.inventory.StockDocumentService.DocumentCommand;
import app.ordaro.inventory.StockDocumentService.DocumentWithLines;
import app.ordaro.org.LocationType;
import app.ordaro.shared.web.ApiException;
import app.ordaro.support.IntegrationTest;
import app.ordaro.support.Shops;
import app.ordaro.support.Shops.Shop;
import app.ordaro.support.TestDatabase;

/** The ledger is append-only, the projection is rebuildable, and concurrency cannot corrupt either. */
class LedgerIntegrityTest extends IntegrationTest {

    @Autowired
    Shops shops;

    @Autowired
    StockDocumentService documents;

    @Autowired
    StockBalanceRepository balances;

    @Autowired
    StockBalanceRebuilder rebuilder;

    Shop shop;
    UUID main;

    @BeforeEach
    void freshShop() {
        shop = shops.newShop();
        main = shop.mainLocationId();
    }

    @Test
    void theLedgerRefusesUpdateDeleteAndTruncateEvenForTheOwner() throws SQLException {
        UUID oil = shops.product(shop, "Oil");
        shops.run(shop, () -> documents.createAndPost(stockIn(main, oil, "5", "1000")));

        try (Connection app = TestDatabase.connectAsApp(); Statement s = app.createStatement()) {
            assertThatThrownBy(() -> s.executeUpdate("update stock_movement set quantity = 500"))
                    .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> s.executeUpdate("delete from stock_movement"))
                    .hasMessageContaining("permission denied");
        }
        try (Connection owner = TestDatabase.connectAsOwner(); Statement s = owner.createStatement()) {
            assertThatThrownBy(() -> s.executeUpdate("update stock_movement set quantity = 500"))
                    .hasMessageContaining("append-only");
            assertThatThrownBy(() -> s.executeUpdate("delete from stock_movement"))
                    .hasMessageContaining("append-only");
            assertThatThrownBy(() -> s.execute("truncate stock_movement"))
                    .hasMessageContaining("append-only");
        }
    }

    /**
     * Random documents across three locations and five products, including refused outflows
     * and voids. Afterwards: per product the balances sum to the ledger; every transfer and
     * every void is value-neutral where it should be; and the rebuild job finds nothing.
     */
    @Test
    void aRandomHistoryLeavesTheProjectionEqualToTheLedger() throws SQLException {
        Random random = new Random(20260921L);
        List<UUID> locations = List.of(main, shops.location(shop, "WH", LocationType.WAREHOUSE),
                shops.location(shop, "B2", LocationType.STORE));
        List<UUID> products = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            products.add(shops.product(shop, "Product " + i));
        }
        List<UUID> posted = new ArrayList<>();
        int refused = 0;

        for (int i = 0; i < 200; i++) {
            UUID location = pick(random, locations);
            UUID product = pick(random, products);
            DocumentCommand command = switch (random.nextInt(10)) {
                case 0, 1, 2, 3 -> stockIn(location, product, qty(random), cost(random));
                case 4, 5 -> stockOut(location, product, qty(random), StockMovementReason.DAMAGED);
                case 6 -> adjustment(location, product, random.nextBoolean() ? "-" + qty(random) : qty(random),
                        cost(random), StockMovementReason.COUNT_CORRECTION);
                case 7, 8 -> transfer(location, otherThan(random, locations, location), product, qty(random));
                default -> null;
            };
            try {
                if (command == null && !posted.isEmpty()) {
                    UUID victim = posted.remove(random.nextInt(posted.size()));
                    shops.run(shop, () -> documents.voidDocument(victim));
                } else if (command != null) {
                    posted.add(shops.as(shop, () -> documents.createAndPost(command)).document().getId());
                }
            } catch (ApiException e) {
                assertThat(e.code()).isEqualTo("insufficient_stock");
                refused++;
            }
        }
        assertThat(refused).as("the history should include refused outflows").isPositive();

        try (Connection owner = TestDatabase.connectAsOwner()) {
            // Σ balances = Σ ledger, per product
            assertThat(rows(owner, """
                    select b.product_id from
                      (select product_id, sum(quantity) q from stock_balance where organization_id = ? group by 1) b
                    full join
                      (select product_id, sum(quantity) q from stock_movement where organization_id = ? group by 1) m
                    using (product_id)
                    where coalesce(b.q, 0) <> coalesce(m.q, 0)""")).isEmpty();
            // a transfer is value-neutral: the IN leg is priced at what the OUT leg consumed
            assertThat(rows(owner, """
                    select d.id from stock_document d
                    join stock_movement m on m.reference_id = d.id and m.type in ('TRANSFER_OUT', 'TRANSFER_IN')
                    where d.organization_id = ? and d.type = 'TRANSFER' and m.reason is null
                      and m.organization_id = ?
                    group by d.id having sum(m.quantity * m.unit_cost) <> 0""")).isEmpty();
            // a void puts the quantity back exactly: every VOID document nets to zero
            assertThat(rows(owner, """
                    select d.id from stock_document d
                    join stock_movement m on m.reference_id = d.id
                    where d.organization_id = ? and d.status = 'VOID' and m.organization_id = ?
                    group by d.id having sum(m.quantity) <> 0""")).isEmpty();
        }

        Report report = shops.as(shop, () -> rebuilder.verify());
        assertThat(report.mismatches()).isEmpty();
        assertThat(report.movementsReplayed()).isGreaterThan(150);
    }

    /**
     * Ledger order is {@code seq}, not the clock. A second app instance with a slow clock can write
     * a later movement with an earlier {@code created_at}; the replay must not care.
     */
    @Test
    void theLedgerIsOrderedBySeqEvenWhenCreatedAtIsOutOfOrder() throws SQLException {
        UUID oil = shops.product(shop, "Oil");
        shops.run(shop, () -> documents.createAndPost(stockIn(main, oil, "10", "1000")));
        shops.run(shop, () -> documents.createAndPost(stockOut(main, oil, "5", StockMovementReason.DAMAGED)));
        shops.run(shop, () -> documents.createAndPost(stockIn(main, oil, "10", "2000")));

        try (Connection owner = TestDatabase.connectAsOwner(); Statement s = owner.createStatement()) {
            s.execute("alter table stock_movement disable trigger stock_movement_no_update_or_delete");
            try (PreparedStatement skew = owner.prepareStatement("""
                    update stock_movement set created_at = created_at - interval '1 hour'
                    where product_id = ? and seq = 3""")) {
                skew.setObject(1, oil);
                assertThat(skew.executeUpdate()).isEqualTo(1);
            } finally {
                s.execute("alter table stock_movement enable trigger stock_movement_no_update_or_delete");
            }
            // the clock now puts the third posting first
            try (PreparedStatement byClock = owner.prepareStatement(
                    "select seq from stock_movement where product_id = ? order by created_at, id")) {
                byClock.setObject(1, oil);
                List<Long> order = new ArrayList<>();
                try (ResultSet rs = byClock.executeQuery()) {
                    while (rs.next()) {
                        order.add(rs.getLong(1));
                    }
                }
                assertThat(order).containsExactly(3L, 1L, 2L);
            }
        }

        // by seq: 10 @ 1000, −5, +10 @ 2000 → (5 × 1000 + 10 × 2000) / 15
        StockBalanceRebuilder.Replayed replayed = shops.as(shop, () -> rebuilder.replay(main, oil));
        assertThat(replayed.quantity()).isEqualByComparingTo("15");
        assertThat(replayed.averageCost()).isEqualByComparingTo("1666.6667");
        assertThat(replayed.balanceAfterErrors()).isZero();
        assertThat(replayed.sequenceErrors()).isZero();
        assertThat(shops.as(shop, () -> rebuilder.verify()).clean()).isTrue();

        // …whereas replaying by the clock would have blended before the outflow: 1500, a different answer
        BigDecimal byClock = WeightedAverage.afterInflow(new BigDecimal("10"), new BigDecimal("1000"),
                new BigDecimal("10"), new BigDecimal("2000"));
        assertThat(byClock).isNotEqualByComparingTo(replayed.averageCost());
    }

    @Test
    void theRebuildJobFindsDriftAndRepairsIt() throws SQLException {
        UUID oil = shops.product(shop, "Oil");
        shops.run(shop, () -> documents.createAndPost(stockIn(main, oil, "10", "1000")));
        shops.run(shop, () -> documents.createAndPost(stockIn(main, oil, "10", "1300")));
        assertThat(shops.as(shop, () -> rebuilder.verify()).clean()).isTrue();

        try (Connection owner = TestDatabase.connectAsOwner();
                PreparedStatement drift = owner.prepareStatement(
                        "update stock_balance set quantity = 99, average_cost = 1 where product_id = ?")) {
            drift.setObject(1, oil);
            assertThat(drift.executeUpdate()).isEqualTo(1);
        }

        Report found = shops.as(shop, () -> rebuilder.verify());
        assertThat(found.mismatches()).singleElement().satisfies(m -> {
            assertThat(m.storedQuantity()).isEqualByComparingTo("99");
            assertThat(m.expectedQuantity()).isEqualByComparingTo("20");
            assertThat(m.expectedAverageCost()).isEqualByComparingTo("1150");
            assertThat(m.balanceAfterErrors()).isZero();
        });

        Report repaired = shops.as(shop, () -> rebuilder.repair());
        assertThat(repaired.repaired()).isEqualTo(1);
        assertThat(shops.as(shop, () -> rebuilder.verify()).clean()).isTrue();
        StockBalance fixed = shops.as(shop, () -> balances.findByLocationIdAndProductId(main, oil).orElseThrow());
        assertThat(fixed.getQuantity()).isEqualByComparingTo("20");
        assertThat(fixed.getAverageCost()).isEqualByComparingTo("1150");
    }

    /** Two first-ever postings of a new product race on creating its balance row; both must land. */
    @Test
    void concurrentFirstPostingsOfANewProductBothSucceed() throws Exception {
        UUID fresh = shops.product(shop, "Brand new");
        List<Callable<String>> tasks = Collections.nCopies(2,
                () -> shops.as(shop, () -> documents.createAndPost(stockIn(main, fresh, "5", "1000")))
                        .document().getDocumentNumber());

        List<String> numbers = runTogether(tasks);

        assertThat(numbers).doesNotContainNull().containsExactlyInAnyOrder("MAIN-GRN-26-000001", "MAIN-GRN-26-000002");
        StockBalance balance = shops.as(shop, () -> balances.findByLocationIdAndProductId(main, fresh).orElseThrow());
        assertThat(balance.getQuantity()).isEqualByComparingTo("10");
        assertThat(shops.as(shop, () -> rebuilder.verify()).clean()).isTrue();
    }

    /** Eight cashiers-worth of stock-outs against 10 on hand: exactly five of 2 fit, none oversell. */
    @Test
    void concurrentOutflowsNeverOversellAndNumbersStayGapless() throws Exception {
        UUID oil = shops.product(shop, "Oil");
        shops.run(shop, () -> documents.createAndPost(stockIn(main, oil, "10", "1000")));

        List<Callable<String>> tasks = Collections.nCopies(8, () -> {
            try {
                return shops.as(shop, () -> documents.createAndPost(stockOut(main, oil, "2",
                        StockMovementReason.DAMAGED))).document().getDocumentNumber();
            } catch (ApiException e) {
                assertThat(e.code()).isEqualTo("insufficient_stock");
                return null;
            }
        });

        List<String> numbers = runTogether(tasks).stream().filter(n -> n != null).sorted().toList();

        assertThat(numbers).containsExactly("MAIN-OUT-26-000001", "MAIN-OUT-26-000002", "MAIN-OUT-26-000003",
                "MAIN-OUT-26-000004", "MAIN-OUT-26-000005");
        StockBalance balance = shops.as(shop, () -> balances.findByLocationIdAndProductId(main, oil).orElseThrow());
        assertThat(balance.getQuantity()).isEqualByComparingTo("0");
        assertThat(shops.as(shop, () -> rebuilder.verify()).clean()).isTrue();
    }

    // ───────────────────────────────────────────────────────────── helpers

    private List<String> runTogether(List<Callable<String>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (Callable<String> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<String> results = new ArrayList<>();
            for (Future<String> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private List<Object> rows(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, shop.organizationId());
            statement.setObject(2, shop.organizationId());
            List<Object> found = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    found.add(rs.getObject(1));
                }
            }
            return found;
        }
    }

    private static <T> T pick(Random random, List<T> items) {
        return items.get(random.nextInt(items.size()));
    }

    private static UUID otherThan(Random random, List<UUID> items, UUID not) {
        UUID other;
        do {
            other = pick(random, items);
        } while (other.equals(not));
        return other;
    }

    private static String qty(Random random) {
        // quarter units, up to 20: exercises 4-decimal quantities and repeating averages
        return new BigDecimal(1 + random.nextInt(80)).divide(new BigDecimal(4)).toPlainString();
    }

    private static String cost(Random random) {
        return String.valueOf(500 + random.nextInt(2500));
    }
}
