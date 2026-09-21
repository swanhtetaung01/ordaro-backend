package app.ordaro.catalog;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import app.ordaro.org.Phones;
import app.ordaro.shared.web.ApiException;

@RestController
@RequestMapping("/suppliers")
class SupplierController {

    record SupplierView(UUID id, String name, String phone, String address, int paymentTermsDays) {

        static SupplierView of(Supplier s) {
            return new SupplierView(s.getId(), s.getName(), s.getPhone(), s.getAddress(), s.getPaymentTermsDays());
        }
    }

    record SupplierWrite(@Size(min = 1, max = 200) String name, String phone, @Size(max = 500) String address,
            @Min(0) @Max(365) Integer paymentTermsDays) {
    }

    private final SupplierRepository suppliers;

    SupplierController(SupplierRepository suppliers) {
        this.suppliers = suppliers;
    }

    @GetMapping
    List<SupplierView> list() {
        return suppliers.findAllByArchivedAtIsNullOrderByNameAsc().stream().map(SupplierView::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OWNER', 'STOCK_MANAGER')")
    @Transactional
    SupplierView create(@Valid @RequestBody SupplierWrite request) {
        if (request.name() == null || request.name().isBlank()) {
            throw ApiException.badRequest("validation_failed", "name: must not be blank");
        }
        Supplier supplier = new Supplier(request.name());
        apply(supplier, request);
        return SupplierView.of(suppliers.save(supplier));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('OWNER', 'STOCK_MANAGER')")
    @Transactional
    SupplierView update(@PathVariable UUID id, @Valid @RequestBody SupplierWrite request) {
        Supplier supplier = suppliers.findById(id)
                .orElseThrow(() -> ApiException.notFound("supplier_not_found", "no such supplier"));
        if (request.name() != null) {
            supplier.setName(request.name());
        }
        apply(supplier, request);
        return SupplierView.of(supplier);
    }

    private static void apply(Supplier supplier, SupplierWrite request) {
        if (request.phone() != null) {
            supplier.setPhone(Phones.normalize(request.phone()));
        }
        if (request.address() != null) {
            supplier.setAddress(request.address());
        }
        if (request.paymentTermsDays() != null) {
            supplier.setPaymentTermsDays(request.paymentTermsDays());
        }
    }
}
