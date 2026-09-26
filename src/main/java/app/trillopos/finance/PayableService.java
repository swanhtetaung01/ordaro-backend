package app.trillopos.finance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import app.trillopos.catalog.Supplier;
import app.trillopos.catalog.SupplierRepository;
import app.trillopos.org.Location;
import app.trillopos.org.LocationRepository;
import app.trillopos.org.Organization;
import app.trillopos.org.OrganizationRepository;
import app.trillopos.sales.PaymentMethod;
import app.trillopos.sales.ShiftService;
import app.trillopos.shared.money.Money;
import app.trillopos.shared.tenant.TenantContext;
import app.trillopos.shared.web.ApiException;

/**
 * Payables (spec §7): opened when a STOCK_IN from a supplier is posted, for Σ quantity × unit
 * cost rounded to the currency's minor unit, due {@code paymentTermsDays} after posting; settled
 * by supplier payments; cancelled when an unpaid stock-in is voided.
 */
@Service
public class PayableService {

    public static final EnumSet<PayableStatus> OPEN = EnumSet.of(PayableStatus.OPEN, PayableStatus.PARTIALLY_SETTLED);

    private static final LocalDate ANY_DUE_DATE = LocalDate.of(9999, 12, 31);

    /** One received line: what the goods cost. */
    public record ReceivedLine(BigDecimal quantity, BigDecimal unitCost) {
    }

    public record PayCommand(BigDecimal amount, PaymentMethod method, UUID locationId, UUID cashierShiftId,
            String referenceNo, Instant paidAt, String note, String idempotencyKey) {
    }

    public record ManualCommand(UUID supplierId, UUID locationId, BigDecimal amount, Instant issuedAt,
            LocalDate dueDate, String note) {
    }

    public record PayableDetails(Payable payable, List<PayableSettlement> settlements) {
    }

    /** {@code replayed}: the idempotency key matched an earlier payment, returned unchanged. */
    public record PaymentResult(PayableDetails details, PayableSettlement settlement, boolean replayed) {
    }

    private final PayableRepository payables;
    private final PayableSettlementRepository settlements;
    private final SupplierRepository suppliers;
    private final LocationRepository locations;
    private final OrganizationRepository organizations;
    private final ShiftService shifts;
    private final Clock clock;

    public PayableService(PayableRepository payables, PayableSettlementRepository settlements,
            SupplierRepository suppliers, LocationRepository locations, OrganizationRepository organizations,
            ShiftService shifts, Clock clock) {
        this.payables = payables;
        this.settlements = settlements;
        this.suppliers = suppliers;
        this.locations = locations;
        this.organizations = organizations;
        this.shifts = shifts;
        this.clock = clock;
    }

    // ───────────────────────────────────────────────────────────── stock-in hooks

    /**
     * Called by the stock-in posting, in its transaction. A delivery that cost nothing (free
     * goods) opens no payable.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Payable openForStockIn(UUID documentId, String documentNumber, UUID supplierId, UUID locationId,
            List<ReceivedLine> lines, Instant postedAt) {
        Organization organization = organization();
        BigDecimal cost = BigDecimal.ZERO;
        for (ReceivedLine line : lines) {
            cost = cost.add(line.quantity().multiply(line.unitCost()));
        }
        BigDecimal amount = cost.setScale(Money.minorDigits(organization.getCurrencyCode()), RoundingMode.HALF_UP);
        if (amount.signum() <= 0) {
            return null;
        }
        Supplier supplier = suppliers.findById(supplierId).orElseThrow();
        LocalDate due = postedAt.atZone(ZoneId.of(organization.getTimezone())).toLocalDate()
                .plusDays(supplier.getPaymentTermsDays());
        return payables.save(Payable.forStockIn(supplierId, locationId, documentId, documentNumber, amount, postedAt,
                due));
    }

    /**
     * Called by the stock-in void, in its transaction, before any reversal is written. An unpaid
     * payable is cancelled. One with payments against it refuses the void: the goods would be
     * gone while the payment stood, and v1 has no supplier refund to net it against.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelForStockInVoid(UUID documentId) {
        payables.lockBySource(PayableSourceType.STOCK_DOCUMENT, documentId).ifPresent(payable -> {
            if (payable.getSettledAmount().signum() > 0) {
                throw ApiException.conflict("payable_settled", "the supplier has been paid "
                        + payable.getSettledAmount().stripTrailingZeros().toPlainString()
                        + " for this delivery; a paid stock-in cannot be voided");
            }
            if (payable.getStatus() != PayableStatus.CANCELLED) {
                payable.cancel(clock.instant());
            }
        });
    }

    // ───────────────────────────────────────────────────────────── paying

    /**
     * Records a payment to the supplier. The payable row is locked first, so a retried request
     * with the same key waits for the first and then finds it. Paid from a drawer: the shift must
     * be open at the same location, and its expected cash goes down by the amount.
     */
    @Transactional
    public PaymentResult pay(UUID payableId, PayCommand command) {
        Payable payable = payables.lockById(payableId).orElseThrow(PayableService::notFound);
        String key = command.idempotencyKey();
        if (key != null && (key.isBlank() || key.length() > 100)) {
            throw ApiException.badRequest("invalid_idempotency_key", "the key is 1–100 characters");
        }
        if (key != null) {
            var earlier = settlements.findByPayableIdAndIdempotencyKey(payableId, key);
            if (earlier.isPresent()) {
                return new PaymentResult(details(payable), earlier.get(), true);
            }
        }
        if (!payable.isOpen()) {
            throw ApiException.conflict("payable_closed", "the payable is " + payable.getStatus());
        }
        BigDecimal amount = Money.requirePositive(command.amount(),
                Money.minorDigits(organization().getCurrencyCode()), "amount");
        if (amount.compareTo(payable.getOutstandingAmount()) > 0) {
            throw ApiException.badRequest("amount_exceeds_outstanding", "only "
                    + payable.getOutstandingAmount().stripTrailingZeros().toPlainString() + " is outstanding");
        }
        if (command.method() == null || command.method() == PaymentMethod.CREDIT) {
            throw ApiException.badRequest("invalid_method", "a supplier is paid in cash, a wallet, a transfer or other");
        }
        requireLocation(command.locationId());
        if (command.cashierShiftId() != null) {
            shifts.requireOpenForCash(command.cashierShiftId(), command.locationId());
        }
        payable.settle(amount);
        PayableSettlement settlement = settlements.save(new PayableSettlement(payableId, command.locationId(),
                command.cashierShiftId(), command.method(), amount, command.referenceNo(),
                command.paidAt() != null ? command.paidAt() : clock.instant(), command.note(), key));
        return new PaymentResult(details(payable), settlement, false);
    }

