package app.ordaro.finance;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.ordaro.org.Location;
import app.ordaro.org.LocationRepository;
import app.ordaro.org.Organization;
import app.ordaro.org.OrganizationRepository;
import app.ordaro.sales.ShiftService;
import app.ordaro.shared.money.Money;
import app.ordaro.shared.tenant.TenantContext;
import app.ordaro.shared.web.ApiException;

/** Expenses (spec §7). One paid from a drawer names its open shift; voiding gives the cash back to the count. */
@Service
public class ExpenseService {

    public record ExpenseCommand(UUID categoryId, UUID locationId, UUID cashierShiftId, BigDecimal amount,
            ExpenseMethod method, Instant paidAt, String description, String referenceNo) {
    }

    private final ExpenseRepository expenses;
    private final ExpenseCategoryRepository categories;
    private final LocationRepository locations;
    private final OrganizationRepository organizations;
    private final ShiftService shifts;
    private final Clock clock;

    public ExpenseService(ExpenseRepository expenses, ExpenseCategoryRepository categories,
            LocationRepository locations, OrganizationRepository organizations, ShiftService shifts, Clock clock) {
        this.expenses = expenses;
        this.categories = categories;
        this.locations = locations;
        this.organizations = organizations;
        this.shifts = shifts;
        this.clock = clock;
    }

    @Transactional
    public Expense record(ExpenseCommand command) {
        Organization organization = organization();
        BigDecimal amount = Money.requirePositive(command.amount(), Money.minorDigits(organization.getCurrencyCode()),
                "amount");
        if (command.method() == null) {
            throw ApiException.badRequest("method_required", "how was it paid?");
        }
        ExpenseCategory category = command.categoryId() == null ? null
                : categories.findById(command.categoryId()).orElse(null);
        if (category == null || category.isArchived()) {
            throw ApiException.badRequest("expense_category_not_found", "no such expense category");
        }
        requireLocation(command.locationId());
        if (command.cashierShiftId() != null) {
            if (command.method() != ExpenseMethod.CASH) {
                throw ApiException.badRequest("unexpected_shift", "only a cash expense comes out of a drawer");
            }
            shifts.requireOpenForCash(command.cashierShiftId(), command.locationId());
        }
        return expenses.save(new Expense(category.getId(), command.locationId(), command.cashierShiftId(), amount,
                command.method(), command.paidAt() != null ? command.paidAt() : clock.instant(),
                command.description(), command.referenceNo()));
    }

    /** A drawer expense can only be voided while its shift is open: the count is final once closed. */
    @Transactional
    public Expense voidExpense(UUID expenseId) {
        Expense expense = expenses.lockById(expenseId).orElseThrow(ExpenseService::notFound);
        TenantContext.requireLocationInScope(expense.getLocationId());
        if (expense.isVoid()) {
            throw ApiException.conflict("expense_void", "the expense is already void");
        }
        if (expense.getCashierShiftId() != null) {
            shifts.requireOpenForCash(expense.getCashierShiftId(), expense.getLocationId());
        }
        expense.voidExpense(clock.instant());
        return expense;
    }

    public Expense find(UUID expenseId) {
        Expense expense = expenses.findById(expenseId).orElseThrow(ExpenseService::notFound);
        TenantContext.requireLocationInScope(expense.getLocationId());
        return expense;
    }

    public List<Expense> between(UUID locationId, Instant from, Instant to, int limit) {
        PageRequest page = PageRequest.of(0, Math.clamp(limit, 1, 500));
        return locationId == null ? expenses.between(from, to, page) : expenses.betweenAt(locationId, from, to, page);
    }

    private Location requireLocation(UUID locationId) {
        if (locationId == null) {
            throw ApiException.badRequest("validation_failed", "locationId is required");
        }
        TenantContext.requireLocationInScope(locationId);
        Location location = locations.findById(locationId)
                .orElseThrow(() -> ApiException.badRequest("location_not_found", "no such location"));
        if (!location.isActive()) {
            throw ApiException.badRequest("location_inactive", location.getCode() + " is closed");
        }
        return location;
    }

    private Organization organization() {
        return organizations.findById(TenantContext.requireOrganizationId()).orElseThrow();
    }

    private static ApiException notFound() {
        return ApiException.notFound("expense_not_found", "no such expense");
    }
}
