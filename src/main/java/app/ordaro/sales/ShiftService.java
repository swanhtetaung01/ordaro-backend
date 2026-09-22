package app.ordaro.sales;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.EnumSet;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.ordaro.org.Location;
import app.ordaro.org.LocationRepository;
import app.ordaro.shared.tenant.TenantContext;
import app.ordaro.shared.web.ApiException;

/**
 * Opening and closing cashier shifts (spec §6). Only a STORE has shifts, one OPEN at a time.
 *
 * <p>Drawer rule: {@code expectedCash = openingFloat + Σ CASH applied on the shift's completed
 * sales + Σ CASH receivable repayments − Σ CASH refunds − Σ CASH expenses − Σ CASH payable
 * settlements}, each counting only when its own {@code cashierShiftId} is this shift. Repayments
 * are an addition to the spec's formula (§12 Step 5 as built): cash a debtor hands over at the
 * register is in the drawer. Expenses join in step 5d.
 */
@Service
public class ShiftService {

    static final EnumSet<SaleStatus> TOOK_MONEY = EnumSet.of(SaleStatus.COMPLETED, SaleStatus.PARTIALLY_REFUNDED,
            SaleStatus.REFUNDED);

    /** Where the expected cash comes from, line by line: what the close screen shows. */
    public record Drawer(BigDecimal openingFloat, BigDecimal cashSales, BigDecimal cashRepayments,
            BigDecimal cashRefunds, BigDecimal cashSupplierPayments, BigDecimal expectedCash) {
    }

    private final CashierShiftRepository shifts;
    private final LocationRepository locations;
    private final Clock clock;

    public ShiftService(CashierShiftRepository shifts, LocationRepository locations, Clock clock) {
        this.shifts = shifts;
        this.locations = locations;
        this.clock = clock;
    }

    @Transactional
    public CashierShift open(UUID locationId, BigDecimal openingFloat) {
        requireStore(locationId);
        if (openingFloat == null || openingFloat.signum() < 0) {
            throw ApiException.badRequest("invalid_float", "the opening float is zero or more");
        }
        if (shifts.findByLocationIdAndStatus(locationId, ShiftStatus.OPEN).isPresent()) {
            throw ApiException.conflict("shift_already_open", "this location already has an open shift");
        }
        return shifts.saveAndFlush(new CashierShift(locationId, TenantContext.requireMembershipId(), clock.instant(),
                openingFloat));
    }

    @Transactional
    public CashierShift close(UUID shiftId, BigDecimal countedCash) {
        if (countedCash == null || countedCash.signum() < 0) {
            throw ApiException.badRequest("invalid_count", "the counted cash is zero or more");
        }
        CashierShift shift = shifts.lockForClose(shiftId)
                .orElseThrow(() -> ApiException.notFound("shift_not_found", "no such shift"));
        TenantContext.requireLocationInScope(shift.getLocationId());
        if (!shift.isOpen()) {
            throw ApiException.conflict("shift_closed", "the shift is already closed");
        }
        shift.close(TenantContext.requireMembershipId(), clock.instant(), drawerOf(shift).expectedCash(), countedCash);
        return shift;
    }

    /** The running drawer of an open shift, or the one a closed shift was counted against. */
    public Drawer drawer(UUID shiftId) {
        CashierShift shift = shifts.findById(shiftId)
                .orElseThrow(() -> ApiException.notFound("shift_not_found", "no such shift"));
        TenantContext.requireLocationInScope(shift.getLocationId());
        return drawerOf(shift);
    }

    private Drawer drawerOf(CashierShift shift) {
        BigDecimal sales = shifts.cashTaken(shift.getId(), TOOK_MONEY);
        BigDecimal repayments = shifts.cashCollected(shift.getId());
        BigDecimal refunds = shifts.cashRefunded(shift.getId());
        BigDecimal suppliers = shifts.cashPaidToSuppliers(shift.getId());
        return new Drawer(shift.getOpeningFloat(), sales, repayments, refunds, suppliers,
                shift.getOpeningFloat().add(sales).add(repayments).subtract(refunds).subtract(suppliers));
    }

    /**
     * For money that moves through a drawer outside a sale — a repayment, a refund, an expense, a
     * supplier paid in cash. Takes the same shared lock as a sale completion, so a close waits for
     * it; the shift must be OPEN and at {@code locationId}.
     */
    public CashierShift requireOpenForCash(UUID shiftId, UUID locationId) {
        CashierShift shift = shifts.lockShared(shiftId)
                .orElseThrow(() -> ApiException.badRequest("shift_not_found", "no such shift"));
        if (!shift.getLocationId().equals(locationId)) {
            throw ApiException.badRequest("shift_elsewhere", "the shift belongs to another location");
        }
        if (!shift.isOpen()) {
            throw ApiException.conflict("shift_closed", "the shift is closed");
        }
        return shift;
    }

    public CashierShift current(UUID locationId) {
        TenantContext.requireLocationInScope(locationId);
        return shifts.findByLocationIdAndStatus(locationId, ShiftStatus.OPEN)
                .orElseThrow(() -> ApiException.notFound("no_open_shift", "no open shift at this location"));
    }

    /** Only a STORE has registers, shifts and receipt sequences (spec §12 Locations). */
    Location requireStore(UUID locationId) {
        TenantContext.requireLocationInScope(locationId);
        Location location = locations.findById(locationId)
                .orElseThrow(() -> ApiException.badRequest("location_not_found", "no such location"));
        if (!location.isStore()) {
            throw ApiException.badRequest("not_a_store", location.getCode() + " is a " + location.getType()
                    + "; only a STORE has shifts, registers and receipts");
        }
        if (!location.isActive()) {
            throw ApiException.badRequest("location_inactive", location.getCode() + " is closed");
        }
        return location;
    }
}
