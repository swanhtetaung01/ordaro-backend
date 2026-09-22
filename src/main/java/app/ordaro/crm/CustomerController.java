package app.ordaro.crm;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import app.ordaro.finance.ReceivableRepository;
import app.ordaro.finance.Owed;
import app.ordaro.org.Phones;
import app.ordaro.sales.PriceType;
import app.ordaro.shared.web.ApiException;

/**
 * Customers (spec §8). Anyone who sells may register one at the till; only an owner sets credit
 * terms, because a credit limit is money the shop lends.
 */
@RestController
@RequestMapping("/customers")
@PreAuthorize("hasAnyRole('OWNER', 'STOCK_MANAGER', 'CASHIER')")
class CustomerController {

    /** {@code outstanding}: Σ open receivables; {@code availableCredit}: limit − outstanding, never below 0. */
    record CustomerView(UUID id, String name, String phone, CustomerType type, PriceType defaultPriceType,
            BigDecimal creditLimit, int creditTermDays, BigDecimal outstanding, BigDecimal availableCredit,
            int loyaltyPoints, String address, String note, boolean archived) {

        static CustomerView of(Customer c, BigDecimal outstanding) {
            return new CustomerView(c.getId(), c.getName(), c.getPhone(), c.getType(), c.getDefaultPriceType(),
                    c.getCreditLimit(), c.getCreditTermDays(), outstanding,
                    c.getCreditLimit().subtract(outstanding).max(BigDecimal.ZERO), c.getLoyaltyPoints(),
                    c.getAddress(), c.getNote(), c.isArchived());
        }
    }

    /** Create and update share one shape; on update every field is optional. */
    record CustomerWrite(@Size(min = 1, max = 200) String name, String phone, CustomerType type,
            PriceType defaultPriceType, @DecimalMin("0") BigDecimal creditLimit, @Min(0) @Max(365) Integer creditTermDays,
            @Size(max = 500) String address, @Size(max = 500) String note) {
    }

    private final CustomerRepository customers;
    private final ReceivableRepository receivables;
    private final Clock clock;

    CustomerController(CustomerRepository customers, ReceivableRepository receivables, Clock clock) {
        this.customers = customers;
        this.receivables = receivables;
        this.clock = clock;
    }

    /** Name or phone search for the register's customer selector. */
    @GetMapping
    @Transactional(readOnly = true)
    List<CustomerView> list(@RequestParam(required = false) String q,
            @RequestParam(defaultValue = "50") int limit) {
        List<Customer> found = customers.search(q == null ? "" : q.trim(), PageRequest.of(0, Math.clamp(limit, 1, 200)));
        Map<UUID, BigDecimal> owed = found.isEmpty() ? Map.of()
                : receivables.outstandingByCustomer(found.stream().map(Customer::getId).toList()).stream()
                        .collect(Collectors.toMap(Owed::customerId, Owed::outstanding));
        return found.stream().map(c -> CustomerView.of(c, owed.getOrDefault(c.getId(), BigDecimal.ZERO))).toList();
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    CustomerView get(@PathVariable UUID id) {
        Customer customer = find(id);
        return CustomerView.of(customer, receivables.outstandingFor(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    CustomerView create(@Valid @RequestBody CustomerWrite request, Authentication auth) {
        if (request.name() == null || request.name().isBlank()) {
            throw ApiException.badRequest("validation_failed", "name: must not be blank");
        }
        Customer customer = new Customer(request.name().trim(),
                request.type() != null ? request.type() : CustomerType.MEMBER,
                request.defaultPriceType() != null ? request.defaultPriceType() : PriceType.RETAIL);
        apply(customer, request, auth);
        return CustomerView.of(customers.save(customer), BigDecimal.ZERO);
    }

    @PatchMapping("/{id}")
    @Transactional
    CustomerView update(@PathVariable UUID id, @Valid @RequestBody CustomerWrite request, Authentication auth) {
        Customer customer = find(id);
        if (request.name() != null) {
            customer.setName(request.name().trim());
        }
        if (request.type() != null) {
            customer.setType(request.type());
        }
        if (request.defaultPriceType() != null) {
            customer.setDefaultPriceType(request.defaultPriceType());
        }
        apply(customer, request, auth);
        return CustomerView.of(customer, receivables.outstandingFor(id));
    }

    /** Archived customers leave the selector; their history and debts stay. */
    @PostMapping("/{id}/archive")
    @PreAuthorize("hasAnyRole('OWNER', 'STOCK_MANAGER')")
    @Transactional
    CustomerView archive(@PathVariable UUID id) {
        Customer customer = find(id);
        customer.archive(clock.instant());
        return CustomerView.of(customer, receivables.outstandingFor(id));
    }

    private void apply(Customer customer, CustomerWrite request, Authentication auth) {
        if (request.phone() != null) {
            String phone = request.phone().isBlank() ? null : Phones.normalize(request.phone());
            if (phone != null && !phone.equals(customer.getPhone())) {
                customers.findByPhone(phone).ifPresent(other -> {
                    throw ApiException.conflict("phone_in_use", "another customer has " + phone);
                });
            }
            customer.setPhone(phone);
        }
        if (request.creditLimit() != null || request.creditTermDays() != null) {
            if (!isOwner(auth)) {
                throw ApiException.forbidden("owner_only", "only an owner sets credit terms");
            }
            if (request.creditLimit() != null) {
                customer.setCreditLimit(request.creditLimit());
            }
            if (request.creditTermDays() != null) {
                customer.setCreditTermDays(request.creditTermDays());
            }
        }
        if (request.address() != null) {
            customer.setAddress(request.address().isBlank() ? null : request.address());
        }
        if (request.note() != null) {
            customer.setNote(request.note().isBlank() ? null : request.note());
        }
    }

    private Customer find(UUID id) {
        return customers.findById(id)
                .orElseThrow(() -> ApiException.notFound("customer_not_found", "no such customer"));
    }

    private static boolean isOwner(Authentication auth) {
        return auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).anyMatch("ROLE_OWNER"::equals);
    }
}
