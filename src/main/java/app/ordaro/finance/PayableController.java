package app.ordaro.finance;

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

import app.ordaro.catalog.Supplier;
import app.ordaro.catalog.SupplierRepository;
import app.ordaro.finance.PayableService.ManualCommand;
import app.ordaro.finance.PayableService.PayCommand;
import app.ordaro.finance.PayableService.PayableDetails;
import app.ordaro.finance.PayableService.PaymentResult;
import app.ordaro.org.OrganizationRepository;
import app.ordaro.sales.PaymentMethod;
import app.ordaro.shared.tenant.TenantContext;

/** "Who I owe" (spec §7). Suppliers are the owner's and the stock manager's business, not the till's. */
@RestController
@RequestMapping("/payables")
@PreAuthorize("hasAnyRole('OWNER', 'STOCK_MANAGER')")
class PayableController {

    @Schema(name = "PayableSettlementView")
    record SettlementView(UUID id, BigDecimal amount, PaymentMethod method, UUID locationId, UUID cashierShiftId,
            String referenceNo, Instant paidAt, String note) {

        static SettlementView of(PayableSettlement s) {
            return new SettlementView(s.getId(), s.getAmount(), s.getMethod(), s.getLocationId(),
                    s.getCashierShiftId(), s.getReferenceNo(), s.getPaidAt(), s.getNote());
        }
    }

    record PayableView(UUID id, UUID supplierId, String supplierName, UUID locationId, PayableSourceType sourceType,
            UUID sourceId, String referenceNumber, BigDecimal originalAmount, BigDecimal settledAmount,
            BigDecimal outstandingAmount, Instant issuedAt, LocalDate dueDate, boolean overdue, PayableStatus status,
            String note, List<SettlementView> settlements) {

        static PayableView of(Payable p, String supplierName, LocalDate today, List<PayableSettlement> settlements) {
            return new PayableView(p.getId(), p.getSupplierId(), supplierName, p.getLocationId(), p.getSourceType(),
                    p.getSourceId(), p.getReferenceNumber(), p.getOriginalAmount(), p.getSettledAmount(),
                    p.getOutstandingAmount(), p.getIssuedAt(), p.getDueDate(),
                    p.isOpen() && p.getDueDate().isBefore(today), p.getStatus(), p.getNote(),
                    settlements == null ? null : settlements.stream().map(SettlementView::of).toList());
        }
    }

    record PayRequest(@NotNull BigDecimal amount, @NotNull PaymentMethod method, @NotNull UUID locationId,
            UUID cashierShiftId, @Size(max = 100) String referenceNo, Instant paidAt, @Size(max = 500) String note,
            @Size(max = 100) String idempotencyKey) {
    }

    @Schema(name = "ManualPayableRequest")
    record ManualRequest(@NotNull UUID supplierId, @NotNull UUID locationId, @NotNull BigDecimal amount,
            Instant issuedAt, LocalDate dueDate, @Size(max = 500) String note) {
    }

    private final PayableService service;
    private final SupplierRepository suppliers;
    private final OrganizationRepository organizations;
    private final Clock clock;

    PayableController(PayableService service, SupplierRepository suppliers, OrganizationRepository organizations,
            Clock clock) {
        this.service = service;
        this.suppliers = suppliers;
        this.organizations = organizations;
        this.clock = clock;
    }

    /** {@code status}: {@code open} (default) or {@code all}; {@code overdue=true} narrows to overdue. */
    @GetMapping
    List<PayableView> list(@RequestParam(required = false) UUID supplierId,
            @RequestParam(defaultValue = "open") String status, @RequestParam(defaultValue = "false") boolean overdue,
            @RequestParam(defaultValue = "100") int limit) {
        List<Payable> found = service.search(supplierId, !"all".equalsIgnoreCase(status), overdue, limit);
        Map<UUID, String> names = suppliers.findAllById(found.stream().map(Payable::getSupplierId).distinct()
                .toList()).stream().collect(Collectors.toMap(Supplier::getId, Supplier::getName));
        LocalDate today = today();
        return found.stream().map(p -> PayableView.of(p, names.get(p.getSupplierId()), today, null)).toList();
    }

    @GetMapping("/{id}")
    PayableView get(@PathVariable UUID id) {
        return view(service.find(id));
    }

    /** A payment to the supplier. The same {@code idempotencyKey} again returns the first with 200. */
    @PostMapping("/{id}/settlements")
    ResponseEntity<PayableView> pay(@PathVariable UUID id, @Valid @RequestBody PayRequest request) {
        PaymentResult result = service.pay(id, new PayCommand(request.amount(), request.method(),
                request.locationId(), request.cashierShiftId(), request.referenceNo(), request.paidAt(),
                request.note(), request.idempotencyKey()));
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .header("Idempotent-Replay", String.valueOf(result.replayed()))
                .body(view(result.details()));
    }

    /** A supplier debt from before the shop used Ordaro. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('OWNER')")
    PayableView createManual(@Valid @RequestBody ManualRequest request) {
        return view(service.createManual(new ManualCommand(request.supplierId(), request.locationId(),
                request.amount(), request.issuedAt(), request.dueDate(), request.note())));
    }

    private PayableView view(PayableDetails details) {
        Payable p = details.payable();
        String name = suppliers.findById(p.getSupplierId()).map(Supplier::getName).orElse(null);
        return PayableView.of(p, name, today(), details.settlements());
    }

    private LocalDate today() {
        ZoneId zone = ZoneId.of(organizations.findById(TenantContext.requireOrganizationId()).orElseThrow()
                .getTimezone());
        return LocalDate.now(clock.withZone(zone));
    }
}
