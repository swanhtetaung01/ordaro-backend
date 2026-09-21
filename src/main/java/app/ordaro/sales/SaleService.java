package app.ordaro.sales;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.ordaro.catalog.Product;
import app.ordaro.catalog.ProductRepository;
import app.ordaro.inventory.StockLedger;
import app.ordaro.inventory.StockLedger.Cost;
import app.ordaro.inventory.StockLedger.Entry;
import app.ordaro.inventory.StockMovement;
import app.ordaro.inventory.StockMovementType;
import app.ordaro.inventory.StockReferenceType;
import app.ordaro.org.Location;
import app.ordaro.org.Organization;
import app.ordaro.org.OrganizationRepository;
import app.ordaro.sales.SaleCalculator.LineInput;
import app.ordaro.sales.SaleCalculator.Priced;
import app.ordaro.sales.SaleCalculator.PricedLine;
import app.ordaro.shared.numbering.DocumentNumbers;
import app.ordaro.shared.numbering.DocumentSequenceType;
import app.ordaro.shared.tenant.TenantContext;
import app.ordaro.shared.web.ApiException;

/**
 * Parked carts (DRAFT, HELD) and the completion transaction (spec §9.2). Completion, in one
 * transaction:
 * <ol start="0">
 * <li>idempotency — the key is claimed on the sale row first, so a concurrent retry waits on
 * the unique index and then finds the original;</li>
 * <li>price the basket (§9.2 step 4) and validate payments;</li>
 * <li>take the receipt number from the STORE's RCP sequence (a rollback returns it);</li>
 * <li>{@link StockLedger#post}: ensure and lock balances, check stock, write one SALE movement
 * per tracked line, update balances (§9.2 steps 1, 2, 5, 6);</li>
 * <li>copy each movement's unit cost into its line — the snapshot gross profit rests on
 * (step 3); 0 for products that do not track inventory;</li>
 * <li>insert payments and complete the sale (steps 7, 8).</li>
 * </ol>
 * The receipt number is taken before the movements so each movement carries it; within one
 * transaction the order is invisible.
 */
@Service
public class SaleService {

    static final int MAX_LINES = 300;

    public record LineCommand(UUID productId, BigDecimal quantity, BigDecimal discountAmount) {
    }

    public record CartCommand(UUID locationId, SaleChannel channel, UUID cashierShiftId, PriceType priceType,
            BigDecimal cartDiscountAmount, List<LineCommand> lines) {
    }

    public record PaymentCommand(PaymentMethod method, BigDecimal amount, BigDecimal tenderedAmount,
            String referenceNo) {
    }

    public record SaleDetails(Sale sale, List<SaleLine> lines, List<Payment> payments) {
    }

    /** {@code replayed}: the idempotency key matched an earlier sale, returned unchanged. */
    public record CompletionResult(SaleDetails details, boolean replayed) {
    }

    private final SaleRepository sales;
    private final SaleLineRepository lines;
    private final PaymentRepository payments;
    private final CashierShiftRepository shifts;
    private final ShiftService shiftService;
    private final ProductRepository products;
    private final OrganizationRepository organizations;
    private final StockLedger ledger;
    private final DocumentNumbers numbers;
    private final Clock clock;

    public SaleService(SaleRepository sales, SaleLineRepository lines, PaymentRepository payments,
            CashierShiftRepository shifts, ShiftService shiftService, ProductRepository products,
            OrganizationRepository organizations, StockLedger ledger, DocumentNumbers numbers, Clock clock) {
        this.sales = sales;
        this.lines = lines;
        this.payments = payments;
        this.shifts = shifts;
        this.shiftService = shiftService;
        this.products = products;
        this.organizations = organizations;
        this.ledger = ledger;
        this.numbers = numbers;
        this.clock = clock;
    }

    // ───────────────────────────────────────────────────────────── parked carts

    @Transactional
    public SaleDetails saveCart(CartCommand cart, boolean hold) {
        Organization organization = organization();
        validateCartHeader(cart, false);
        Sale sale = sales.save(new Sale(cart.locationId(), cart.channel(), cart.cashierShiftId(),
                priceType(cart), organization.isTaxInclusivePricing(), hold ? SaleStatus.HELD : SaleStatus.DRAFT));
        return new SaleDetails(sale, writeProvisionalLines(sale, cart, organization), List.of());
    }

