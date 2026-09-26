package app.trillopos.finance;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import app.trillopos.crm.Customer;
import app.trillopos.crm.CustomerRepository;
import app.trillopos.finance.ReceivableService.ManualCommand;
import app.trillopos.finance.ReceivableService.ReceivableDetails;
import app.trillopos.finance.ReceivableService.SettleCommand;
import app.trillopos.finance.ReceivableService.SettlementResult;
import app.trillopos.org.OrganizationRepository;
import app.trillopos.sales.PaymentMethod;
import app.trillopos.shared.tenant.TenantContext;

/** "Who owes me money" (spec §7). Anyone who sells may take a repayment; only an owner writes off. */
@RestController
@RequestMapping("/receivables")
@PreAuthorize("hasAnyRole('OWNER', 'STOCK_MANAGER', 'CASHIER')")
class ReceivableController {

    @Schema(name = "ReceivableSettlementView")
    record SettlementView(UUID id, BigDecimal amount, PaymentMethod method, UUID locationId, UUID cashierShiftId,
            String referenceNo, Instant paidAt, String note) {

        static SettlementView of(ReceivableSettlement s) {
            return new SettlementView(s.getId(), s.getAmount(), s.getMethod(), s.getLocationId(),
                    s.getCashierShiftId(), s.getReferenceNo(), s.getPaidAt(), s.getNote());
        }
    }

    /** {@code overdue}: still open and due before today. {@code settlements} only on a single read. */
    record ReceivableView(UUID id, UUID customerId, String customerName, UUID locationId,
            ReceivableSourceType sourceType, UUID sourceId, String referenceNumber, BigDecimal originalAmount,
            BigDecimal settledAmount, BigDecimal writtenOffAmount, BigDecimal outstandingAmount, Instant issuedAt,
            LocalDate dueDate, boolean overdue, ReceivableStatus status, String note,
            List<SettlementView> settlements) {

        static ReceivableView of(Receivable r, String customerName, LocalDate today,
                List<ReceivableSettlement> settlements) {
            return new ReceivableView(r.getId(), r.getCustomerId(), customerName, r.getLocationId(),
                    r.getSourceType(), r.getSourceId(), r.getReferenceNumber(), r.getOriginalAmount(),
                    r.getSettledAmount(), r.getWrittenOffAmount(), r.getOutstandingAmount(), r.getIssuedAt(),
                    r.getDueDate(), r.isOpen() && r.getDueDate().isBefore(today), r.getStatus(), r.getNote(),
                    settlements == null ? null : settlements.stream().map(SettlementView::of).toList());
        }
    }

    record SettleRequest(@NotNull BigDecimal amount, @NotNull PaymentMethod method, @NotNull UUID locationId,
            UUID cashierShiftId, @Size(max = 100) String referenceNo, Instant paidAt, @Size(max = 500) String note,
            @Size(max = 100) String idempotencyKey) {
    }

    @Schema(name = "ManualReceivableRequest")
    record ManualRequest(@NotNull UUID customerId, @NotNull UUID locationId, @NotNull BigDecimal amount,
            Instant issuedAt, LocalDate dueDate, @Size(max = 500) String note) {
    }

    record WriteOffRequest(@Size(max = 500) String reason) {
    }

    private final ReceivableService service;
    private final CustomerRepository customers;
    private final OrganizationRepository organizations;
    private final Clock clock;

    ReceivableController(ReceivableService service, CustomerRepository customers,
            OrganizationRepository organizations, Clock clock) {
        this.service = service;
        this.customers = customers;
        this.organizations = organizations;
        this.clock = clock;
    }

    /** {@code status}: {@code open} (default) or {@code all}; {@code overdue=true} narrows to overdue. */
    @GetMapping
    List<ReceivableView> list(@RequestParam(required = false) UUID customerId,
            @RequestParam(defaultValue = "open") String status, @RequestParam(defaultValue = "false") boolean overdue,
            @RequestParam(defaultValue = "100") int limit) {
        List<Receivable> found = service.search(customerId, !"all".equalsIgnoreCase(status), overdue, limit);
        Map<UUID, String> names = customers.findAllById(found.stream().map(Receivable::getCustomerId).distinct()
                .toList()).stream().collect(Collectors.toMap(Customer::getId, Customer::getName));
        LocalDate today = today();
        return found.stream().map(r -> ReceivableView.of(r, names.get(r.getCustomerId()), today, null)).toList();
    }

    @GetMapping("/{id}")
    ReceivableView get(@PathVariable UUID id) {
        return view(service.find(id));
    }

    /** A repayment. The same {@code idempotencyKey} again returns the first with 200. */
    @PostMapping("/{id}/settlements")
    ResponseEntity<ReceivableView> settle(@PathVariable UUID id, @Valid @RequestBody SettleRequest request) {
        SettlementResult result = service.settle(id, new SettleCommand(request.amount(), request.method(),
                request.locationId(), request.cashierShiftId(), request.referenceNo(), request.paidAt(),
                request.note(), request.idempotencyKey()));
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .header("Idempotent-Replay", String.valueOf(result.replayed()))
                .body(view(result.details()));
    }

    @PostMapping("/{id}/write-off")
    @PreAuthorize("hasRole('OWNER')")
    ReceivableView writeOff(@PathVariable UUID id, @Valid @RequestBody(required = false) WriteOffRequest request) {
        return view(service.writeOff(id, request == null ? null : request.reason()));
    }

    /** A debt from before the shop used TrilloPOS. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('OWNER')")
    ReceivableView createManual(@Valid @RequestBody ManualRequest request) {
        return view(service.createManual(new ManualCommand(request.customerId(), request.locationId(),
                request.amount(), request.issuedAt(), request.dueDate(), request.note())));
    }

    private ReceivableView view(ReceivableDetails details) {
        Receivable r = details.receivable();
        String name = r.getCustomerId() == null ? null
                : customers.findById(r.getCustomerId()).map(Customer::getName).orElse(null);
        return ReceivableView.of(r, name, today(), details.settlements());
    }

    private LocalDate today() {
        ZoneId zone = ZoneId.of(organizations.findById(TenantContext.requireOrganizationId()).orElseThrow()
                .getTimezone());
        return LocalDate.now(clock.withZone(zone));
    }
}
