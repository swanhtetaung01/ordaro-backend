package app.ordaro.inventory;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.ordaro.catalog.Product;
import app.ordaro.catalog.ProductRepository;
import app.ordaro.catalog.SupplierRepository;
import app.ordaro.inventory.StockLedger.Cost;
import app.ordaro.inventory.StockLedger.Entry;
import app.ordaro.org.Location;
import app.ordaro.org.LocationRepository;
import app.ordaro.org.OrganizationRepository;
import app.ordaro.shared.numbering.DocumentNumbers;
import app.ordaro.shared.tenant.TenantContext;
import app.ordaro.shared.web.ApiException;

/**
 * Stock documents (spec §5): save a draft, post it, void it. Posting and voiding lock the header
 * first and hand every movement to {@link StockLedger} in the same transaction.
 */
@Service
public class StockDocumentService {

    static final int MAX_LINES = 500;

    public record LineCommand(UUID productId, BigDecimal quantity, BigDecimal unitCost, StockMovementReason reason) {
    }

    public record DocumentCommand(StockDocumentType type, UUID locationId, UUID counterpartyLocationId,
            UUID supplierId, Instant occurredAt, String note, List<LineCommand> lines) {
    }

    /** One location's opening stock for a new product (the Add Product form). */
    public record OpeningStock(UUID locationId, BigDecimal quantity, BigDecimal unitCost) {
    }

    public record DocumentWithLines(StockDocument document, List<StockDocumentLine> lines) {
    }

    private final StockDocumentRepository documents;
    private final StockDocumentLineRepository lines;
    private final StockMovementRepository movements;
    private final StockLedger ledger;
    private final LocationRepository locations;
    private final ProductRepository products;
    private final SupplierRepository suppliers;
    private final OrganizationRepository organizations;
    private final DocumentNumbers numbers;
    private final Clock clock;

    public StockDocumentService(StockDocumentRepository documents, StockDocumentLineRepository lines,
            StockMovementRepository movements, StockLedger ledger, LocationRepository locations,
            ProductRepository products, SupplierRepository suppliers, OrganizationRepository organizations,
            DocumentNumbers numbers, Clock clock) {
        this.documents = documents;
        this.lines = lines;
        this.movements = movements;
        this.ledger = ledger;
        this.locations = locations;
        this.products = products;
        this.suppliers = suppliers;
        this.organizations = organizations;
        this.numbers = numbers;
        this.clock = clock;
    }

    @Transactional
    public DocumentWithLines createDraft(DocumentCommand command) {
        List<LineCommand> normalized = validate(command);
        StockDocument document = documents.save(new StockDocument(command.type(), command.locationId(),
                command.counterpartyLocationId(), command.supplierId(), occurredAt(command), command.note()));
        return new DocumentWithLines(document, saveLines(document.getId(), normalized));
    }

    @Transactional
    public DocumentWithLines replaceDraft(UUID documentId, DocumentCommand command) {
        StockDocument document = lockDraft(documentId);
        List<LineCommand> normalized = validate(command);
        document.revise(command.type(), command.locationId(), command.counterpartyLocationId(),
                command.supplierId(), occurredAt(command), command.note());
        lines.deleteAllOfDocument(documentId);
        lines.flush();
        return new DocumentWithLines(document, saveLines(documentId, normalized));
    }

    @Transactional
    public DocumentWithLines createAndPost(DocumentCommand command) {
        return post(createDraft(command).document().getId());
    }

    /** DRAFT → POSTED: number the document and write its movements, all or nothing. */
    @Transactional
    public DocumentWithLines post(UUID documentId) {
        StockDocument document = lockDraft(documentId);
        List<StockDocumentLine> documentLines = lines.findAllByDocumentIdOrderByPosition(documentId);
        validate(new DocumentCommand(document.getType(), document.getLocationId(),
                document.getCounterpartyLocationId(), document.getSupplierId(), document.getOccurredAt(),
                document.getNote(), documentLines.stream()
                        .map(l -> new LineCommand(l.getProductId(), l.getQuantity(), l.getUnitCost(), l.getReason()))
                        .toList()));

        Location location = locations.findById(document.getLocationId()).orElseThrow();
        ZoneId zone = ZoneId.of(organizations.findById(TenantContext.requireOrganizationId()).orElseThrow()
                .getTimezone());
        String number = numbers.next(location.getId(), location.getCode(), document.getType().sequence(),
                document.getOccurredAt(), zone);

        ledger.post(entries(document, number, documentLines));
        document.markPosted(number, clock.instant());
        return new DocumentWithLines(document, documentLines);
    }

