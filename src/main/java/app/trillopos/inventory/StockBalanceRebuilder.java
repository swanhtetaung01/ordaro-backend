package app.trillopos.inventory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.trillopos.shared.tenant.TenantContext;

/**
 * The StockBalance rebuild job (spec §9 Implementation notes): replays the ledger per
 * (location, product) in ledger order {@code seq}, applying §9.1 on inflows and
 * carrying the average through outflows — the same {@link WeightedAverage} the live path uses —
 * and compares the result with the stored projection. It also checks every row's
 * {@code balanceAfter}, that {@code seq} runs 1, 2, 3 … without gaps, and that the balance's
 * {@code last_seq} is the last of them. {@link #verify} only reports; {@link #repair} rewrites drifted balances
 * under their row lock (movements are immutable, so a wrong {@code balanceAfter} is reported,
 * never rewritten).
 *
 * <p>Native SQL, always filtered by the current organization (spec §1 Tenant enforcement).
 */
@Service
public class StockBalanceRebuilder {

    public record Replayed(BigDecimal quantity, BigDecimal averageCost, int movements, int balanceAfterErrors,
            int sequenceErrors) {
    }

    public record Mismatch(UUID locationId, UUID productId, BigDecimal storedQuantity, BigDecimal expectedQuantity,
            BigDecimal storedAverageCost, BigDecimal expectedAverageCost, int balanceAfterErrors,
            int sequenceErrors) {
    }

    public record Report(int balancesChecked, int movementsReplayed, List<Mismatch> mismatches, int repaired) {

        public boolean clean() {
            return mismatches.isEmpty();
        }
    }

    private record Key(UUID locationId, UUID productId) {
    }

    private static final class State {
        BigDecimal quantity = BigDecimal.ZERO;
        BigDecimal averageCost = BigDecimal.ZERO;
        int movements;
        int balanceAfterErrors;
        int sequenceErrors;

        void apply(long seq, BigDecimal q, BigDecimal unitCost, BigDecimal balanceAfter) {
            if (q.signum() > 0) {
                averageCost = WeightedAverage.afterInflow(quantity, averageCost, q, unitCost);
            }
            quantity = quantity.add(q);
            movements++;
            if (seq != movements) {
                sequenceErrors++;
            }
            if (quantity.compareTo(balanceAfter) != 0) {
                balanceAfterErrors++;
            }
        }

        Replayed result() {
            return new Replayed(quantity, averageCost, movements, balanceAfterErrors, sequenceErrors);
        }
    }

    private final JdbcTemplate jdbc;
    private final StockBalanceRepository balances;

    public StockBalanceRebuilder(JdbcTemplate jdbc, StockBalanceRepository balances) {
        this.jdbc = jdbc;
        this.balances = balances;
    }

    @Transactional(readOnly = true)
    public Report verify() {
        return compare(replayAll(), 0);
    }

    /** Verifies, then rewrites each drifted balance from a fresh replay taken under its lock. */
    @Transactional
    public Report repair() {
        Report found = compare(replayAll(), 0);
        int repaired = 0;
        for (Mismatch mismatch : found.mismatches()) {
            StockBalance balance = balances.lockOne(mismatch.locationId(), mismatch.productId()).orElse(null);
            if (balance == null) {
                continue;
            }
            Replayed truth = replay(mismatch.locationId(), mismatch.productId());
            balance.correctTo(truth.quantity(), truth.averageCost());
            repaired++;
        }
        return new Report(found.balancesChecked(), found.movementsReplayed(), found.mismatches(), repaired);
    }

    /** One key's replay; the live-path tests use it to check each posting. */
    @Transactional(readOnly = true)
    public Replayed replay(UUID locationId, UUID productId) {
        State state = new State();
        jdbc.query("""
                select seq, quantity, unit_cost, balance_after
                from stock_movement
                where organization_id = ? and location_id = ? and product_id = ?
                order by seq
                """, rs -> {
            state.apply(rs.getLong(1), rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getBigDecimal(4));
        }, TenantContext.requireOrganizationId(), locationId, productId);
        return state.result();
    }

    private Map<Key, State> replayAll() {
        Map<Key, State> states = new LinkedHashMap<>();
        jdbc.query("""
                select location_id, product_id, seq, quantity, unit_cost, balance_after
                from stock_movement
                where organization_id = ?
                order by location_id, product_id, seq
                """, rs -> {
            Key key = new Key(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class));
            states.computeIfAbsent(key, k -> new State())
                    .apply(rs.getLong(3), rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getBigDecimal(6));
        }, TenantContext.requireOrganizationId());
        return states;
    }

    private Report compare(Map<Key, State> replayed, int repaired) {
        List<Mismatch> mismatches = new ArrayList<>();
        Map<Key, Boolean> seen = new LinkedHashMap<>();
        int[] checked = {0};
        jdbc.query("""
                select location_id, product_id, quantity, average_cost, last_seq
                from stock_balance
                where organization_id = ?
                """, rs -> {
            Key key = new Key(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class));
            checked[0]++;
            seen.put(key, true);
            Replayed truth = replayed.containsKey(key) ? replayed.get(key).result()
                    : new Replayed(BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, 0);
            BigDecimal storedQuantity = rs.getBigDecimal(3);
            BigDecimal storedAverage = rs.getBigDecimal(4);
            int sequenceErrors = truth.sequenceErrors() + (rs.getLong(5) == truth.movements() ? 0 : 1);
            if (storedQuantity.compareTo(truth.quantity()) != 0 || storedAverage.compareTo(truth.averageCost()) != 0
                    || truth.balanceAfterErrors() > 0 || sequenceErrors > 0) {
                mismatches.add(new Mismatch(key.locationId(), key.productId(), storedQuantity, truth.quantity(),
                        storedAverage, truth.averageCost(), truth.balanceAfterErrors(), sequenceErrors));
            }
        }, TenantContext.requireOrganizationId());
        replayed.forEach((key, state) -> {
            if (!seen.containsKey(key)) {
                // movements with no balance row: the projection lost a row
                Replayed truth = state.result();
                mismatches.add(new Mismatch(key.locationId(), key.productId(), null, truth.quantity(), null,
                        truth.averageCost(), truth.balanceAfterErrors(), truth.sequenceErrors()));
            }
        });
        int movements = replayed.values().stream().mapToInt(s -> s.movements).sum();
        return new Report(checked[0], movements, List.copyOf(mismatches), repaired);
    }
}