    @Transactional
    public SaleDetails replaceCart(UUID saleId, CartCommand cart, boolean hold) {
        Organization organization = organization();
        Sale sale = lockOpenCart(saleId);
        validateCartHeader(cart, false);
        if (!sale.getLocationId().equals(cart.locationId())) {
            throw ApiException.badRequest("location_changed", "a cart stays at the location it was started at");
        }
        sale.reviseCart(cart.channel(), cart.cashierShiftId(), priceType(cart), organization.isTaxInclusivePricing());
        lines.deleteAllOfSale(saleId);
        List<SaleLine> saved = writeProvisionalLines(sale, cart, organization);
        if (hold && sale.getStatus() == SaleStatus.DRAFT) {
            sale.hold();
        }
        return new SaleDetails(sale, saved, List.of());
    }

    @Transactional
    public SaleDetails hold(UUID saleId) {
        Sale sale = lockOpenCart(saleId);
        sale.hold();
        return details(sale);
    }

    /** VOID only from DRAFT or HELD; a completed sale is undone by a Return, never a void. */
    @Transactional
    public SaleDetails voidCart(UUID saleId) {
        Sale sale = sales.lockById(saleId).orElseThrow(SaleService::notFound);
        TenantContext.requireLocationInScope(sale.getLocationId());
        if (!sale.isOpenCart()) {
            throw ApiException.conflict("sale_not_voidable",
                    "a " + sale.getStatus() + " sale cannot be voided; completed sales are undone by a return");
        }
        sale.voidCart(clock.instant());
        return details(sale);
    }

    // ───────────────────────────────────────────────────────────── completion

    /** Complete a basket in one call (the register's normal path). */
    @Transactional
    public CompletionResult checkout(String idempotencyKey, CartCommand cart, List<PaymentCommand> tenders) {
        requireKey(idempotencyKey);
        var existing = sales.findByLocationIdAndIdempotencyKey(cart.locationId(), idempotencyKey);
        if (existing.isPresent()) {
            TenantContext.requireLocationInScope(existing.get().getLocationId());
            return new CompletionResult(details(existing.get()), true);
        }
        Organization organization = organization();
        validateCartHeader(cart, true);
        Sale sale = new Sale(cart.locationId(), cart.channel(), cart.cashierShiftId(), priceType(cart),
                organization.isTaxInclusivePricing(), SaleStatus.DRAFT);
        sale.claimIdempotencyKey(idempotencyKey);
        sale = sales.saveAndFlush(sale); // a concurrent retry now waits here on the unique index
        return new CompletionResult(complete(sale, cart, tenders, organization), false);
    }

    /** Complete a parked cart. */
    @Transactional
    public CompletionResult completeCart(UUID saleId, String idempotencyKey, List<PaymentCommand> tenders) {
        requireKey(idempotencyKey);
        Sale sale = sales.lockById(saleId).orElseThrow(SaleService::notFound);
        TenantContext.requireLocationInScope(sale.getLocationId());
        var existing = sales.findByLocationIdAndIdempotencyKey(sale.getLocationId(), idempotencyKey);
        if (existing.isPresent()) {
            return new CompletionResult(details(existing.get()), true);
        }
        if (!sale.isOpenCart()) {
            throw ApiException.conflict("sale_not_open", "the sale is " + sale.getStatus());
        }
        List<LineCommand> cartLines = lines.findAllBySaleIdOrderByPosition(saleId).stream()
                .map(l -> new LineCommand(l.getProductId(), l.getQuantity(), l.getDiscountAmount()))
                .toList();
        CartCommand cart = new CartCommand(sale.getLocationId(), sale.getChannel(), sale.getCashierShiftId(),
                sale.getPriceType(), sale.getCartDiscountAmount(), cartLines);
        Organization organization = organization();
        validateCartHeader(cart, true);
        sale.reviseCart(cart.channel(), cart.cashierShiftId(), cart.priceType(), organization.isTaxInclusivePricing());
        sale.claimIdempotencyKey(idempotencyKey);
        sales.saveAndFlush(sale);
        lines.deleteAllOfSale(saleId);
        return new CompletionResult(complete(sale, cart, tenders, organization), false);
    }

