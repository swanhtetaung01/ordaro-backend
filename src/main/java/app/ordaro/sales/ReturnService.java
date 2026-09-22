package app.ordaro.sales;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.ordaro.catalog.Product;
import app.ordaro.catalog.ProductRepository;
import app.ordaro.finance.Receivable;
import app.ordaro.finance.ReceivableService;
import app.ordaro.inventory.StockLedger;
import app.ordaro.inventory.StockLedger.Cost;
import app.ordaro.inventory.StockLedger.Entry;
import app.ordaro.inventory.StockMovementType;
import app.ordaro.inventory.StockReferenceType;
import app.ordaro.org.Location;
import app.ordaro.org.Organization;
import app.ordaro.org.OrganizationRepository;
import app.ordaro.shared.money.Money;
import app.ordaro.shared.numbering.DocumentNumbers;
import app.ordaro.shared.numbering.DocumentSequenceType;
import app.ordaro.shared.persistence.UuidV7Generator;
import app.ordaro.shared.tenant.TenantContext;
import app.ordaro.shared.web.ApiException;

/**
 * Returns against completed sales (spec §6, §9.3). One transaction: lock the sale, check each
 * line's returned-so-far, compute pro-rata refunds, take the RTN number, restock through
 * {@link StockLedger} at each line's original cost, record the refund (a cash refund from a
 * register comes out of that drawer; CREDIT settles the customer's receivable instead), and move
 * the sale to PARTIALLY_REFUNDED or REFUNDED.
 */
@Service
public class ReturnService {

    public record LineCommand(UUID saleLineId, BigDecimal quantity, Boolean restock) {
    }

    public record ReturnCommand(UUID saleId, UUID locationId, UUID cashierShiftId, PaymentMethod refundMethod,
            String referenceNo, String reason, List<LineCommand> lines, String idempotencyKey) {
    }

    public record ReturnDetails(SaleReturn saleReturn, List<SaleReturnLine> lines) {
    }

    /** {@code replayed}: the idempotency key matched an earlier return, returned unchanged. */
    public record ReturnResult(ReturnDetails details, boolean replayed) {
    }

    private final SaleRepository sales;
    private final SaleLineRepository saleLines;
    private final SaleReturnRepository returns;
    private final SaleReturnLineRepository returnLines;
    private final ProductRepository products;
    private final OrganizationRepository organizations;
    private final ShiftService shiftService;
    private final ReceivableService receivables;
    private final StockLedger ledger;
    private final DocumentNumbers numbers;
    private final Clock clock;

    public ReturnService(SaleRepository sales, SaleLineRepository saleLines, SaleReturnRepository returns,
            SaleReturnLineRepository returnLines, ProductRepository products, OrganizationRepository organizations,
            ShiftService shiftService, ReceivableService receivables, StockLedger ledger, DocumentNumbers numbers,
            Clock clock) {
        this.sales = sales;
        this.saleLines = saleLines;
        this.returns = returns;
        this.returnLines = returnLines;
        this.products = products;
        this.organizations = organizations;
        this.shiftService = shiftService;
        this.receivables = receivables;
        this.ledger = ledger;
        this.numbers = numbers;
        this.clock = clock;
    }

