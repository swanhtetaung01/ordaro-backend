package app.ordaro.catalog;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.ordaro.org.LocationRepository;
import app.ordaro.shared.web.ApiException;

@Service
public class ProductService {

    public record ProductCommand(UUID id, String sku, String name, UUID categoryId, UUID defaultSupplierId,
            ProductUnit unit, String sizeLabel, String productGroupKey, BigDecimal retailPrice,
            BigDecimal wholesalePrice, Boolean taxable, Boolean trackInventory, Integer reorderPoint,
            Boolean sellInPos, Boolean sellOnline, Boolean active, List<String> barcodes) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String SKU_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";

    private final ProductRepository products;
    private final ProductBarcodeRepository barcodes;
    private final CategoryRepository categories;
    private final SupplierRepository suppliers;
    private final LocationRepository locations;
    private final LocationProductRepository locationProducts;
    private final Clock clock;

    public ProductService(ProductRepository products, ProductBarcodeRepository barcodes,
            CategoryRepository categories, SupplierRepository suppliers, LocationRepository locations,
            LocationProductRepository locationProducts, Clock clock) {
        this.products = products;
        this.barcodes = barcodes;
        this.categories = categories;
        this.suppliers = suppliers;
        this.locations = locations;
        this.locationProducts = locationProducts;
        this.clock = clock;
    }

    @Transactional
    public Product create(ProductCommand command) {
        if (command.name() == null || command.name().isBlank() || command.unit() == null
                || command.retailPrice() == null) {
            throw ApiException.badRequest("validation_failed", "name, unit and retailPrice are required");
        }
        if (command.id() != null && products.existsById(command.id())) {
            throw ApiException.conflict("product_exists", "a product with that id already exists");
        }
        Category category = category(command.categoryId());
        String sku = command.sku() != null && !command.sku().isBlank()
                ? command.sku().trim()
                : generateSku(category);
        if (products.existsBySku(sku)) {
            throw ApiException.conflict("sku_taken", "SKU " + sku + " is already used (archived SKUs stay reserved)");
        }
        Product product = new Product(sku, command.name(), command.unit(), command.retailPrice());
        if (command.id() != null) {
            product.withId(command.id());
        }
        apply(product, command);
        product = products.save(product);
        if (command.barcodes() != null) {
            for (String code : command.barcodes()) {
                addBarcode(product.getId(), code);
            }
        }
        return product;
    }

    @Transactional
    public Product update(UUID id, ProductCommand command) {
        Product product = find(id);
        if (command.sku() != null && !command.sku().isBlank() && !command.sku().trim().equals(product.getSku())) {
            if (products.existsBySku(command.sku().trim())) {
                throw ApiException.conflict("sku_taken", "SKU " + command.sku() + " is already used");
            }
            product.setSku(command.sku().trim());
        }
        if (command.name() != null && !command.name().isBlank()) {
            product.setName(command.name());
        }
        if (command.unit() != null) {
            product.setUnit(command.unit());
        }
        if (command.retailPrice() != null) {
            product.setRetailPrice(command.retailPrice());
        }
        apply(product, command);
        return product;
    }

    @Transactional
    public void archive(UUID id) {
        find(id).archive(clock.instant());
    }

    @Transactional
    public ProductBarcode addBarcode(UUID productId, String code) {
        find(productId);
        String barcode = code == null ? "" : code.trim();
        if (barcode.isEmpty() || barcode.length() > 64) {
            throw ApiException.badRequest("invalid_barcode", "a barcode is 1–64 characters");
        }
        if (barcodes.findByBarcode(barcode).isPresent()) {
            throw ApiException.conflict("barcode_taken", "barcode " + barcode + " is already assigned");
        }
        return barcodes.save(new ProductBarcode(productId, barcode));
    }

    @Transactional
    public LocationProduct setLocationSettings(UUID productId, UUID locationId, Integer reorderPoint,
            String shelfLocation) {
        find(productId);
        if (locations.findById(locationId).isEmpty()) {
            throw ApiException.notFound("location_not_found", "no such location");
        }
        LocationProduct settings = locationProducts.findByLocationIdAndProductId(locationId, productId)
                .orElseGet(() -> locationProducts.save(new LocationProduct(locationId, productId)));
        settings.setReorderPoint(reorderPoint);
        settings.setShelfLocation(shelfLocation);
        return settings;
    }

    public Product find(UUID id) {
        return products.findById(id)
                .orElseThrow(() -> ApiException.notFound("product_not_found", "no such product"));
    }

    private void apply(Product product, ProductCommand command) {
        if (command.categoryId() != null) {
            product.setCategoryId(category(command.categoryId()).getId());
        }
        if (command.defaultSupplierId() != null) {
            suppliers.findById(command.defaultSupplierId())
                    .orElseThrow(() -> ApiException.badRequest("supplier_not_found", "no such supplier"));
            product.setDefaultSupplierId(command.defaultSupplierId());
        }
        if (command.sizeLabel() != null) {
            product.setSizeLabel(command.sizeLabel());
        }
        if (command.productGroupKey() != null) {
            product.setProductGroupKey(command.productGroupKey());
        }
        if (command.wholesalePrice() != null) {
            product.setWholesalePrice(command.wholesalePrice());
        }
        if (command.taxable() != null) {
            product.setTaxable(command.taxable());
        }
        if (command.trackInventory() != null) {
            product.setTrackInventory(command.trackInventory());
        }
        if (command.reorderPoint() != null) {
            product.setReorderPoint(command.reorderPoint());
        }
        if (command.sellInPos() != null) {
            product.setSellInPos(command.sellInPos());
        }
        if (command.sellOnline() != null) {
            product.setSellOnline(command.sellOnline());
        }
        if (command.active() != null) {
            product.setActive(command.active());
        }
    }

    private Category category(UUID categoryId) {
        if (categoryId == null) {
            return null;
        }
        return categories.findById(categoryId)
                .orElseThrow(() -> ApiException.badRequest("category_not_found", "no such category"));
    }

    /** "Auto-generated from the category, editable" (spec §4): a category prefix plus random characters. */
    private String generateSku(Category category) {
        String prefix = category == null ? "SKU"
                : category.getName().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        if (prefix.isEmpty()) {
            prefix = "SKU";
        }
        prefix = prefix.substring(0, Math.min(3, prefix.length()));
        for (int attempt = 0; attempt < 5; attempt++) {
            StringBuilder sku = new StringBuilder(prefix).append('-');
            for (int i = 0; i < 6; i++) {
                sku.append(SKU_ALPHABET.charAt(RANDOM.nextInt(SKU_ALPHABET.length())));
            }
            if (!products.existsBySku(sku.toString())) {
                return sku.toString();
            }
        }
        throw ApiException.conflict("sku_unavailable", "could not generate a unique SKU; supply one");
    }
}
