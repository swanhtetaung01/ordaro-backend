package app.ordaro.finance;

import java.math.BigDecimal;
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

import app.ordaro.crm.Customer;
import app.ordaro.crm.CustomerRepository;
import app.ordaro.org.Location;
import app.ordaro.org.LocationRepository;
import app.ordaro.org.Organization;
import app.ordaro.org.OrganizationRepository;
import app.ordaro.sales.PaymentMethod;
import app.ordaro.sales.ShiftService;
import app.ordaro.shared.money.Money;
import app.ordaro.shared.tenant.TenantContext;
import app.ordaro.shared.web.ApiException;

/**
 * Receivables (spec §7): opened by a CREDIT payment when a sale completes, reduced by
 * settlements, closed by settling or writing off. The credit check runs with the customer row
 * locked, so two registers cannot both pass it.
 */
@Service
public class ReceivableService {

    public static final EnumSet<ReceivableStatus> OPEN = EnumSet.of(ReceivableStatus.OPEN,
            ReceivableStatus.PARTIALLY_SETTLED);

    /** Far enough out to mean "any due date" without a nullable query parameter. */
    private static final LocalDate ANY_DUE_DATE = LocalDate.of(9999, 12, 31);

    public record SettleCommand(BigDecimal amount, PaymentMethod method, UUID locationId, UUID cashierShiftId,
            String referenceNo, Instant paidAt, String note, String idempotencyKey) {
    }

    public record ManualCommand(UUID customerId, UUID locationId, BigDecimal amount, Instant issuedAt,
            LocalDate dueDate, String note) {
    }

    public record ReceivableDetails(Receivable receivable, List<ReceivableSettlement> settlements) {
    }

    /** {@code replayed}: the idempotency key matched an earlier settlement, returned unchanged. */
    public record SettlementResult(ReceivableDetails details, ReceivableSettlement settlement, boolean replayed) {
    }

    private final ReceivableRepository receivables;
    private final ReceivableSettlementRepository settlements;
    private final CustomerRepository customers;
    private final LocationRepository locations;
    private final OrganizationRepository organizations;
    private final ShiftService shifts;
    private final Clock clock;

    public ReceivableService(ReceivableRepository receivables, ReceivableSettlementRepository settlements,
            CustomerRepository customers, LocationRepository locations, OrganizationRepository organizations,
            ShiftService shifts, Clock clock) {
        this.receivables = receivables;
        this.settlements = settlements;
        this.customers = customers;
        this.locations = locations;
        this.organizations = organizations;
        this.shifts = shifts;
        this.clock = clock;
    }

    // ───────────────────────────────────────────────────────────── opening

    /**
     * Spec §9.2 step 7, the CREDIT half: lock the customer, check the limit against Σ outstanding,
     * open a receivable due {@code creditTermDays} after the sale's business date. Runs inside the
     * completion transaction, after the stock locks (a fixed order: balances, then customer).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Receivable openForSale(UUID customerId, UUID locationId, UUID saleId, String receiptNumber,
            BigDecimal amount, Instant soldAt, ZoneId zone) {
        Customer customer = lockCreditCustomer(customerId);
        requireWithinLimit(customer, amount);
        LocalDate due = soldAt.atZone(zone).toLocalDate().plusDays(customer.getCreditTermDays());
        return receivables.save(Receivable.forSale(customer.getId(), locationId, saleId, receiptNumber, amount,
                soldAt, due));
    }

    /** An owner records a debt from before Ordaro. Counts against the customer's limit like any other. */
    @Transactional
    public ReceivableDetails createManual(ManualCommand command) {
        Organization organization = organization();
        Money.requirePositive(command.amount(), Money.minorDigits(organization.getCurrencyCode()), "amount");
        requireLocation(command.locationId());
        Customer customer = lockCreditCustomer(command.customerId());
        Instant issuedAt = command.issuedAt() != null ? command.issuedAt() : clock.instant();
        LocalDate due = command.dueDate() != null ? command.dueDate()
                : issuedAt.atZone(ZoneId.of(organization.getTimezone())).toLocalDate()
                        .plusDays(customer.getCreditTermDays());
        Receivable receivable = receivables.save(Receivable.manual(customer.getId(), command.locationId(),
                command.amount(), issuedAt, due, command.note()));
        return new ReceivableDetails(receivable, List.of());
    }

    // ───────────────────────────────────────────────────────────── settling