    /**
     * POSTED → VOID writes, for each original movement, one of the opposite sign at the balance's
     * <em>current</em> average, reason VOID_REVERSAL (spec §5). Deliberately not an exact undo. A
     * reversal that would take stock below zero is refused like any other outflow. DRAFT → VOID
     * writes nothing. The Payable rule for a voided STOCK_IN arrives with finance (step 5).
     */
    @Transactional
    public DocumentWithLines voidDocument(UUID documentId) {
        StockDocument document = documents.lockById(documentId).orElseThrow(StockDocumentService::notFound);
        if (document.getStatus() == StockDocumentStatus.VOID) {
            throw ApiException.conflict("document_void", "the document is already void");
        }
        if (document.getStatus() == StockDocumentStatus.POSTED) {
            List<StockMovement> original = movements.findByReference(StockReferenceType.STOCK_DOCUMENT, documentId);
            List<Entry> reversals = new ArrayList<>(original.size());
            Instant now = clock.instant();
            for (int i = original.size() - 1; i >= 0; i--) {
                StockMovement m = original.get(i);
                BigDecimal quantity = m.getQuantity().negate();
                reversals.add(new Entry(m.getLocationId(), m.getProductId(), reversalOf(m.getType()), quantity,
                        Cost.currentAverage(), StockMovementReason.VOID_REVERSAL, quantity.signum() < 0,
                        StockReferenceType.STOCK_DOCUMENT, documentId, document.getDocumentNumber(), now));
            }
            ledger.post(reversals);
        }
        document.markVoided(clock.instant());
        return new DocumentWithLines(document, lines.findAllByDocumentIdOrderByPosition(documentId));
    }

    /** One auto-posted OPENING document per location (spec §5). */
    @Transactional
    public List<DocumentWithLines> postOpeningStock(UUID productId, List<OpeningStock> opening) {
        List<DocumentWithLines> posted = new ArrayList<>();
        for (OpeningStock stock : opening) {
            posted.add(createAndPost(new DocumentCommand(StockDocumentType.OPENING, stock.locationId(), null, null,
                    null, "Opening stock", List.of(new LineCommand(productId, stock.quantity(), stock.unitCost(),
                            null)))));
        }
        return posted;
    }

    public DocumentWithLines find(UUID documentId) {
        StockDocument document = documents.findById(documentId).orElseThrow(StockDocumentService::notFound);
        return new DocumentWithLines(document, lines.findAllByDocumentIdOrderByPosition(documentId));
    }

    private List<Entry> entries(StockDocument document, String number, List<StockDocumentLine> documentLines) {
        List<Entry> entries = new ArrayList<>();
        Instant movedAt = document.getOccurredAt();
        UUID id = document.getId();
        UUID source = document.getLocationId();
        for (StockDocumentLine line : documentLines) {
            BigDecimal q = line.getQuantity();
            switch (document.getType()) {
                case OPENING -> entries.add(entry(source, line, StockMovementType.OPENING, q,
                        Cost.given(line.getUnitCost()), null, false, id, number, movedAt));
                case STOCK_IN -> entries.add(entry(source, line, StockMovementType.STOCK_IN, q,
                        Cost.given(line.getUnitCost()), null, false, id, number, movedAt));
                case STOCK_OUT -> entries.add(entry(source, line, StockMovementType.STOCK_OUT, q.negate(),
                        Cost.currentAverage(), line.getReason(), true, id, number, movedAt));
                case ADJUSTMENT -> entries.add(entry(source, line, StockMovementType.ADJUSTMENT, q,
                        q.signum() > 0 ? Cost.given(line.getUnitCost()) : Cost.currentAverage(), line.getReason(),
                        false, id, number, movedAt));
                case TRANSFER -> {
                    int outIndex = entries.size();
                    entries.add(entry(source, line, StockMovementType.TRANSFER_OUT, q.negate(),
                            Cost.currentAverage(), null, true, id, number, movedAt));
                    // TRANSFER_IN at the source's cost (spec §5): the destination blends it in
                    entries.add(entry(document.getCounterpartyLocationId(), line, StockMovementType.TRANSFER_IN, q,
                            new Cost.SameAsEntry(outIndex), null, false, id, number, movedAt));
                }
            }
        }
        return entries;
    }

    private static Entry entry(UUID locationId, StockDocumentLine line, StockMovementType type, BigDecimal quantity,
            Cost cost, StockMovementReason reason, boolean enforceStock, UUID documentId, String number,
            Instant movedAt) {
        return new Entry(locationId, line.getProductId(), type, quantity, cost, reason, enforceStock,
                StockReferenceType.STOCK_DOCUMENT, documentId, number, movedAt);
    }

    /** STOCK_IN ↔ STOCK_OUT, TRANSFER_IN ↔ TRANSFER_OUT; OPENING and ADJUSTMENT reverse as ADJUSTMENT. */
    static StockMovementType reversalOf(StockMovementType type) {
        return switch (type) {
            case STOCK_IN -> StockMovementType.STOCK_OUT;
            case STOCK_OUT -> StockMovementType.STOCK_IN;
            case TRANSFER_IN -> StockMovementType.TRANSFER_OUT;
            case TRANSFER_OUT -> StockMovementType.TRANSFER_IN;
            case OPENING, ADJUSTMENT -> StockMovementType.ADJUSTMENT;
            case SALE, SALE_RETURN -> throw new IllegalArgumentException(type + " is not voided through a document");
        };
    }