    @Transactional
    public ReturnResult create(ReturnCommand command) {
        Sale sale = sales.lockById(command.saleId())
                .orElseThrow(() -> ApiException.notFound("sale_not_found", "no such sale"));
        TenantContext.requireLocationInScope(sale.getLocationId());
        String key = command.idempotencyKey();
        if (key != null && (key.isBlank() || key.length() > 100)) {
            throw ApiException.badRequest("invalid_idempotency_key", "the key is 1–100 characters");
        }
        if (key != null) {
            var earlier = returns.findByOriginalSaleIdAndIdempotencyKey(sale.getId(), key);
            if (earlier.isPresent()) {
                return new ReturnResult(details(earlier.get()), true);
            }
        }
        if (!sale.tookMoney()) {
            throw ApiException.conflict("sale_not_completed", "only a completed sale is returned; this one is "
                    + sale.getStatus());
        }
        if (sale.getStatus() == SaleStatus.REFUNDED) {
            throw ApiException.conflict("sale_fully_refunded", "everything on this sale has been returned");
        }
        Organization organization = organization();
        int minor = Money.minorDigits(organization.getCurrencyCode());
        Location store = shiftService.requireStore(command.locationId());
        if (command.refundMethod() == null) {
            throw ApiException.badRequest("refund_method_required", "how did the money go back?");
        }
        if (command.refundMethod() == PaymentMethod.CREDIT && sale.getCustomerId() == null) {
            throw ApiException.badRequest("customer_required", "a CREDIT refund reduces a customer's receivable");
        }
        if (command.cashierShiftId() != null) {
            if (command.refundMethod() != PaymentMethod.CASH) {
                throw ApiException.badRequest("unexpected_shift", "only a cash refund comes out of a drawer");
            }
            shiftService.requireOpenForCash(command.cashierShiftId(), command.locationId());
        }

        // what was sold, and how much of each line has already come back
        Map<UUID, SaleLine> sold = saleLines.findAllBySaleIdOrderByPosition(sale.getId()).stream()
                .collect(Collectors.toMap(SaleLine::getId, Function.identity()));
        Map<UUID, BigDecimal> returnedSoFar = new HashMap<>();
        for (Returned r : returnLines.returnedSoFar(sold.keySet())) {
            returnedSoFar.put(r.saleLineId(), r.quantity());
        }
        List<LineCommand> requested = command.lines() == null ? List.of() : command.lines();
        if (requested.isEmpty()) {
            throw ApiException.badRequest("invalid_lines", "a return has at least one line");
        }
        Map<UUID, BigDecimal> requestedPerLine = new HashMap<>();
        for (LineCommand line : requested) {
            SaleLine original = sold.get(line.saleLineId());
            if (original == null) {
                throw ApiException.badRequest("sale_line_not_found", "line " + line.saleLineId()
                        + " is not on this sale");
            }
            if (line.quantity() == null || line.quantity().signum() <= 0 || line.quantity().scale() > 4) {
                throw ApiException.badRequest("invalid_quantity", "quantity is positive with at most 4 decimals");
            }
            requestedPerLine.merge(original.getId(), line.quantity(), BigDecimal::add);
        }
        for (var entry : requestedPerLine.entrySet()) {
            SaleLine original = sold.get(entry.getKey());
            BigDecimal already = returnedSoFar.getOrDefault(original.getId(), BigDecimal.ZERO);
            if (already.add(entry.getValue()).compareTo(original.getQuantity()) > 0) {
                throw ApiException.conflict("return_exceeds_sold", original.getProductName() + ": "
                        + original.getQuantity().subtract(already).stripTrailingZeros().toPlainString()
                        + " can still be returned");
            }
        }

        // number, then goods, then money — like a sale, so each movement carries the number
        Instant returnedAt = clock.instant();
        String number = numbers.next(store.getId(), store.getCode(), DocumentSequenceType.RTN, returnedAt,
                ZoneId.of(organization.getTimezone()));
        Map<UUID, Product> catalog = products.findAllById(requested.stream()
                .map(l -> sold.get(l.saleLineId()).getProductId()).distinct().toList()).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        UUID returnId = UuidV7Generator.newId();
        List<Entry> entries = new ArrayList<>();
        List<SaleReturnLine> lines = new ArrayList<>();
        BigDecimal refund = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        Map<UUID, BigDecimal> runningReturned = new HashMap<>(returnedSoFar);
        for (LineCommand line : requested) {
            SaleLine original = sold.get(line.saleLineId());
            BigDecimal before = runningReturned.getOrDefault(original.getId(), BigDecimal.ZERO);
            BigDecimal after = before.add(line.quantity());
            runningReturned.put(original.getId(), after);
            BigDecimal lineRefund = share(original.getLineTotal(), original.getQuantity(), before, after, minor);
            BigDecimal lineTax = share(original.getTaxAmount(), original.getQuantity(), before, after, minor);
            boolean restock = !Boolean.FALSE.equals(line.restock())
                    && catalog.get(original.getProductId()).isTrackInventory();
            if (restock) {
                entries.add(new Entry(store.getId(), original.getProductId(), StockMovementType.SALE_RETURN,
                        line.quantity(), Cost.given(original.getUnitCost()), null, false, StockReferenceType.RETURN,
                        returnId, number, returnedAt));
            }
            lines.add(new SaleReturnLine(returnId, original.getId(), original.getProductId(), line.quantity(),
                    lineRefund, lineTax, original.getUnitCost(), restock));
            refund = refund.add(lineRefund);
            tax = tax.add(lineTax);
        }
        ledger.post(entries);

        SaleReturn saleReturn = returns.saveAndFlush(new SaleReturn(returnId, sale.getId(), store.getId(),
                command.cashierShiftId(), number, command.refundMethod(), refund, tax, command.referenceNo(),
                command.reason(), returnedAt, key));
        List<SaleReturnLine> saved = new ArrayList<>(lines.size());
        for (SaleReturnLine line : lines) {
            saved.add(returnLines.save(line));
        }
        if (command.refundMethod() == PaymentMethod.CREDIT) {
            settleAgainstReceivable(sale, refund, store.getId(), number, returnedAt);
        }
        boolean everything = sold.values().stream().allMatch(l -> runningReturned
                .getOrDefault(l.getId(), BigDecimal.ZERO).compareTo(l.getQuantity()) >= 0);
        sale.markRefunded(everything);
        return new ReturnResult(new ReturnDetails(saleReturn, saved), false);
    }