    /**
     * Records a repayment. The receivable row is locked first, so a retried request with the same
     * key waits for the first and then finds it. A cash repayment at a register names its shift:
     * the shift must be open at the same location, and the drawer then expects that cash.
     */
    @Transactional
    public SettlementResult settle(UUID receivableId, SettleCommand command) {
        Receivable receivable = receivables.lockById(receivableId).orElseThrow(ReceivableService::notFound);
        String key = command.idempotencyKey();
        if (key != null && (key.isBlank() || key.length() > 100)) {
            throw ApiException.badRequest("invalid_idempotency_key", "the key is 1–100 characters");
        }
        if (key != null) {
            var earlier = settlements.findByReceivableIdAndIdempotencyKey(receivableId, key);
            if (earlier.isPresent()) {
                return new SettlementResult(details(receivable), earlier.get(), true);
            }
        }
        if (!receivable.isOpen()) {
            throw ApiException.conflict("receivable_closed", "the receivable is " + receivable.getStatus());
        }
        Organization organization = organization();
        BigDecimal amount = Money.requirePositive(command.amount(), Money.minorDigits(organization.getCurrencyCode()),
                "amount");
        if (amount.compareTo(receivable.getOutstandingAmount()) > 0) {
            throw ApiException.badRequest("amount_exceeds_outstanding", "only "
                    + receivable.getOutstandingAmount().stripTrailingZeros().toPlainString() + " is outstanding");
        }
        if (command.method() == null || command.method() == PaymentMethod.CREDIT) {
            throw ApiException.badRequest("invalid_method", "a repayment is cash, a wallet, a transfer or other");
        }
        requireLocation(command.locationId());
        if (command.cashierShiftId() != null) {
            shifts.requireOpenForCash(command.cashierShiftId(), command.locationId());
        }
        receivable.settle(amount);
        ReceivableSettlement settlement = settlements.save(new ReceivableSettlement(receivableId,
                command.locationId(), command.cashierShiftId(), command.method(), amount, command.referenceNo(),
                command.paidAt() != null ? command.paidAt() : clock.instant(), command.note(), key));
        return new SettlementResult(details(receivable), settlement, false);
    }

    /**
     * A return on a credit sale never pays out cash: the refund lands here as a CREDIT settlement
     * (spec §7). The caller has checked it fits what is outstanding. In the return's transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ReceivableSettlement settleByReturn(UUID receivableId, BigDecimal amount, UUID locationId,
            String returnNumber, Instant at) {
        Receivable receivable = receivables.lockById(receivableId).orElseThrow(ReceivableService::notFound);
        receivable.settle(amount);
        return settlements.save(new ReceivableSettlement(receivableId, locationId, null, PaymentMethod.CREDIT,
                amount, returnNumber, at, "return " + returnNumber, null));
    }

    /** Gives up what is still owed. Owners only (checked by the controller). */
    @Transactional
    public ReceivableDetails writeOff(UUID receivableId, String reason) {
        Receivable receivable = receivables.lockById(receivableId).orElseThrow(ReceivableService::notFound);
        if (!receivable.isOpen()) {
            throw ApiException.conflict("receivable_closed", "the receivable is " + receivable.getStatus());
        }
        receivable.writeOff(clock.instant(), reason);
        return details(receivable);
    }

    // ───────────────────────────────────────────────────────────── reads

    public ReceivableDetails find(UUID receivableId) {
        return details(receivables.findById(receivableId).orElseThrow(ReceivableService::notFound));
    }

    /** {@code overdueOnly}: due before today in the organization's timezone. */
    public List<Receivable> search(UUID customerId, boolean openOnly, boolean overdueOnly, int limit) {
        EnumSet<ReceivableStatus> statuses = openOnly || overdueOnly ? OPEN : EnumSet.allOf(ReceivableStatus.class);
        LocalDate dueBefore = overdueOnly
                ? LocalDate.now(clock.withZone(ZoneId.of(organization().getTimezone())))
                : ANY_DUE_DATE;
        PageRequest page = PageRequest.of(0, Math.clamp(limit, 1, 500));
        return customerId == null
                ? receivables.search(statuses, dueBefore, page)
                : receivables.searchForCustomer(customerId, statuses, dueBefore, page);
    }

    /** The receivable a credit sale opened, or null when the sale was not taken on credit. */
    public Receivable forSale(UUID saleId) {
        return receivables.findBySourceTypeAndSourceId(ReceivableSourceType.SALE, saleId).orElse(null);
    }

    public BigDecimal outstandingFor(UUID customerId) {
        return receivables.outstandingFor(customerId);
    }

    ReceivableDetails details(Receivable receivable) {
        return new ReceivableDetails(receivable,
                settlements.findAllByReceivableIdOrderByPaidAtAscIdAsc(receivable.getId()));
    }

    // ───────────────────────────────────────────────────────────── helpers

    private Customer lockCreditCustomer(UUID customerId) {
        if (customerId == null) {
            throw ApiException.badRequest("customer_required", "credit is given to a named customer, never a walk-in");
        }
        Customer customer = customers.lockById(customerId)
                .orElseThrow(() -> ApiException.badRequest("customer_not_found", "no such customer"));
        if (customer.isArchived()) {
            throw ApiException.badRequest("customer_archived", customer.getName() + " is archived");
        }
        return customer;
    }

    private void requireWithinLimit(Customer customer, BigDecimal amount) {
        BigDecimal owed = receivables.outstandingFor(customer.getId());
        BigDecimal available = customer.getCreditLimit().subtract(owed);
        if (amount.compareTo(available) > 0) {
            throw ApiException.conflict("credit_limit_exceeded", customer.getName() + " can take "
                    + available.max(BigDecimal.ZERO).stripTrailingZeros().toPlainString() + " more on credit (limit "
                    + customer.getCreditLimit().stripTrailingZeros().toPlainString() + ", owed "
                    + owed.stripTrailingZeros().toPlainString() + ")");
        }
    }

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
        return ApiException.notFound("receivable_not_found", "no such receivable");
    }
}