    /** A supplier debt from before the shop used TrilloPOS. */
    @Transactional
    public PayableDetails createManual(ManualCommand command) {
        Organization organization = organization();
        Money.requirePositive(command.amount(), Money.minorDigits(organization.getCurrencyCode()), "amount");
        requireLocation(command.locationId());
        Supplier supplier = command.supplierId() == null ? null : suppliers.findById(command.supplierId()).orElse(null);
        if (supplier == null) {
            throw ApiException.badRequest("supplier_not_found", "no such supplier");
        }
        Instant issuedAt = command.issuedAt() != null ? command.issuedAt() : clock.instant();
        LocalDate due = command.dueDate() != null ? command.dueDate()
                : issuedAt.atZone(ZoneId.of(organization.getTimezone())).toLocalDate()
                        .plusDays(supplier.getPaymentTermsDays());
        return new PayableDetails(payables.save(Payable.manual(supplier.getId(), command.locationId(),
                command.amount(), issuedAt, due, command.note())), List.of());
    }

    // ───────────────────────────────────────────────────────────── reads

    public PayableDetails find(UUID payableId) {
        return details(payables.findById(payableId).orElseThrow(PayableService::notFound));
    }

    public Payable forStockDocument(UUID documentId) {
        return payables.findBySourceTypeAndSourceId(PayableSourceType.STOCK_DOCUMENT, documentId).orElse(null);
    }

    /** {@code overdueOnly}: due before today in the organization's timezone. */
    public List<Payable> search(UUID supplierId, boolean openOnly, boolean overdueOnly, int limit) {
        EnumSet<PayableStatus> statuses = openOnly || overdueOnly ? OPEN : EnumSet.allOf(PayableStatus.class);
        LocalDate dueBefore = overdueOnly
                ? LocalDate.now(clock.withZone(ZoneId.of(organization().getTimezone())))
                : ANY_DUE_DATE;
        PageRequest page = PageRequest.of(0, Math.clamp(limit, 1, 500));
        return supplierId == null
                ? payables.search(statuses, dueBefore, page)
                : payables.searchForSupplier(supplierId, statuses, dueBefore, page);
    }

    PayableDetails details(Payable payable) {
        return new PayableDetails(payable, settlements.findAllByPayableIdOrderByPaidAtAscIdAsc(payable.getId()));
    }

    // ───────────────────────────────────────────────────────────── helpers

    private Location requireLocation(UUID locationId) {
        if (locationId == null) {
            throw ApiException.badRequest("validation_failed", "locationId is required");
        }
        TenantContext.requireLocationInScope(locationId);
        Location location = locations.findById(locationId)
                .orElseThrow(() -> ApiException.badRequest("location_not_found", "no such location"));
        if (!location.isActive()) {
            throw ApiException.badRequest("location_inactive", location.getCode() + " is closed");
        }
        return location;
    }

    private Organization organization() {
        return organizations.findById(TenantContext.requireOrganizationId()).orElseThrow();
    }

    private static ApiException notFound() {
        return ApiException.notFound("payable_not_found", "no such payable");
    }
}