    private StockDocument lockDraft(UUID documentId) {
        StockDocument document = documents.lockById(documentId).orElseThrow(StockDocumentService::notFound);
        if (document.getStatus() != StockDocumentStatus.DRAFT) {
            throw ApiException.conflict("document_not_draft", "the document is " + document.getStatus());
        }
        return document;
    }

    private List<StockDocumentLine> saveLines(UUID documentId, List<LineCommand> commands) {
        List<StockDocumentLine> saved = new ArrayList<>(commands.size());
        for (int i = 0; i < commands.size(); i++) {
            LineCommand c = commands.get(i);
            saved.add(lines.save(new StockDocumentLine(documentId, i + 1, c.productId(), c.quantity(), c.unitCost(),
                    c.reason())));
        }
        return saved;
    }

    private Instant occurredAt(DocumentCommand command) {
        return command.occurredAt() != null ? command.occurredAt() : clock.instant();
    }

    /** Checks a document against its type and returns lines with ignored fields cleared. */
    private List<LineCommand> validate(DocumentCommand command) {
        StockDocumentType type = command.type();
        if (type == null) {
            throw ApiException.badRequest("validation_failed", "type is required");
        }
        requireActiveLocation(command.locationId());
        if (type == StockDocumentType.TRANSFER) {
            if (command.counterpartyLocationId() == null) {
                throw ApiException.badRequest("destination_required", "a transfer names its destination location");
            }
            if (command.counterpartyLocationId().equals(command.locationId())) {
                throw ApiException.badRequest("same_location", "a transfer moves stock between two locations");
            }
            requireActiveLocation(command.counterpartyLocationId());
        } else if (command.counterpartyLocationId() != null) {
            throw ApiException.badRequest("unexpected_destination", "only a transfer has a destination location");
        }
        if (command.supplierId() != null) {
            if (type != StockDocumentType.STOCK_IN) {
                throw ApiException.badRequest("unexpected_supplier", "only a stock-in has a supplier");
            }
            suppliers.findById(command.supplierId())
                    .orElseThrow(() -> ApiException.badRequest("supplier_not_found", "no such supplier"));
        }
        List<LineCommand> requested = command.lines() == null ? List.of() : command.lines();
        if (requested.isEmpty() || requested.size() > MAX_LINES) {
            throw ApiException.badRequest("invalid_lines", "a document has 1 to " + MAX_LINES + " lines");
        }

        Map<UUID, Product> known = products.findAllById(requested.stream().map(LineCommand::productId).toList())
                .stream().collect(Collectors.toMap(Product::getId, Function.identity()));
        List<LineCommand> normalized = new ArrayList<>(requested.size());
        for (LineCommand line : requested) {
            normalized.add(validateLine(type, line, known.get(line.productId())));
        }
        return normalized;
    }

    private static LineCommand validateLine(StockDocumentType type, LineCommand line, Product product) {
        if (product == null) {
            throw ApiException.badRequest("product_not_found", "no such product " + line.productId());
        }
        if (product.isArchived() || !product.isTrackInventory()) {
            throw ApiException.badRequest("product_not_stockable",
                    product.getSku() + " is archived or does not track inventory");
        }
        BigDecimal quantity = line.quantity();
        if (quantity == null || quantity.signum() == 0 || quantity.scale() > WeightedAverage.SCALE) {
            throw ApiException.badRequest("invalid_quantity", "quantity must be non-zero with at most 4 decimals");
        }
        if (type != StockDocumentType.ADJUSTMENT && quantity.signum() < 0) {
            throw ApiException.badRequest("invalid_quantity",
                    "quantities are positive; only an adjustment carries a signed difference");
        }
        BigDecimal unitCost = null;
        if (type.requiresUnitCost(quantity)) {
            unitCost = line.unitCost();
            if (unitCost == null || unitCost.signum() < 0 || unitCost.scale() > WeightedAverage.SCALE) {
                throw ApiException.badRequest("unit_cost_required",
                        "unit cost (zero or more, at most 4 decimals) is required for " + type
                                + (type == StockDocumentType.ADJUSTMENT ? " increases" : ""));
            }
        }
        StockMovementReason reason = null;
        if (type.requiresReason()) {
            reason = line.reason();
            if (reason == null) {
                throw ApiException.badRequest("reason_required", "a reason is required for " + type);
            }
            if (reason == StockMovementReason.VOID_REVERSAL) {
                throw ApiException.badRequest("invalid_reason", "VOID_REVERSAL is written only by a void");
            }
        }
        return new LineCommand(line.productId(), quantity, unitCost, reason);
    }

    private void requireActiveLocation(UUID locationId) {
        if (locationId == null) {
            throw ApiException.badRequest("validation_failed", "locationId is required");
        }
        Location location = locations.findById(locationId)
                .orElseThrow(() -> ApiException.badRequest("location_not_found", "no such location"));
        if (!location.isActive()) {
            throw ApiException.badRequest("location_inactive", location.getCode() + " is closed");
        }
    }

    private static ApiException notFound() {
        return ApiException.notFound("document_not_found", "no such stock document");
    }
}