    private SaleDetails complete(Sale sale, CartCommand cart, List<PaymentCommand> tenders,
            Organization organization) {
        int minor = minorDigits(organization);
        Map<UUID, Product> catalog = sellable(cart);
        Priced priced = price(cart, catalog, organization, minor);
        List<PaymentCommand> validTenders = validatePayments(tenders, priced.totals().total(), minor);

        Location location = shiftService.requireStore(cart.locationId());
        Instant soldAt = clock.instant();
        String receipt = numbers.next(location.getId(), location.getCode(), DocumentSequenceType.RCP, soldAt,
                ZoneId.of(organization.getTimezone()));

        // stock: one SALE movement per tracked line, through the one posting path
        List<Entry> entries = new ArrayList<>();
        List<Integer> entryLine = new ArrayList<>();
        for (int i = 0; i < priced.lines().size(); i++) {
            PricedLine line = priced.lines().get(i);
            if (catalog.get(line.productId()).isTrackInventory()) {
                entries.add(new Entry(cart.locationId(), line.productId(), StockMovementType.SALE,
                        line.quantity().negate(), Cost.currentAverage(), null, true, StockReferenceType.SALE,
                        sale.getId(), receipt, soldAt));
                entryLine.add(i);
            }
        }
        List<StockMovement> movements = ledger.post(entries);
        BigDecimal[] unitCost = new BigDecimal[priced.lines().size()];
        for (int e = 0; e < movements.size(); e++) {
            unitCost[entryLine.get(e)] = movements.get(e).getUnitCost();
        }

        List<SaleLine> savedLines = new ArrayList<>();
        for (int i = 0; i < priced.lines().size(); i++) {
            savedLines.add(lines.save(new SaleLine(sale.getId(), i + 1, priced.lines().get(i),
                    unitCost[i] != null ? unitCost[i] : BigDecimal.ZERO)));
        }
        List<Payment> savedPayments = new ArrayList<>();
        BigDecimal paid = BigDecimal.ZERO;
        for (PaymentCommand tender : validTenders) {
            savedPayments.add(payments.save(new Payment(sale.getId(), tender.method(), tender.amount(),
                    tender.tenderedAmount(), tender.referenceNo())));
            paid = paid.add(tender.amount());
        }
        sale.applyTotals(priced.totals());
        sale.complete(receipt, soldAt, paid);
        return new SaleDetails(sale, savedLines, savedPayments);
    }

    // ───────────────────────────────────────────────────────────── reads

    public SaleDetails find(UUID saleId) {
        Sale sale = sales.findById(saleId).orElseThrow(SaleService::notFound);
        TenantContext.requireLocationInScope(sale.getLocationId());
        return details(sale);
    }

    public UUID locationOf(UUID saleId) {
        return sales.findById(saleId).map(Sale::getLocationId).orElse(null);
    }

    public SaleDetails findByKey(UUID locationId, String idempotencyKey) {
        return sales.findByLocationIdAndIdempotencyKey(locationId, idempotencyKey).map(this::details).orElse(null);
    }

    SaleDetails details(Sale sale) {
        return new SaleDetails(sale, lines.findAllBySaleIdOrderByPosition(sale.getId()),
                payments.findAllBySaleIdOrderByCreatedAtAsc(sale.getId()));
    }

    // ───────────────────────────────────────────────────────────── validation and pricing

    private List<SaleLine> writeProvisionalLines(Sale sale, CartCommand cart, Organization organization) {
        int minor = minorDigits(organization);
        Priced priced = price(cart, sellable(cart), organization, minor);
        sale.applyTotals(priced.totals());
        List<SaleLine> saved = new ArrayList<>();
        for (int i = 0; i < priced.lines().size(); i++) {
            // provisional: completion re-prices and snapshots the real unit cost
            saved.add(lines.save(new SaleLine(sale.getId(), i + 1, priced.lines().get(i), BigDecimal.ZERO)));
        }
        return saved;
    }

    private void validateCartHeader(CartCommand cart, boolean completing) {
        if (cart.channel() == null || cart.locationId() == null) {
            throw ApiException.badRequest("validation_failed", "locationId and channel are required");
        }
        shiftService.requireStore(cart.locationId());
        switch (cart.channel()) {
            case ORDER -> throw ApiException.badRequest("orders_not_available",
                    "order fulfilment arrives with the Order entity; use POS or ONLINE");
            case ONLINE -> {
                if (cart.cashierShiftId() != null) {
                    throw ApiException.badRequest("unexpected_shift", "an online sale has no register shift");
                }
            }
            case POS -> {
                if (cart.cashierShiftId() == null) {
                    if (completing) {
                        throw ApiException.badRequest("shift_required", "a POS sale belongs to an open shift");
                    }
                    return;
                }
                CashierShift shift = (completing ? shifts.lockShared(cart.cashierShiftId())
                        : shifts.findById(cart.cashierShiftId()))
                        .orElseThrow(() -> ApiException.badRequest("shift_not_found", "no such shift"));
                if (!shift.getLocationId().equals(cart.locationId())) {
                    throw ApiException.badRequest("shift_elsewhere", "the shift belongs to another location");
                }
                if (!shift.isOpen()) {
                    throw ApiException.conflict("shift_closed", "the shift is closed");
                }
            }
        }
    }

