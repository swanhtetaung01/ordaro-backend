package app.ordaro.reports;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import app.ordaro.reports.ReportService.DayTotal;
import app.ordaro.reports.ReportService.LowStockRow;
import app.ordaro.reports.ReportService.PaymentMixRow;
import app.ordaro.reports.ReportService.ProductTotal;
import app.ordaro.reports.ReportService.Range;
import app.ordaro.reports.ReportService.Summary;

/**
 * The Owner Dashboard's numbers. Every figure here is a margin or a debt, so the till does not
 * see them: owners and stock managers only. {@code from}/{@code to} are dates in the
 * organization's timezone, {@code to} inclusive; with neither, today.
 */
@RestController
@RequestMapping("/reports")
@PreAuthorize("hasAnyRole('OWNER', 'STOCK_MANAGER')")
class ReportController {

    private final ReportService reports;

    ReportController(ReportService reports) {
        this.reports = reports;
    }

    /** The tiles: sales, gross profit, returns, expenses, and what is owed either way. */
    @GetMapping("/summary")
    Summary summary(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) UUID locationId) {
        return reports.summary(range(from, to), locationId);
    }

    @GetMapping("/sales-by-day")
    List<DayTotal> salesByDay(@RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to, @RequestParam(required = false) UUID locationId) {
        return reports.salesByDay(range(from, to), locationId);
    }

    /** Best sellers by net revenue. */
    @GetMapping("/top-products")
    List<ProductTotal> topProducts(@RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to, @RequestParam(required = false) UUID locationId,
            @RequestParam(defaultValue = "10") int limit) {
        return reports.topProducts(range(from, to), locationId, limit);
    }

    /** At or below the reorder point, the emptiest first. */
    @GetMapping("/low-stock")
    List<LowStockRow> lowStock(@RequestParam(required = false) UUID locationId,
            @RequestParam(defaultValue = "20") int limit) {
        return reports.lowStock(locationId, limit);
    }

    /** Cash against each wallet, for reconciling their statements. */
    @GetMapping("/payment-mix")
    List<PaymentMixRow> paymentMix(@RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to, @RequestParam(required = false) UUID locationId) {
        return reports.paymentMix(range(from, to), locationId);
    }

    private Range range(LocalDate from, LocalDate to) {
        return reports.range(from, to);
    }
}
