package app.ordaro.reports;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.ordaro.org.OrganizationRepository;
import app.ordaro.shared.tenant.TenantContext;
import app.ordaro.shared.web.ApiException;

/**
 * The dashboard's numbers (spec §9.3 and §11 step 6). Native SQL, because these are aggregates
 * over columns, not object graphs; every statement names {@code organization_id} explicitly and
 * runs inside a transaction, so it also works once row-level security is on (step 6b).
 *
 * <p>Gross profit reads only the snapshots on {@code sale_line} and {@code sale_return_line} —
 * never today's cost, never a join to product (spec §9.3). A REFUNDED sale stays in the sales
 * side; the returns side is the only place a refund is subtracted, or it would come off twice.
 */
@Service
public class ReportService {

    /** A closed date range in the organization's timezone, {@code to} inclusive. */
    public record Range(LocalDate from, LocalDate to) {
    }

    /**
     * {@code netRevenue} and {@code cogs} are the sales side; {@code revenueReversed} and
     * {@code cogsReversed} the returns side; {@code grossProfit} is what the tile shows.
     * {@code receivablesOutstanding} and {@code payablesOutstanding} are as of now, not of the
     * range — "who owes me" is a live figure.
     */
    public record Summary(LocalDate from, LocalDate to, long salesCount, BigDecimal grossSales,
            BigDecimal netRevenue, BigDecimal taxCollected, BigDecimal cogs, long returnCount,
            BigDecimal refundAmount, BigDecimal revenueReversed, BigDecimal cogsReversed, BigDecimal grossProfit,
            BigDecimal expenses, BigDecimal receivablesOutstanding, BigDecimal payablesOutstanding) {
    }

    public record DayTotal(LocalDate day, long salesCount, BigDecimal grossSales, BigDecimal netRevenue,
            BigDecimal grossProfit) {
    }

    public record ProductTotal(UUID productId, String productName, String sku, BigDecimal quantity,
            BigDecimal netRevenue, BigDecimal grossProfit) {
    }

    public record LowStockRow(UUID productId, String productName, String sku, UUID locationId, String locationCode,
            BigDecimal quantity, int reorderPoint) {
    }

    public record PaymentMixRow(String method, long count, BigDecimal amount) {
    }

    private final JdbcTemplate jdbc;
    private final OrganizationRepository organizations;
    private final Clock clock;

    public ReportService(JdbcTemplate jdbc, OrganizationRepository organizations, Clock clock) {
        this.jdbc = jdbc;
        this.organizations = organizations;
        this.clock = clock;
    }

    /** Defaults to today in the organization's timezone; refuses a backwards range. */
    public Range range(LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now(clock.withZone(zone()));
        Range range = new Range(from != null ? from : today, to != null ? to : today);
        if (range.to().isBefore(range.from())) {
            throw ApiException.badRequest("invalid_range", "the range ends before it starts");
        }
        return range;
    }

    @Transactional(readOnly = true)
    public Summary summary(Range range, UUID locationId) {
        UUID organizationId = TenantContext.requireOrganizationId();
        UUID location = scoped(locationId);
        Instant from = startOf(range.from());
        Instant to = startOf(range.to().plusDays(1));

        // sales side (spec §9.3): every sale that took money, refunded later or not
        var sales = jdbc.queryForMap("""
                select count(distinct s.id)                                  as sales_count,
                       coalesce(sum(sl.line_total), 0)                       as line_total,
                       coalesce(sum(sl.tax_amount), 0)                       as tax_amount,
                       coalesce(sum(sl.unit_cost * sl.quantity), 0)          as cogs
                from sale_line sl join sale s on s.id = sl.sale_id
                where s.organization_id = ?
                  and s.status in ('COMPLETED', 'PARTIALLY_REFUNDED', 'REFUNDED')
                  and (?::uuid is null or s.location_id = ?::uuid)
                  and s.sold_at >= ? and s.sold_at < ?
                """, organizationId, location, location, timestamp(from), timestamp(to));
        // the header total carries the rounding adjustment, which no line does
        BigDecimal grossSales = amount(jdbc.queryForObject("""
                select coalesce(sum(s.total), 0) from sale s
                where s.organization_id = ? and s.status in ('COMPLETED', 'PARTIALLY_REFUNDED', 'REFUNDED')
                  and (?::uuid is null or s.location_id = ?::uuid)
                  and s.sold_at >= ? and s.sold_at < ?
                """, BigDecimal.class, organizationId, location, location, timestamp(from), timestamp(to)));

        // returns side: revenue always comes back, cost only if the goods did
        var returns = jdbc.queryForMap("""
                select count(distinct r.id)                                   as return_count,
                       coalesce(sum(rl.refund_amount), 0)                     as refund_amount,
                       coalesce(sum(rl.refund_amount - rl.tax_amount), 0)     as revenue_reversed,
                       coalesce(sum(case when rl.restock then rl.unit_cost * rl.quantity else 0 end), 0)
                                                                              as cogs_reversed
                from sale_return_line rl join sale_return r on r.id = rl.return_id
                where r.organization_id = ?
                  and (?::uuid is null or r.location_id = ?::uuid)
                  and r.returned_at >= ? and r.returned_at < ?
                """, organizationId, location, location, timestamp(from), timestamp(to));

        BigDecimal expenses = amount(jdbc.queryForObject("""
                select coalesce(sum(e.amount), 0) from expense e
                where e.organization_id = ? and e.voided_at is null
                  and (?::uuid is null or e.location_id = ?::uuid)
                  and e.paid_at >= ? and e.paid_at < ?
                """, BigDecimal.class, organizationId, location, location, timestamp(from), timestamp(to)));
        BigDecimal receivables = amount(jdbc.queryForObject("""
                select coalesce(sum(r.outstanding_amount), 0) from receivable r where r.organization_id = ?
                """, BigDecimal.class, organizationId));
        BigDecimal payables = amount(jdbc.queryForObject("""
                select coalesce(sum(p.outstanding_amount), 0) from payable p where p.organization_id = ?
                """, BigDecimal.class, organizationId));

        BigDecimal netRevenue = amount(sales.get("line_total")).subtract(amount(sales.get("tax_amount")));
        BigDecimal cogs = amount(sales.get("cogs"));
        BigDecimal revenueReversed = amount(returns.get("revenue_reversed"));
        BigDecimal cogsReversed = amount(returns.get("cogs_reversed"));
        BigDecimal grossProfit = netRevenue.subtract(cogs).subtract(revenueReversed.subtract(cogsReversed));
        return new Summary(range.from(), range.to(), count(sales.get("sales_count")), grossSales, netRevenue,
                amount(sales.get("tax_amount")), cogs, count(returns.get("return_count")),
                amount(returns.get("refund_amount")), revenueReversed, cogsReversed, grossProfit, expenses,
                receivables, payables);
    }

