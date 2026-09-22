package app.ordaro.inventory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import app.ordaro.finance.Payable;
import app.ordaro.finance.PayableService;
import app.ordaro.inventory.StockDocumentService.DocumentCommand;
import app.ordaro.inventory.StockDocumentService.DocumentWithLines;
import app.ordaro.inventory.StockDocumentService.LineCommand;

/** Stock-in, stock-out, adjustments, transfers and opening stock. Owners and stock managers. */
@RestController
@RequestMapping("/stock-documents")
@PreAuthorize("hasAnyRole('OWNER', 'STOCK_MANAGER')")
class StockDocumentController {

    @Schema(name = "StockDocumentLineRequest")
    record LineRequest(@NotNull UUID productId, @NotNull BigDecimal quantity, BigDecimal unitCost,
            StockMovementReason reason) {
    }

    /**
     * {@code post: true} saves and posts in one call. {@code Boolean}, not {@code boolean}: Jackson 3
     * fails on a record's missing primitive ({@code FAIL_ON_NULL_FOR_PRIMITIVES} is on by default).
     */
    record DocumentRequest(@NotNull StockDocumentType type, @NotNull UUID locationId, UUID counterpartyLocationId,
            UUID supplierId, Instant occurredAt, @Size(max = 500) String note,
            @NotEmpty @Size(max = StockDocumentService.MAX_LINES) List<@Valid LineRequest> lines, Boolean post) {

        boolean postNow() {
            return Boolean.TRUE.equals(post);
        }

        DocumentCommand command() {
            return new DocumentCommand(type, locationId, counterpartyLocationId, supplierId, occurredAt, note,
                    lines.stream().map(l -> new LineCommand(l.productId(), l.quantity(), l.unitCost(), l.reason()))
                            .toList());
        }
    }

    @Schema(name = "StockDocumentLineView")
    record LineView(int position, UUID productId, BigDecimal quantity, BigDecimal unitCost,
            StockMovementReason reason) {
    }

    /** {@code payableId}: the supplier debt a posted STOCK_IN opened, if it cost anything. */
    record DocumentView(UUID id, StockDocumentType type, StockDocumentStatus status, String documentNumber,
            UUID locationId, UUID counterpartyLocationId, UUID supplierId, UUID payableId, Instant occurredAt,
            String note, Instant postedAt, Instant voidedAt, List<LineView> lines) {

        static DocumentView of(DocumentWithLines d, UUID payableId) {
            StockDocument h = d.document();
            return new DocumentView(h.getId(), h.getType(), h.getStatus(), h.getDocumentNumber(), h.getLocationId(),
                    h.getCounterpartyLocationId(), h.getSupplierId(), payableId, h.getOccurredAt(), h.getNote(),
                    h.getPostedAt(), h.getVoidedAt(), d.lines().stream()
                            .map(l -> new LineView(l.getPosition(), l.getProductId(), l.getQuantity(),
                                    l.getUnitCost(), l.getReason()))
                            .toList());
        }
    }

    record DocumentSummary(UUID id, StockDocumentType type, StockDocumentStatus status, String documentNumber,
            UUID locationId, UUID counterpartyLocationId, Instant occurredAt) {
    }

    private final StockDocumentService service;
    private final StockDocumentRepository documents;
    private final PayableService payables;

    StockDocumentController(StockDocumentService service, StockDocumentRepository documents,
            PayableService payables) {
        this.service = service;
        this.documents = documents;
        this.payables = payables;
    }

    @GetMapping
    List<DocumentSummary> recent() {
        return documents.findTop100ByOrderByCreatedAtDesc().stream()
                .map(d -> new DocumentSummary(d.getId(), d.getType(), d.getStatus(), d.getDocumentNumber(),
                        d.getLocationId(), d.getCounterpartyLocationId(), d.getOccurredAt()))
                .toList();
    }

    @GetMapping("/{id}")
    DocumentView get(@PathVariable UUID id) {
        return view(service.find(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    DocumentView create(@Valid @RequestBody DocumentRequest request) {
        return view(request.postNow()
                ? service.createAndPost(request.command())
                : service.createDraft(request.command()));
    }

    /** Replace a draft wholesale (header and lines). */
    @PutMapping("/{id}")
    DocumentView replaceDraft(@PathVariable UUID id, @Valid @RequestBody DocumentRequest request) {
        DocumentWithLines draft = service.replaceDraft(id, request.command());
        return view(request.postNow() ? service.post(id) : draft);
    }

    @PostMapping("/{id}/post")
    DocumentView post(@PathVariable UUID id) {
        return view(service.post(id));
    }

    @PostMapping("/{id}/void")
    DocumentView voidDocument(@PathVariable UUID id) {
        return view(service.voidDocument(id));
    }

    private DocumentView view(DocumentWithLines d) {
        Payable payable = d.document().getType() == StockDocumentType.STOCK_IN
                ? payables.forStockDocument(d.document().getId())
                : null;
        return DocumentView.of(d, payable == null ? null : payable.getId());
    }
}
