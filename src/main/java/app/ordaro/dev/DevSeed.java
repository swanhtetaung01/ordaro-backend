package app.ordaro.dev;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import app.ordaro.auth.AuthService;
import app.ordaro.auth.AuthService.SignupCommand;
import app.ordaro.auth.MembershipDirectory;
import app.ordaro.auth.MembershipDirectory.PickerEntry;
import app.ordaro.catalog.Category;
import app.ordaro.catalog.CategoryRepository;
import app.ordaro.catalog.ProductService;
import app.ordaro.catalog.ProductService.ProductCommand;
import app.ordaro.catalog.ProductUnit;
import app.ordaro.inventory.StockDocumentService.OpeningStock;
import app.ordaro.org.Account;
import app.ordaro.org.AccountRepository;
import app.ordaro.org.Location;
import app.ordaro.org.LocationRepository;
import app.ordaro.org.LocationType;
import app.ordaro.shared.tenant.TenantContext;

/**
 * {@code --spring.profiles.active=seed}: a demo shop to work against (spec §11 step 1).
 * Idempotent — does nothing once the demo account exists. Development only.
 */
@Component
@Profile("seed")
class DevSeed implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevSeed.class);

    private final AuthService auth;
    private final AccountRepository accounts;
    private final MembershipDirectory directory;
    private final LocationRepository locations;
    private final CategoryRepository categories;
    private final ProductService products;
    private final TransactionTemplate transaction;
    private final String phone;
    private final String password;

    DevSeed(AuthService auth, AccountRepository accounts, MembershipDirectory directory,
            LocationRepository locations, CategoryRepository categories, ProductService products,
            PlatformTransactionManager transactionManager,
            @Value("${ordaro.seed.phone:+959000000001}") String phone,
            @Value("${ordaro.seed.password:demo-password}") String password) {
        this.auth = auth;
        this.accounts = accounts;
        this.directory = directory;
        this.locations = locations;
        this.categories = categories;
        this.products = products;
        this.transaction = new TransactionTemplate(transactionManager);
        this.phone = phone;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (accounts.existsByPhone(phone)) {
            log.info("seed: demo account {} already exists", phone);
            return;
        }
        auth.signup(new SignupCommand(phone, password, "Daw Demo", "Shwe Demo Shop", "seed"));
        Account account = accounts.findByPhone(phone).orElseThrow();
        PickerEntry owner = directory.forAccount(account.getId()).getFirst();

        TenantContext.run(new TenantContext.Current(owner.organizationId(), owner.membershipId(), account.getId()),
                () -> transaction.executeWithoutResult(status -> {
                    locations.save(new Location("WH", "Yangon Warehouse", LocationType.WAREHOUSE));
                    UUID main = locations.findAllByOrderByCodeAsc().stream()
                            .filter(l -> l.getCode().equals("MAIN")).findFirst().orElseThrow().getId();
                    Category oils = categories.save(new Category("Cooking Oil", null));
                    Category rice = categories.save(new Category("Rice", null));
                    product("Cooking Oil 1L", oils, ProductUnit.PIECE, "1L", "cooking-oil", "4500", "8834000000011",
                            main, "24", "3600");
                    product("Cooking Oil 2L", oils, ProductUnit.PIECE, "2L", "cooking-oil", "8600", "8834000000028",
                            main, "12", "7000");
                    product("Paw San Rice 5kg", rice, ProductUnit.BAG, "5kg", null, "21000", "8834000000035",
                            main, "10", "17500");
                }));
        log.info("seed: created demo shop for {}", phone);
    }

    private void product(String name, Category category, ProductUnit unit, String size, String group, String price,
            String barcode, UUID location, String openingQuantity, String openingCost) {
        products.create(new ProductCommand(null, null, name, category.getId(), null, unit, size, group,
                new BigDecimal(price), null, null, null, 5, null, null, null, List.of(barcode),
                List.of(new OpeningStock(location, new BigDecimal(openingQuantity), new BigDecimal(openingCost)))));
    }
}