    /** One row per day that saw a sale or a return, oldest first: the dashboard's chart. */
    @Transactional(readOnly = true)
    public List<DayTotal> salesByDay(Range range, UUID locationId) {
        UUID organizationId = TenantContext.requireOrganizationId();
        UUID location = scoped(locationId);
        String zone = zone().getId();
        java.sql.Timestamp from = timestamp(startOf(range.from()));
        java.sql.Timestamp to = timestamp(startOf(range.to().plusDays(1)));
        return jdbc.query("""
                with sold as (
                    select (s.sold_at at time zone ?)::date            as day,
                           count(distinct s.id)                        as sales_count,
                           sum(sl.line_total - sl.tax_amount)          as net_revenue,
                           sum(sl.unit_cost * sl.quantity)             as cogs
                    from sale_line sl
                    join sale s on s.id = sl.sale_id
                    where s.organization_id = ?
                      and s.status in ('COMPLETED', 'PARTIALLY_REFUNDED', 'REFUNDED')
                      and (?::uuid is null or s.location_id = ?::uuid)
                      and s.sold_at >= ? and s.sold_at < ?
                    group by 1
                ), charged as (
                    -- the header total, which carries the rounding adjustment no line has
                    select (s.sold_at at time zone ?)::date            as day,
                           sum(s.total)                                as gross_sales
                    from sale s
                    where s.organization_id = ?
                      and s.status in ('COMPLETED', 'PARTIALLY_REFUNDED', 'REFUNDED')
                      and (?::uuid is null or s.location_id = ?::uuid)
                      and s.sold_at >= ? and s.sold_at < ?
                    group by 1
                ), returned as (
                    select (r.returned_at at time zone ?)::date        as day,
                           sum(rl.refund_amount - rl.tax_amount)       as revenue_reversed,
                           sum(case when rl.restock then rl.unit_cost * rl.quantity else 0 end) as cogs_reversed
                    from sale_return_line rl
                    join sale_return r on r.id = rl.return_id
                    where r.organization_id = ?
                      and (?::uuid is null or r.location_id = ?::uuid)
                      and r.returned_at >= ? and r.returned_at < ?
                    group by 1
                )
                select d.day,
                       coalesce(sold.sales_count, 0)                   as sales_count,
                       coalesce(charged.gross_sales, 0)                as gross_sales,
                       coalesce(sold.net_revenue, 0)                   as net_revenue,
                       coalesce(sold.net_revenue, 0) - coalesce(sold.cogs, 0)
                           - (coalesce(returned.revenue_reversed, 0) - coalesce(returned.cogs_reversed, 0))
                                                                       as gross_profit
                from (select day from sold union select day from returned) d
                left join sold on sold.day = d.day
                left join charged on charged.day = d.day
                left join returned on returned.day = d.day
                order by d.day
                """, (rs, row) -> new DayTotal(rs.getObject("day", LocalDate.class), rs.getLong("sales_count"),
                        rs.getBigDecimal("gross_sales"), rs.getBigDecimal("net_revenue"),
                        rs.getBigDecimal("gross_profit")),
                zone, organizationId, location, location, from, to,
                zone, organizationId, location, location, from, to,
                zone, organizationId, location, location, from, to);
    }

