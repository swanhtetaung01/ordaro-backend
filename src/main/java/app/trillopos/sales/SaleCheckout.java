package app.trillopos.sales;

import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import app.trillopos.sales.SaleService.CartCommand;
import app.trillopos.sales.SaleService.CompletionResult;
import app.trillopos.sales.SaleService.PaymentCommand;
import app.trillopos.sales.SaleService.SaleDetails;

/**
 * Idempotent completion (spec §9.2 step 0). {@link SaleService#checkout} claims the key on the
 * sale row first; a concurrent duplicate blocks on the unique index until the first commits, then
 * fails there. That failure aborts its transaction, so the original is read here, in a new one,
 * and returned as a replay. Deliberately not transactional itself.
 */
@Service
public class SaleCheckout {

    private final SaleService sales;

    public SaleCheckout(SaleService sales) {
        this.sales = sales;
    }

    public CompletionResult checkout(String idempotencyKey, CartCommand cart, List<PaymentCommand> tenders) {
        try {
            return sales.checkout(idempotencyKey, cart, tenders);
        } catch (DataIntegrityViolationException e) {
            return replayOrRethrow(cart.locationId(), idempotencyKey, e);
        }
    }

    public CompletionResult completeCart(UUID saleId, String idempotencyKey, List<PaymentCommand> tenders) {
        try {
            return sales.completeCart(saleId, idempotencyKey, tenders);
        } catch (DataIntegrityViolationException e) {
            return replayOrRethrow(sales.locationOf(saleId), idempotencyKey, e);
        }
    }

    private CompletionResult replayOrRethrow(UUID locationId, String idempotencyKey, DataIntegrityViolationException e) {
        SaleDetails original = locationId == null ? null : sales.findByKey(locationId, idempotencyKey);
        if (original == null) {
            throw e;
        }
        return new CompletionResult(original, true);
    }
}