    /**
     * A refund on a credit sale never pays out cash: it reduces the customer's receivable. If the
     * receivable was already repaid in full — or the refund is larger than what is still owed —
     * the difference has to go back some other way, so the return is refused and the cashier picks
     * a cash or wallet refund instead.
     */
    private void settleAgainstReceivable(Sale sale, BigDecimal refund, UUID locationId, String number,
            Instant at) {
        Receivable receivable = receivables.forSale(sale.getId());
        if (receivable == null) {
            throw ApiException.badRequest("no_receivable", "this sale was not taken on credit");
        }
        if (!receivable.isOpen() || refund.compareTo(receivable.getOutstandingAmount()) > 0) {
            throw ApiException.conflict("refund_exceeds_outstanding", "only "
                    + receivable.getOutstandingAmount().stripTrailingZeros().toPlainString()
                    + " is still owed on this sale; refund the rest in cash or to a wallet");
        }
        receivables.settleByReturn(receivable.getId(), refund, locationId, number, at);
    }

    /**
     * The refund for units {@code before}..{@code after} of a line: the line's total shared
     * pro-rata by quantity, and once the last unit comes back, exactly what is left of it — so the
     * sum of a line's refunds is its total, whatever the rounding along the way.
     */
    static BigDecimal share(BigDecimal lineAmount, BigDecimal soldQuantity, BigDecimal before, BigDecimal after,
            int minor) {
        BigDecimal upToBefore = lineAmount.multiply(before).divide(soldQuantity, minor, RoundingMode.HALF_UP);
        BigDecimal upToAfter = after.compareTo(soldQuantity) >= 0 ? lineAmount
                : lineAmount.multiply(after).divide(soldQuantity, minor, RoundingMode.HALF_UP);
        return upToAfter.subtract(upToBefore);
    }

    // ───────────────────────────────────────────────────────────── reads

    public ReturnDetails find(UUID returnId) {
        SaleReturn saleReturn = returns.findById(returnId)
                .orElseThrow(() -> ApiException.notFound("return_not_found", "no such return"));
        TenantContext.requireLocationInScope(saleReturn.getLocationId());
        return details(saleReturn);
    }

    public List<ReturnDetails> forSale(UUID saleId) {
        return returns.findAllByOriginalSaleIdOrderByReturnedAtAscIdAsc(saleId).stream().map(this::details).toList();
    }

    private ReturnDetails details(SaleReturn saleReturn) {
        return new ReturnDetails(saleReturn, returnLines.findAllByReturnIdOrderByIdAsc(saleReturn.getId()));
    }

    private Organization organization() {
        return organizations.findById(TenantContext.requireOrganizationId()).orElseThrow();
    }
}
