package app.ordaro.catalog;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import app.ordaro.catalog.ProductService.ProductCommand;

@RestController
@RequestMapping("/products")
class ProductController {

    record ProductView(UUID id, String sku, String name, UUID categoryId, UUID defaultSupplierId, ProductUnit unit,
            String sizeLabel, String productGroupKey, BigDecimal retailPrice, BigDecimal wholesalePrice,
            boolean taxable, boolean trackInventory, int reorderPoint, boolean sellInPos, boolean sellOnline,
            boolean active, List<String> barcodes) {
    }

    /** Create and update share one shape; on update every field is optional. */
    record ProductWrite(UUID id, @Size(max = 64) String sku, @Size(max = 200) String name, UUID categoryId,
            UUID defaultSupplierId, ProductUnit unit, @Size(max = 32) String sizeLabel,
            @Size(max = 64) String productGroupKey, @DecimalMin("0") BigDecimal retailPrice,
            @DecimalMin("0") BigDecimal wholesalePrice, Boolean taxable, Boolean trackInventory,
            @Min(0) Integer reorderPoint, Boolean sellInPos, Boolean sellOnline, Boolean active,
            List<String> barcodes) {

        ProductCommand command() {
            return new ProductCommand(id, sku, name, categoryId, defaultSupplierId, unit, sizeLabel, productGroupKey,
                    retailPrice, wholesalePrice, taxable, trackInventory, reorderPoint, sellInPos, sellOnline, active,
                    barcodes);
        }
    }

    record BarcodeWrite(@NotBlank @Size(max = 64) String barcode) {
    }

    record LocationSettingsWrite(@Min(0) Integer reorderPoint, @Size(max = 32) String shelfLocation) {
    }

    record LocationSettingsView(UUID locationId, UUID productId, Integer reorderPoint, String shelfLocation) {
    }

    private final ProductService service;
    private final ProductRepository products;
    private final ProductBarcodeRepository barcodes;

    ProductController(ProductService service, ProductRepository products, ProductBarcodeRepository barcodes) {
        this.service = service;
        this.products = products;
        this.barcodes = barcodes;
    }

    @GetMapping
    List<ProductView> list() {
        return products.findAllByArchivedAtIsNullOrderByNameAsc().stream().map(this::view).toList();
    }

    @GetMapping("/{id}")
    ProductView get(@PathVariable UUID id) {
        return view(service.find(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OWNER', 'STOCK_MANAGER')")
    ProductView create(@Valid @RequestBody ProductWrite request) {
        return view(service.create(request.command()));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('OWNER', 'STOCK_MANAGER')")
    ProductView update(@PathVariable UUID id, @Valid @RequestBody ProductWrite request) {
        return view(service.update(id, request.command()));
    }

    /** Archive, never delete: a two-year-old receipt must still resolve the product. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('OWNER', 'STOCK_MANAGER')")
    void archive(@PathVariable UUID id) {
        service.archive(id);
    }

    @PostMapping("/{id}/barcodes")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OWNER', 'STOCK_MANAGER')")
    ProductView addBarcode(@PathVariable UUID id, @Valid @RequestBody BarcodeWrite request) {
        service.addBarcode(id, request.barcode());
        return view(service.find(id));
    }

    @PutMapping("/{id}/locations/{locationId}")
    @PreAuthorize("hasAnyRole('OWNER', 'STOCK_MANAGER')")
    LocationSettingsView setLocationSettings(@PathVariable UUID id, @PathVariable UUID locationId,
            @Valid @RequestBody LocationSettingsWrite request) {
        LocationProduct settings = service.setLocationSettings(id, locationId, request.reorderPoint(),
                request.shelfLocation());
        return new LocationSettingsView(settings.getLocationId(), settings.getProductId(),
                settings.getReorderPoint(), settings.getShelfLocation());
    }

    private ProductView view(Product p) {
        List<String> codes = barcodes.findAllByProductIdOrderByCreatedAtAsc(p.getId()).stream()
                .map(ProductBarcode::getBarcode).toList();
        return new ProductView(p.getId(), p.getSku(), p.getName(), p.getCategoryId(), p.getDefaultSupplierId(),
                p.getUnit(), p.getSizeLabel(), p.getProductGroupKey(), p.getRetailPrice(), p.getWholesalePrice(),
                p.isTaxable(), p.isTrackInventory(), p.getReorderPoint(), p.isSellInPos(), p.isSellOnline(),
                p.isActive(), codes);
    }
}
