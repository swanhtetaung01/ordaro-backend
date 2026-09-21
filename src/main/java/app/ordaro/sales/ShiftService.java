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
 * sales − Σ CASH refunds − Σ CASH expenses − Σ CASH payable settlements}, each cash-out counting
 * only when its own {@code cashierShiftId} is this shift. Returns, expenses and payable
 * settlements arrive in step 5; until then the sales side is the whole formula.
 */
@Service
public class ShiftService {

    static final EnumSet<SaleStatus> TOOK_MONEY = EnumSet.of(SaleStatus.COMPLETED, SaleStatus.PARTIALLY_REFUNDED,
            SaleStatus.REFUNDED);

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
        BigDecimal expected = shift.getOpeningFloat().add(shifts.cashTaken(shiftId, TOOK_MONEY));
        shift.close(TenantContext.requireMembershipId(), clock.instant(), expected, countedCash);
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