    private Map<UUID, Product> sellable(CartCommand cart) {
        List<LineCommand> requested = cart.lines() == null ? List.of() : cart.lines();
        if (requested.isEmpty() || requested.size() > MAX_LINES) {
            throw ApiException.badRequest("invalid_lines", "a sale has 1 to " + MAX_LINES + " lines");
        }
        Map<UUID, Product> catalog = products.findAllById(requested.stream().map(LineCommand::productId).toList())
                .stream().collect(Collectors.toMap(Product::getId, Function.identity()));
        for (LineCommand line : requested) {
            Product product = catalog.get(line.productId());
            if (product == null) {
                throw ApiException.badRequest("product_not_found", "no such product " + line.productId());
            }
            boolean listed = cart.channel() == SaleChannel.ONLINE ? product.isSellOnline() : product.isSellInPos();
            if (product.isArchived() || !product.isActive() || !listed) {
                throw ApiException.badRequest("product_not_for_sale",
                        product.getSku() + " is not for sale on " + cart.channel());
            }
            if (line.quantity() == null || line.quantity().signum() <= 0 || line.quantity().scale() > 4) {
                throw ApiException.badRequest("invalid_quantity", "quantity is positive with at most 4 decimals");
            }
        }
        return catalog;
    }

    private static Priced price(CartCommand cart, Map<UUID, Product> catalog, Organization organization, int minor) {
        PriceType priceType = priceType(cart);
        List<LineInput> inputs = cart.lines().stream().map(line -> {
            Product p = catalog.get(line.productId());
            BigDecimal unitPrice = priceType == PriceType.WHOLESALE && p.getWholesalePrice() != null
                    ? p.getWholesalePrice()
                    : p.getRetailPrice();
            BigDecimal rate = p.isTaxable() ? organization.getDefaultTaxRate() : BigDecimal.ZERO;
            return new LineInput(p.getId(), p.getName(), p.getSku(), line.quantity(), unitPrice,
                    line.discountAmount(), rate);
        }).toList();
        return SaleCalculator.price(inputs, cart.cartDiscountAmount(), organization.isTaxInclusivePricing(), minor,
                organization.getRoundTotalToNearest());
    }

    /** Σ payments must equal the total: a completed sale has nothing due (spec §6). */
    private static List<PaymentCommand> validatePayments(List<PaymentCommand> tenders, BigDecimal total, int minor) {
        if (tenders == null || tenders.isEmpty()) {
            throw ApiException.badRequest("payment_required", "a completed sale is paid");
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (PaymentCommand tender : tenders) {
            if (tender.method() == null || tender.amount() == null || tender.amount().signum() <= 0
                    || tender.amount().stripTrailingZeros().scale() > minor) {
                throw ApiException.badRequest("invalid_payment", "each payment has a method and a positive amount");
            }
            if (tender.method() == PaymentMethod.CREDIT) {
                throw ApiException.badRequest("credit_not_available",
                        "credit sales need a customer and a receivable, which arrive in step 5");
            }
            if (tender.tenderedAmount() != null) {
                if (tender.method() != PaymentMethod.CASH) {
                    throw ApiException.badRequest("invalid_payment", "only cash is tendered");
                }
                if (tender.tenderedAmount().compareTo(tender.amount()) < 0) {
                    throw ApiException.badRequest("insufficient_tender", "tendered cash is less than the amount");
                }
            }
            sum = sum.add(tender.amount());
        }
        if (sum.compareTo(total) != 0) {
            throw ApiException.badRequest("payment_mismatch",
                    "payments add up to " + sum.toPlainString() + " but the total is " + total.toPlainString());
        }
        return tenders;
    }

    private Sale lockOpenCart(UUID saleId) {
        Sale sale = sales.lockById(saleId).orElseThrow(SaleService::notFound);
        TenantContext.requireLocationInScope(sale.getLocationId());
        if (!sale.isOpenCart()) {
            throw ApiException.conflict("sale_not_open", "the sale is " + sale.getStatus());
        }
        return sale;
    }

    private Organization organization() {
        return organizations.findById(TenantContext.requireOrganizationId()).orElseThrow();
    }

    private static PriceType priceType(CartCommand cart) {
        return cart.priceType() != null ? cart.priceType() : PriceType.RETAIL;
    }

    static int minorDigits(Organization organization) {
        return Currency.getInstance(organization.getCurrencyCode()).getDefaultFractionDigits();
    }

    private static void requireKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 100) {
            throw ApiException.badRequest("idempotency_key_required", "a completion carries a 1–100 character key");
        }
    }

    private static ApiException notFound() {
        return ApiException.notFound("sale_not_found", "no such sale");
    }
}