    @Transactional(readOnly = true)
    public List<ProductTotal> topProducts(Range range, UUID locationId, int limit) {
        UUID organizationId = TenantContext.requireOrganizationId();
        UUID location = scoped(locationId);
        return jdbc.query("""
                select sl.product_id,
                       max(sl.product_name)                                   as product_name,
                       max(sl.sku)                                            as sku,
                       sum(sl.quantity)                                       as quantity,
                       sum(sl.line_total - sl.tax_amount)                     as net_revenue,
                       sum((sl.line_total - sl.tax_amount) - sl.unit_cost * sl.quantity) as gross_profit
                from sale_line sl join sale s on s.id = sl.sale_id
                where s.organization_id = ?
                  and s.status in ('COMPLETED', 'PARTIALLY_REFUNDED', 'REFUNDED')
                  and (?::uuid is null or s.location_id = ?::uuid)
                  and s.sold_at >= ? and s.sold_at < ?
                group by sl.product_id
                order by net_revenue desc
                limit ?
                """, (rs, row) -> new ProductTotal(rs.getObject("product_id", UUID.class),
                        rs.getString("product_name"), rs.getString("sku"), rs.getBigDecimal("quantity"),
                        rs.getBigDecimal("net_revenue"), rs.getBigDecimal("gross_profit")),
                organizationId, location, location, timestamp(startOf(range.from())),
                timestamp(startOf(range.to().plusDays(1))), Math.clamp(limit, 1, 100));
    }

    /** At or below the reorder point — the location's own point when it has one (spec §4). */
    @Transactional(readOnly = true)
    public List<LowStockRow> lowStock(UUID locationId, int limit) {
        UUID organizationId = TenantContext.requireOrganizationId();
        UUID location = scoped(locationId);
        return jdbc.query("""
                select b.product_id, p.name as product_name, p.sku, b.location_id, l.code as location_code,
                       b.quantity,
                       coalesce(lp.reorder_point, p.reorder_point) as reorder_point
                from stock_balance b
                join product p on p.id = b.product_id
                join location l on l.id = b.location_id
                left join location_product lp on lp.product_id = b.product_id and lp.location_id = b.location_id
                where b.organization_id = ?
                  and (?::uuid is null or b.location_id = ?::uuid)
                  and p.archived_at is null and p.active and p.track_inventory
                  and b.quantity <= coalesce(lp.reorder_point, p.reorder_point)
                order by b.quantity - coalesce(lp.reorder_point, p.reorder_point), p.name
                limit ?
                """, (rs, row) -> new LowStockRow(rs.getObject("product_id", UUID.class),
                        rs.getString("product_name"), rs.getString("sku"), rs.getObject("location_id", UUID.class),
                        rs.getString("location_code"), rs.getBigDecimal("quantity"), rs.getInt("reorder_point")),
                organizationId, location, location, Math.clamp(limit, 1, 200));
    }

    /** How the money came in, for reconciling wallets against their statements. */
    @Transactional(readOnly = true)
    public List<PaymentMixRow> paymentMix(Range range, UUID locationId) {
        UUID organizationId = TenantContext.requireOrganizationId();
        UUID location = scoped(locationId);
        return jdbc.query("""
                select pm.method, count(*) as payments, sum(pm.amount) as amount
                from payment pm join sale s on s.id = pm.sale_id
                where s.organization_id = ?
                  and s.status in ('COMPLETED', 'PARTIALLY_REFUNDED', 'REFUNDED')
                  and (?::uuid is null or s.location_id = ?::uuid)
                  and s.sold_at >= ? and s.sold_at < ?
                group by pm.method
                order by amount desc
                """, (rs, row) -> new PaymentMixRow(rs.getString("method"), rs.getLong("payments"),
                        rs.getBigDecimal("amount")),
                organizationId, location, location, timestamp(startOf(range.from())),
                timestamp(startOf(range.to().plusDays(1))));
    }

    // ───────────────────────────────────────────────────────────── helpers

    /** A session scoped to one location reports on that location, whatever was asked for. */
    private UUID scoped(UUID locationId) {
        UUID scope = TenantContext.current().map(TenantContext.Current::locationScope).orElse(null);
        if (scope != null) {
            if (locationId != null) {
                TenantContext.requireLocationInScope(locationId);
            }
            return scope;
        }
        return locationId;
    }

    private ZoneId zone() {
        return ZoneId.of(organizations.findById(TenantContext.requireOrganizationId()).orElseThrow().getTimezone());
    }

    private Instant startOf(LocalDate day) {
        return day.atStartOfDay(zone()).toInstant();
    }

    private static java.sql.Timestamp timestamp(Instant instant) {
        return java.sql.Timestamp.from(instant);
    }

    private static BigDecimal amount(Object value) {
        return value == null ? BigDecimal.ZERO : (BigDecimal) value;
    }

    private static long count(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }
}
