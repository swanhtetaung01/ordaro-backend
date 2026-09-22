package app.ordaro.sales;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/shifts")
@PreAuthorize("hasAnyRole('OWNER', 'STOCK_MANAGER', 'CASHIER')")
class ShiftController {

    record OpenRequest(@NotNull UUID locationId, @NotNull BigDecimal openingFloat) {
    }

    record CloseRequest(@NotNull BigDecimal countedCash) {
    }

    record ShiftView(UUID id, UUID locationId, ShiftStatus status, UUID openedBy, Instant openedAt,
            BigDecimal openingFloat, UUID closedBy, Instant closedAt, BigDecimal expectedCash,
            BigDecimal countedCash, BigDecimal variance) {

        static ShiftView of(CashierShift s) {
            return new ShiftView(s.getId(), s.getLocationId(), s.getStatus(), s.getOpenedBy(), s.getOpenedAt(),
                    s.getOpeningFloat(), s.getClosedBy(), s.getClosedAt(), s.getExpectedCash(), s.getCountedCash(),
                    s.getVariance());
        }
    }

    private final ShiftService shifts;

    ShiftController(ShiftService shifts) {
        this.shifts = shifts;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ShiftView open(@Valid @RequestBody OpenRequest request) {
        return ShiftView.of(shifts.open(request.locationId(), request.openingFloat()));
    }

    @GetMapping("/current")
    ShiftView current(@RequestParam UUID locationId) {
        return ShiftView.of(shifts.current(locationId));
    }

    /** What the drawer should hold, and why — for the close screen. */
    @GetMapping("/{id}/drawer")
    ShiftService.Drawer drawer(@PathVariable UUID id) {
        return shifts.drawer(id);
    }

    /** Count the drawer; the variance is stored now and never recomputed. */
    @PostMapping("/{id}/close")
    ShiftView close(@PathVariable UUID id, @Valid @RequestBody CloseRequest request) {
        return ShiftView.of(shifts.close(id, request.countedCash()));
    }
}
