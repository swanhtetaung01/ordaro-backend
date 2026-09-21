package app.ordaro.shared.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import app.ordaro.catalog.Category;
import app.ordaro.catalog.CategoryRepository;
import app.ordaro.catalog.Product;
import app.ordaro.catalog.ProductRepository;
import app.ordaro.catalog.ProductUnit;
import app.ordaro.org.Location;
import app.ordaro.org.LocationRepository;
import app.ordaro.org.LocationType;
import app.ordaro.org.Organization;
import app.ordaro.org.OrganizationRepository;
import app.ordaro.org.Slugs;
import app.ordaro.shared.tenant.TenantContext;
import app.ordaro.support.IntegrationTest;

/** Spec §1 Tenant enforcement and the step-1 tests in §11. */
class TenantIsolationTest extends IntegrationTest {

    @Autowired
    OrganizationRepository organizations;

    @Autowired
    ProductRepository products;

    @Autowired
    CategoryRepository categories;

    @Autowired
    LocationRepository locations;

    @Autowired
    PlatformTransactionManager transactionManager;

    UUID orgA;
    UUID orgB;

    @BeforeEach
    void twoOrganizations() {
        orgA = newOrganization("Alpha Mart");
        orgB = newOrganization("Beta Store");
    }

    @Test
    void findByIdOnAnotherTenantsRowReturnsNothing() {
        UUID bProduct = as(orgB, () -> products.save(product("B-1")).getId());

        assertThat(as(orgA, () -> products.findById(bProduct))).isEmpty();
        assertThat(as(orgA, () -> products.findAll())).extracting(Product::getId).doesNotContain(bProduct);
        assertThat(as(orgB, () -> products.findById(bProduct))).isPresent();
    }

    @Test
    void withoutATenantNothingIsVisible() {
        UUID aProduct = as(orgA, () -> products.save(product("A-1")).getId());

        assertThat(inTransaction(() -> products.findById(aProduct))).isEmpty();
        assertThat(inTransaction(() -> products.findAll())).isEmpty();
    }

    @Test
    void withoutATenantNothingCanBeWritten() {
        assertThatThrownBy(() -> inTransaction(() -> locations.save(new Location("X", "X", LocationType.STORE))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aPreAssignedIdIsKept() {
        UUID clientId = UuidV7Generator.newId();
        Product saved = as(orgA, () -> products.save(product("A-OFFLINE").withId(clientId)));

        assertThat(saved.getId()).isEqualTo(clientId);
        assertThat(as(orgA, () -> products.findById(clientId))).isPresent();
    }

    @Test
    void generatedIdsAreVersion7() {
        Product saved = as(orgA, () -> products.save(product("A-GEN")));
        assertThat(saved.getId().version()).isEqualTo(7);
        assertThat(saved.getVersion()).isZero();
        assertThat(saved.getOrganizationId()).isEqualTo(orgA);
    }

    @Test
    void createdByRecordsTheActingMembership() {
        UUID membership = UUID.randomUUID();
        Category saved = TenantContext.call(new TenantContext.Current(orgA, membership, null),
                () -> inTransaction(() -> categories.save(new Category("Oils", null))));
        assertThat(saved.getCreatedBy()).isEqualTo(membership);
        assertThat(saved.getUpdatedBy()).isEqualTo(membership);
    }

    /** The composite foreign keys refuse a pointer into another tenant, whatever the code does. */
    @Test
    void theDatabaseRefusesAReferenceIntoAnotherTenant() {
        UUID bCategory = as(orgB, () -> categories.save(new Category("B only", null)).getId());
        Product aProduct = product("A-CROSS");
        aProduct.setCategoryId(bCategory);

        assertThatThrownBy(() -> as(orgA, () -> products.saveAndFlush(aProduct)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID newOrganization(String name) {
        UUID id = UuidV7Generator.newId();
        as(id, () -> organizations.save(new Organization(id, name, Slugs.generate(name))));
        return id;
    }

    private static Product product(String sku) {
        return new Product(sku + "-" + UUID.randomUUID().toString().substring(0, 8), "Product " + sku,
                ProductUnit.PIECE, new BigDecimal("1500.0000"));
    }

    private <T> T as(UUID organizationId, Supplier<T> work) {
        return TenantContext.call(new TenantContext.Current(organizationId, null, null), () -> inTransaction(work));
    }

    private <T> T inTransaction(Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }
}
