package app.ordaro.inventory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import app.ordaro.inventory.StockBalanceRebuilder.Report;

@RestController
class StockController {

    /** {@code averageCost} is null for roles that do not see costs (cashier, packer). */
    record BalanceView(UUID locationId, UUID productId, BigDecimal quantity, BigDecimal averageCost,
            Instant lastMovementAt) {
    }

    record MovementView(UUID id, long seq, Instant createdAt, Instant movedAt, StockMovementType type,
            BigDecimal quantity, BigDecimal unitCost, BigDecimal balanceAfter, StockReferenceType referenceType, UUID referenceId,
            String referenceNumber, StockMovementReason reason, UUID createdBy) {

        static MovementView of(StockMovement m) {
            return new MovementView(m.getId(), m.getSeq(), m.getCreatedAt(), m.getMovedAt(), m.getType(),
                    m.getQuantity(), m.getUnitCost(), m.getBalanceAfter(), m.getReferenceType(), m.getReferenceId(),
                    m.getReferenceNumber(), m.getReason(), m.getCreatedBy());
        }
    }

    private final StockBalanceRepository balances;
    private final StockMovementRepository movements;
    private final StockBalanceRebuilder rebuilder;

    StockController(StockBalanceRepository balances, StockMovementRepository movements,
            StockBalanceRebuilder rebuilder) {
        this.balances = balances;
        this.movements = movements;
        this.rebuilder = rebuilder;
    }

    /** On-hand stock. Filter by location, by product, or neither. */
    @GetMapping("/stock-balances")
    @Transactional(readOnly = true)
    List<BalanceView> balances(@RequestParam(required = false) UUID locationId,
            @RequestParam(required = false) UUID productId, Authentication authentication) {
        List<StockBalance> rows;
        if (locationId != null && productId != null) {
            rows = balances.findByLocationIdAndProductId(locationId, productId).stream().toList();
        } else if (locationId != null) {
            rows = balances.findAllByLocationIdOrderByProductId(locationId);
        } else if (productId != null) {
            rows = balances.findAllByProductIdOrderByLocationId(productId);
        } else {
            rows = balances.findAllByOrderByLocationIdAscProductIdAsc();
        }
        boolean seesCost = seesCost(authentication);
        return rows.stream()
                .map(b -> new BalanceView(b.getLocationId(), b.getProductId(), b.getQuantity(),
                        seesCost ? b.getAverageCost() : null, b.getLastMovementAt()))
                .toList();
    }

    /** The ledger for one (location, product), newest first. */
    @GetMapping("/stock-movements")
    @PreAuthorize("hasAnyRole('OWNER', 'STOCK_MANAGER')")
    List<MovementView> ledger(@RequestParam UUID locationId, @RequestParam UUID productId,
            @RequestParam(defaultValue = "200") int limit) {
        return movements.ledger(locationId, productId, PageRequest.of(0, Math.clamp(limit, 1, 1000))).stream()
                .map(MovementView::of)
                .toList();
    }

    /** Replays the ledger and reports any balance that does not match it. Changes nothing. */
    @GetMapping("/inventory/verification")
    @PreAuthorize("hasRole('OWNER')")
    Report verify() {
        return rebuilder.verify();
    }

    /** Rewrites drifted balances from the ledger. */
    @PostMapping("/inventory/rebuild")
    @PreAuthorize("hasRole('OWNER')")
    Report rebuild() {
        return rebuilder.repair();
    }

    private static boolean seesCost(Authentication authentication) {
        return authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals("ROLE_OWNER") || a.equals("ROLE_STOCK_MANAGER"));
    }
}
