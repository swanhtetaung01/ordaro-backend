package app.trillopos.support;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import app.trillopos.auth.AuthService;
import app.trillopos.auth.AuthService.SignupCommand;
import app.trillopos.auth.MembershipDirectory;
import app.trillopos.auth.MembershipDirectory.PickerEntry;
import app.trillopos.catalog.ProductService;
import app.trillopos.catalog.ProductService.ProductCommand;
import app.trillopos.catalog.ProductUnit;
import app.trillopos.inventory.StockDocumentService.OpeningStock;
import app.trillopos.org.AccountRepository;
import app.trillopos.org.Location;
import app.trillopos.org.LocationRepository;
import app.trillopos.org.LocationType;
import app.trillopos.org.OrganizationRepository;
import app.trillopos.shared.tenant.TenantContext;

/** Service-level fixtures: a signed-up shop, and running code as its owner in a transaction. */
@Component
public class Shops {

    /** A signed-up organization with its owner and default STORE "MAIN". */
    public record Shop(UUID organizationId, UUID membershipId, UUID accountId, UUID mainLocationId) {
    }

    private final AuthService auth;
    private final AccountRepository accounts;
    private final MembershipDirectory directory;
    private final LocationRepository locations;
    private final OrganizationRepository organizations;
    private final ProductService products;
    private final TransactionTemplate transaction;

    public Shops(AuthService auth, AccountRepository accounts, MembershipDirectory directory,
            LocationRepository locations, OrganizationRepository organizations, ProductService products,
            PlatformTransactionManager transactionManager) {
        this.auth = auth;
        this.accounts = accounts;
        this.directory = directory;
        this.locations = locations;
        this.organizations = organizations;
        this.products = products;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public Shop newShop() {
        String phone = "+9598" + ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999);
        auth.signup(new SignupCommand(phone, "correct horse battery", "Daw Test", "Test Shop", "test"));
        UUID accountId = accounts.findByPhone(phone).orElseThrow().getId();
        PickerEntry owner = directory.forAccount(accountId).getFirst();
        Shop provisional = new Shop(owner.organizationId(), owner.membershipId(), accountId, null);
        UUID main = as(provisional, () -> locations.findAllByOrderByCodeAsc().getFirst().getId());
        return new Shop(owner.organizationId(), owner.membershipId(), accountId, main);
    }

    /** Runs {@code work} as the shop's owner, in its own transaction. */
    public <T> T as(Shop shop, Supplier<T> work) {
        return TenantContext.call(new TenantContext.Current(shop.organizationId(), shop.membershipId(),
                shop.accountId()), () -> transaction.execute(status -> work.get()));
    }

    /**
     * Sets the tenant but opens no transaction — as a controller does. Needed for code that
     * manages its own transactions, such as idempotent checkout reading the original after a
     * duplicate-key failure.
     */
    public <T> T inTenant(Shop shop, Supplier<T> work) {
        return TenantContext.call(new TenantContext.Current(shop.organizationId(), shop.membershipId(),
                shop.accountId()), work);
    }

    /** As {@link #as}, but for a session limited to one location (a scoped member, or a register). */
    public <T> T asScoped(Shop shop, UUID locationScope, Supplier<T> work) {
        return TenantContext.call(new TenantContext.Current(shop.organizationId(), shop.membershipId(),
                shop.accountId(), locationScope), () -> transaction.execute(status -> work.get()));
    }

    public void run(Shop shop, Runnable work) {
        as(shop, () -> {
            work.run();
            return null;
        });
    }

    public UUID location(Shop shop, String code, LocationType type) {
        return as(shop, () -> locations.save(new Location(code, code + " location", type)).getId());
    }

    public UUID product(Shop shop, String name) {
        return product(shop, name, null);
    }

    public UUID product(Shop shop, String name, List<OpeningStock> opening) {
        return as(shop, () -> products.create(new ProductCommand(null, null, name, null, null, ProductUnit.PIECE,
                null, null, new BigDecimal("1000"), null, null, null, null, null, null, null, null, opening)).getId());
    }

    public void allowNegativeStock(Shop shop, boolean allowed) {
        run(shop, () -> organizations.findById(shop.organizationId()).orElseThrow().setAllowNegativeStock(allowed));
    }
}
