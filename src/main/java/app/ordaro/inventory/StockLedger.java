package app.ordaro.inventory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import app.ordaro.org.Organization;
import app.ordaro.org.OrganizationRepository;
import app.ordaro.shared.persistence.UuidV7Generator;
import app.ordaro.shared.tenant.TenantContext;
import app.ordaro.shared.web.ApiException;

/**
 * The only code that changes stock. Every posting — stock documents now, sales and returns in
 * later steps — goes through {@link #post}, which in one transaction:
 * <ol>
 * <li>creates any missing balance rows ({@code INSERT … ON CONFLICT DO NOTHING});</li>
 * <li>locks every balance involved, ordered by product then location;</li>
 * <li>applies each entry in order — §9.1 on inflows, the average carried through outflows —
 * refusing an outflow that would go below zero unless the organization allows it;</li>
 * <li>appends one movement per entry with its {@code balanceAfter}.</li>
 * </ol>
 * Callers must not load {@link StockBalance} rows earlier in the same transaction: the lock
 * query would hand back the already-loaded, possibly stale, instance.
 */
@Service
public class StockLedger {

    /** Where an entry's unit cost comes from. */
    public sealed interface Cost {

        /** What was paid (inflows only). */
        record Given(BigDecimal unitCost) implements Cost {
        }

        /** The balance's average at the moment of posting. Always used for outflows. */
        record CurrentAverage() implements Cost {
        }

        /** The cost an earlier entry of this posting consumed — a transfer's IN leg. */
        record SameAsEntry(int index) implements Cost {
        }

        static Cost given(BigDecimal unitCost) {
            return new Given(unitCost);
        }

        static Cost currentAverage() {
            return new CurrentAverage();
        }
    }

    /**
     * @param quantity     signed: positive in, negative out
     * @param enforceStock refuse to take the balance below zero (unless the organization allows
     *                     negative stock); false for a count adjustment, which records what is
     *                     physically there
     */
    public record Entry(UUID locationId, UUID productId, StockMovementType type, BigDecimal quantity, Cost cost,
            StockMovementReason reason, boolean enforceStock, StockReferenceType referenceType, UUID referenceId,
            String referenceNumber, Instant movedAt) {
    }

    private record Key(UUID locationId, UUID productId) {
    }

    private final StockBalanceRepository balances;
    private final StockMovementRepository movements;
    private final OrganizationRepository organizations;
    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;

    public StockLedger(StockBalanceRepository balances, StockMovementRepository movements,
            OrganizationRepository organizations, JdbcTemplate jdbc, EntityManager entityManager) {
        this.balances = balances;
        this.movements = movements;
        this.organizations = organizations;
        this.jdbc = jdbc;
        this.entityManager = entityManager;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<StockMovement> post(List<Entry> entries) {
        if (entries.isEmpty()) {
            return List.of();
        }
        // balance rows are created with plain JDBC, which Hibernate does not flush before: a product
        // or location persisted earlier in this transaction must reach the database first
        entityManager.flush();
        Organization organization = organizations.findById(TenantContext.requireOrganizationId()).orElseThrow();
        Map<Key, StockBalance> locked = ensureAndLock(entries);

        List<StockMovement> posted = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            StockBalance balance = locked.get(new Key(entry.locationId(), entry.productId()));
            BigDecimal quantity = entry.quantity();
            BigDecimal unitCost;
            if (quantity.signum() < 0) {
                BigDecimal after = balance.getQuantity().add(quantity);
                if (entry.enforceStock() && !organization.isAllowNegativeStock() && after.signum() < 0) {
                    throw new ApiException(HttpStatus.CONFLICT, "insufficient_stock",
                            "not enough stock of product " + entry.productId() + " at location "
                                    + entry.locationId() + ": " + balance.getQuantity().stripTrailingZeros()
                                            .toPlainString() + " on hand");
                }
                unitCost = balance.getAverageCost();
                balance.applyOutflow(quantity);
            } else if (quantity.signum() > 0) {
                unitCost = inflowCost(entry.cost(), posted, balance);
                balance.applyInflow(quantity, unitCost);
            } else {
                throw new IllegalArgumentException("a movement has a non-zero quantity");
            }
            balance.touch(entry.movedAt());
            posted.add(movements.save(new StockMovement(entry.locationId(), entry.productId(), entry.type(),
                    quantity, unitCost, balance.getQuantity(), entry.referenceType(), entry.referenceId(),
                    entry.referenceNumber(), entry.reason(), entry.movedAt())));
        }
        return posted;
    }

    private static BigDecimal inflowCost(Cost cost, List<StockMovement> postedSoFar, StockBalance balance) {
        return switch (cost) {
            case Cost.Given given -> {
                if (given.unitCost() == null || given.unitCost().signum() < 0) {
                    throw new IllegalArgumentException("an inflow needs a unit cost of zero or more");
                }
                yield given.unitCost();
            }
            case Cost.SameAsEntry same -> postedSoFar.get(same.index()).getUnitCost();
            // a void's reversal of an outflow comes back at the current average, leaving it unchanged
            case Cost.CurrentAverage current -> balance.getAverageCost();
        };
    }

    private Map<Key, StockBalance> ensureAndLock(List<Entry> entries) {
        Set<Key> keys = new LinkedHashSet<>();
        for (Entry entry : entries) {
            keys.add(new Key(entry.locationId(), entry.productId()));
        }
        UUID organizationId = TenantContext.requireOrganizationId();
        UUID actor = TenantContext.membershipId().orElse(null);
        List<Key> ordered = keys.stream()
                .sorted(Comparator.comparing(Key::productId).thenComparing(Key::locationId))
                .toList();
        for (Key key : ordered) {
            jdbc.update("""
                    insert into stock_balance (id, version, created_at, updated_at, created_by, updated_by,
                                               organization_id, location_id, product_id, quantity, average_cost)
                    values (?, 0, now(), now(), ?, ?, ?, ?, ?, 0, 0)
                    on conflict (location_id, product_id) do nothing
                    """, UuidV7Generator.newId(), actor, actor, organizationId, key.locationId(), key.productId());
        }
        Set<UUID> locations = new LinkedHashSet<>();
        Set<UUID> products = new LinkedHashSet<>();
        ordered.forEach(k -> {
            locations.add(k.locationId());
            products.add(k.productId());
        });
        Map<Key, StockBalance> locked = new HashMap<>();
        for (StockBalance balance : balances.lockAll(locations, products)) {
            locked.put(new Key(balance.getLocationId(), balance.getProductId()), balance);
        }
        if (!locked.keySet().containsAll(keys)) {
            throw new IllegalStateException("a balance row is missing after ensure; wrong tenant?");
        }
        return locked;
    }
}
