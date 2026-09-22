package app.ordaro.finance;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import app.ordaro.finance.ExpenseService.ExpenseCommand;
import app.ordaro.org.OrganizationRepository;
import app.ordaro.shared.tenant.TenantContext;
import app.ordaro.shared.web.ApiException;

/** Expenses and their categories (spec §7). A cashier records a drawer expense; owners manage categories. */
@RestController
@RequestMapping("/expenses")
@PreAuthorize("hasAnyRole('OWNER', 'STOCK_MANAGER', 'CASHIER')")
class ExpenseController {

    @Schema(name = "ExpenseCategoryView")
    record CategoryView(UUID id, String name) {

        static CategoryView of(ExpenseCategory c) {
            return new CategoryView(c.getId(), c.getName());
        }
    }

    @Schema(name = "ExpenseCategoryWrite")
    record CategoryWrite(@NotBlank @Size(max = 120) String name) {
    }

    record ExpenseView(UUID id, UUID categoryId, String categoryName, UUID locationId, UUID cashierShiftId,
            BigDecimal amount, ExpenseMethod method, Instant paidAt, String description, String referenceNo,
            Instant voidedAt) {

        static ExpenseView of(Expense e, String categoryName) {
            return new ExpenseView(e.getId(), e.getCategoryId(), categoryName, e.getLocationId(),
                    e.getCashierShiftId(), e.getAmount(), e.getMethod(), e.getPaidAt(), e.getDescription(),
                    e.getReferenceNo(), e.getVoidedAt());
        }
    }

    record ExpenseWrite(@NotNull UUID categoryId, @NotNull UUID locationId, UUID cashierShiftId,
            @NotNull BigDecimal amount, @NotNull ExpenseMethod method, Instant paidAt,
            @Size(max = 500) String description, @Size(max = 100) String referenceNo) {
    }

    private final ExpenseService service;
    private final ExpenseCategoryRepository categories;
    private final OrganizationRepository organizations;
    private final Clock clock;

    ExpenseController(ExpenseService service, ExpenseCategoryRepository categories,
            OrganizationRepository organizations, Clock clock) {
        this.service = service;
        this.categories = categories;
        this.organizations = organizations;
        this.clock = clock;
    }

    // ───────────────────────────────────────────────────────────── categories

    @GetMapping("/categories")
    List<CategoryView> listCategories() {
        return categories.findAllByArchivedAtIsNullOrderByNameAsc().stream().map(CategoryView::of).toList();
    }

    @PostMapping("/categories")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OWNER', 'STOCK_MANAGER')")
    @Transactional
    CategoryView createCategory(@Valid @RequestBody CategoryWrite request) {
        String name = request.name().trim();
        categories.findByNameIgnoreCase(name).ifPresent(c -> {
            throw ApiException.conflict("category_exists", "there is already a category named " + c.getName());
        });
        return CategoryView.of(categories.save(new ExpenseCategory(name)));
    }

    @PostMapping("/categories/{id}/archive")
    @PreAuthorize("hasRole('OWNER')")
    @Transactional
    CategoryView archiveCategory(@PathVariable UUID id) {
        ExpenseCategory category = categories.findById(id)
                .orElseThrow(() -> ApiException.notFound("expense_category_not_found", "no such expense category"));
        category.archive(clock.instant());
        return CategoryView.of(category);
    }

    // ───────────────────────────────────────────────────────────── expenses

    /** {@code from}/{@code to} are dates in the organization's timezone; default: this month so far. */
    @GetMapping
    List<ExpenseView> list(@RequestParam(required = false) UUID locationId,
            @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
            @RequestParam(defaultValue = "200") int limit) {
        ZoneId zone = zone();
        LocalDate today = LocalDate.now(clock.withZone(zone));
        LocalDate start = from != null ? from : today.withDayOfMonth(1);
        LocalDate end = to != null ? to : today;
        List<Expense> found = service.between(locationId, start.atStartOfDay(zone).toInstant(),
                end.plusDays(1).atStartOfDay(zone).toInstant(), limit);
        Map<UUID, String> names = categories.findAllById(found.stream().map(Expense::getCategoryId).distinct()
                .toList()).stream().collect(Collectors.toMap(ExpenseCategory::getId, ExpenseCategory::getName));
        return found.stream().map(e -> ExpenseView.of(e, names.get(e.getCategoryId()))).toList();
    }

    @GetMapping("/{id}")
    ExpenseView get(@PathVariable UUID id) {
        return view(service.find(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ExpenseView record(@Valid @RequestBody ExpenseWrite request) {
        return view(service.record(new ExpenseCommand(request.categoryId(), request.locationId(),
                request.cashierShiftId(), request.amount(), request.method(), request.paidAt(),
                request.description(), request.referenceNo())));
    }

    @PostMapping("/{id}/void")
    @PreAuthorize("hasAnyRole('OWNER', 'STOCK_MANAGER')")
    ExpenseView voidExpense(@PathVariable UUID id) {
        return view(service.voidExpense(id));
    }

    private ExpenseView view(Expense expense) {
        return ExpenseView.of(expense, categories.findById(expense.getCategoryId()).map(ExpenseCategory::getName)
                .orElse(null));
    }

    private ZoneId zone() {
        return ZoneId.of(organizations.findById(TenantContext.requireOrganizationId()).orElseThrow().getTimezone());
    }
}
