package app.ordaro.sales;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import app.ordaro.sales.SaleService.CartCommand;
import app.ordaro.sales.SaleService.CompletionResult;
import app.ordaro.sales.SaleService.LineCommand;
import app.ordaro.sales.SaleService.PaymentCommand;
import app.ordaro.sales.SaleService.SaleDetails;

/** The register's API. Owners, stock managers and cashiers; not packers. */
@RestController
@RequestMapping("/sales")
@PreAuthorize("hasAnyRole('OWNER', 'STOCK_MANAGER', 'CASHIER')")
class SaleController {

    record LineRequest(@NotNull UUID productId, @NotNull BigDecimal quantity, BigDecimal discountAmount) {
    }

    record PaymentRequest(@NotNull PaymentMethod method, @NotNull BigDecimal amount, BigDecimal tenderedAmount,
            @Size(max = 100) String referenceNo) {
    }

    /** {@code Boolean}, not {@code boolean}: Jackson 3 fails on a record's missing primitive. */
    record CartRequest(@NotNull UUID locationId, @NotNull SaleChannel channel, UUID cashierShiftId,
            PriceType priceType, BigDecimal cartDiscountAmount,
            @NotEmpty @Size(max = SaleService.MAX_LINES) List<@Valid LineRequest> lines, Boolean hold) {

        CartCommand command() {
            return new CartCommand(locationId, channel, cashierShiftId, priceType, cartDiscountAmount,
                    lines.stream().map(l -> new LineCommand(l.productId(), l.quantity(), l.discountAmount())).toList());
        }

        boolean holdNow() {
            return Boolean.TRUE.equals(hold);
        }
    }

    record CheckoutRequest(@NotBlank @Size(max = 100) String idempotencyKey, @NotNull UUID locationId,
            @NotNull SaleChannel channel, UUID cashierShiftId, PriceType priceType, BigDecimal cartDiscountAmount,
            @NotEmpty @Size(max = SaleService.MAX_LINES) List<@Valid LineRequest> lines,
            @NotEmpty List<@Valid PaymentRequest> payments) {

        CartCommand cart() {
            return new CartCommand(locationId, channel, cashierShiftId, priceType, cartDiscountAmount,
                    lines.stream().map(l -> new LineCommand(l.productId(), l.quantity(), l.discountAmount())).toList());
        }
    }

    record CompleteRequest(@NotBlank @Size(max = 100) String idempotencyKey,
            @NotEmpty List<@Valid PaymentRequest> payments) {
    }

    /** {@code unitCost} is null for roles that do not see costs, and for parked carts. */
    record LineView(int position, UUID productId, String productName, String sku, BigDecimal quantity,
            BigDecimal unitPrice, BigDecimal discountAmount, BigDecimal cartDiscountAllocated, BigDecimal taxRate,
            BigDecimal taxAmount, BigDecimal lineTotal, BigDecimal unitCost) {
    }

    record PaymentView(PaymentMethod method, BigDecimal amount, BigDecimal tenderedAmount, BigDecimal changeAmount,
            String referenceNo) {
    }

    record SaleView(UUID id, String receiptNumber, SaleStatus status, SaleChannel channel, UUID locationId,
            UUID cashierShiftId, PriceType priceType, boolean taxInclusive, BigDecimal subtotal,
            BigDecimal lineDiscountTotal, BigDecimal cartDiscountAmount, BigDecimal taxAmount,
            BigDecimal roundingAdjustment, BigDecimal total, BigDecimal paidAmount, BigDecimal dueAmount,
            Instant soldAt, List<LineView> lines, List<PaymentView> payments) {

        static SaleView of(SaleDetails d, boolean seesCost) {
            Sale s = d.sale();
            boolean costKnown = seesCost && !s.isOpenCart() && s.getStatus() != SaleStatus.VOID;
            return new SaleView(s.getId(), s.getReceiptNumber(), s.getStatus(), s.getChannel(), s.getLocationId(),
                    s.getCashierShiftId(), s.getPriceType(), s.isTaxInclusive(), s.getSubtotal(),
                    s.getLineDiscountTotal(), s.getCartDiscountAmount(), s.getTaxAmount(), s.getRoundingAdjustment(),
                    s.getTotal(), s.getPaidAmount(), s.getDueAmount(), s.getSoldAt(),
                    d.lines().stream().map(l -> new LineView(l.getPosition(), l.getProductId(), l.getProductName(),
                            l.getSku(), l.getQuantity(), l.getUnitPrice(), l.getDiscountAmount(),
                            l.getCartDiscountAllocated(), l.getTaxRate(), l.getTaxAmount(), l.getLineTotal(),
                            costKnown ? l.getUnitCost() : null)).toList(),
                    d.payments().stream().map(p -> new PaymentView(p.getMethod(), p.getAmount(),
                            p.getTenderedAmount(), p.getChangeAmount(), p.getReferenceNo())).toList());
        }
    }

    private final SaleService sales;
    private final SaleCheckout checkout;

    SaleController(SaleService sales, SaleCheckout checkout) {
        this.sales = sales;
        this.checkout = checkout;
    }

    /** Complete a basket in one call. A repeat of the same key returns the original with 200. */
    @PostMapping("/checkout")
    ResponseEntity<SaleView> checkout(@Valid @RequestBody CheckoutRequest request, Authentication auth) {
        CompletionResult result = checkout.checkout(request.idempotencyKey(), request.cart(),
                payments(request.payments()));
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .header("Idempotent-Replay", String.valueOf(result.replayed()))
                .body(SaleView.of(result.details(), seesCost(auth)));
    }

    /** Park a cart as DRAFT, or HELD with {@code hold: true}. No number, no stock, no cost. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    SaleView saveCart(@Valid @RequestBody CartRequest request, Authentication auth) {
        return SaleView.of(sales.saveCart(request.command(), request.holdNow()), seesCost(auth));
    }

    @PutMapping("/{id}")
    SaleView replaceCart(@PathVariable UUID id, @Valid @RequestBody CartRequest request, Authentication auth) {
        return SaleView.of(sales.replaceCart(id, request.command(), request.holdNow()), seesCost(auth));
    }

    @PostMapping("/{id}/hold")
    SaleView hold(@PathVariable UUID id, Authentication auth) {
        return SaleView.of(sales.hold(id), seesCost(auth));
    }

    @PostMapping("/{id}/complete")
    SaleView complete(@PathVariable UUID id, @Valid @RequestBody CompleteRequest request, Authentication auth) {
        return SaleView.of(checkout.completeCart(id, request.idempotencyKey(), payments(request.payments()))
                .details(), seesCost(auth));
    }

    @PostMapping("/{id}/void")
    SaleView voidCart(@PathVariable UUID id, Authentication auth) {
        return SaleView.of(sales.voidCart(id), seesCost(auth));
    }

    @GetMapping("/{id}")
    SaleView get(@PathVariable UUID id, Authentication auth) {
        return SaleView.of(sales.find(id), seesCost(auth));
    }

    private static List<PaymentCommand> payments(List<PaymentRequest> requests) {
        return requests.stream().map(p -> new PaymentCommand(p.method(), p.amount(), p.tenderedAmount(),
                p.referenceNo())).toList();
    }

    private static boolean seesCost(Authentication authentication) {
        return authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals("ROLE_OWNER") || a.equals("ROLE_STOCK_MANAGER"));
    }
}
