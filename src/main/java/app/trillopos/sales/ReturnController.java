package app.trillopos.sales;

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
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import app.trillopos.sales.ReturnService.LineCommand;
import app.trillopos.sales.ReturnService.ReturnCommand;
import app.trillopos.sales.ReturnService.ReturnDetails;
import app.trillopos.sales.ReturnService.ReturnResult;

/** Returns (spec §6). Anyone who sells may take one; the numbers come from the store's RTN book. */
@RestController
@RequestMapping("/returns")
@PreAuthorize("hasAnyRole('OWNER', 'STOCK_MANAGER', 'CASHIER')")
class ReturnController {

    @Schema(name = "ReturnLineRequest")
    record LineRequest(@NotNull UUID saleLineId, @NotNull BigDecimal quantity, Boolean restock) {
    }

    /** {@code restock} defaults to true; false for damaged goods. */
    record ReturnRequest(@NotNull UUID saleId, @NotNull UUID locationId, UUID cashierShiftId,
            @NotNull PaymentMethod refundMethod, @Size(max = 100) String referenceNo, @Size(max = 500) String reason,
            @NotEmpty List<@Valid LineRequest> lines, @Size(max = 100) String idempotencyKey) {

        ReturnCommand command() {
            return new ReturnCommand(saleId, locationId, cashierShiftId, refundMethod, referenceNo, reason,
                    lines.stream().map(l -> new LineCommand(l.saleLineId(), l.quantity(), l.restock())).toList(),
                    idempotencyKey);
        }
    }

    /** {@code unitCost} is null for roles that do not see costs. */
    @Schema(name = "ReturnLineView")
    record LineView(UUID id, UUID saleLineId, UUID productId, BigDecimal quantity, BigDecimal refundAmount,
            BigDecimal taxAmount, boolean restock, BigDecimal unitCost) {
    }

    record ReturnView(UUID id, String returnNumber, UUID saleId, UUID locationId, UUID cashierShiftId,
            PaymentMethod refundMethod, BigDecimal refundAmount, BigDecimal taxAmount, String referenceNo,
            String reason, Instant returnedAt, List<LineView> lines) {

        static ReturnView of(ReturnDetails d, boolean seesCost) {
            SaleReturn r = d.saleReturn();
            return new ReturnView(r.getId(), r.getReturnNumber(), r.getOriginalSaleId(), r.getLocationId(),
                    r.getCashierShiftId(), r.getRefundMethod(), r.getRefundAmount(), r.getTaxAmount(),
                    r.getReferenceNo(), r.getReason(), r.getReturnedAt(), d.lines().stream()
                            .map(l -> new LineView(l.getId(), l.getSaleLineId(), l.getProductId(), l.getQuantity(),
                                    l.getRefundAmount(), l.getTaxAmount(), l.isRestock(),
                                    seesCost ? l.getUnitCost() : null))
                            .toList());
        }
    }

    private final ReturnService returns;

    ReturnController(ReturnService returns) {
        this.returns = returns;
    }

    /** A repeat of the same {@code idempotencyKey} returns the original with 200. */
    @PostMapping
    ResponseEntity<ReturnView> create(@Valid @RequestBody ReturnRequest request, Authentication auth) {
        ReturnResult result = returns.create(request.command());
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .header("Idempotent-Replay", String.valueOf(result.replayed()))
                .body(ReturnView.of(result.details(), seesCost(auth)));
    }

    @GetMapping("/{id}")
    ReturnView get(@PathVariable UUID id, Authentication auth) {
        return ReturnView.of(returns.find(id), seesCost(auth));
    }

    private static boolean seesCost(Authentication authentication) {
        return authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals("ROLE_OWNER") || a.equals("ROLE_STOCK_MANAGER"));
    }
}
