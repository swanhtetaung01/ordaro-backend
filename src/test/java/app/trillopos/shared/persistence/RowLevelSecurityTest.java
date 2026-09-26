package app.trillopos.shared.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import app.trillopos.catalog.ProductRepository;
import app.trillopos.support.IntegrationTest;
import app.trillopos.support.Shops;
import app.trillopos.support.Shops.Shop;
import app.trillopos.support.TestDatabase;

/**
 * Spec §12 RLS, second half (step 6): the policies of V9, tested where they matter — on a raw
 * connection as {@code trillopos_app}, which is what a native query, a mistake in a repository, or
 * a future reporting job would use. {@code @TenantId} is not involved here at all.
 */
class RowLevelSecurityTest extends IntegrationTest {

    @Autowired
    Shops shops;

    @Autowired
    ProductRepository products;

    @Test
    void oneTenantsRowsAreInvisibleToAnotherAndToNobody() throws SQLException {
        Shop alpha = shops.newShop();
        Shop beta = shops.newShop();
        UUID alphaProduct = shops.product(alpha, "Alpha Oil");
        shops.product(beta, "Beta Rice");

        try (Connection app = TestDatabase.connectAsApp()) {
            // no tenant announced: the policies match nothing at all
            assertThat(rows(app, "select count(*) from product")).isZero();
            assertThat(rows(app, "select count(*) from location")).isZero();

            announce(app, alpha.organizationId(), null);
            assertThat(names(app, "select name from product")).containsExactly("Alpha Oil");
            assertThat(rows(app, "select count(*) from location")).isEqualTo(1);

            announce(app, beta.organizationId(), null);
            assertThat(names(app, "select name from product")).containsExactly("Beta Rice");
            // even naming the row explicitly, which is what a native query would do
            assertThat(rows(app, "select count(*) from product where id = '" + alphaProduct + "'")).isZero();
        }
    }

    @Test
    void aWriteForAnotherTenantIsRefused() throws SQLException {
        Shop alpha = shops.newShop();
        Shop beta = shops.newShop();

        try (Connection app = TestDatabase.connectAsApp()) {
            announce(app, alpha.organizationId(), null);
            assertThatThrownBy(() -> insertCategory(app, beta.organizationId()))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("row-level security");
            // its own tenant is fine
            insertCategory(app, alpha.organizationId());
            assertThat(rows(app, "select count(*) from category")).isEqualTo(1);

            announce(app, beta.organizationId(), null);
            assertThat(rows(app, "select count(*) from category")).isZero();
        }
    }

    /** The picker reads an account's memberships across organizations: the second policy on membership. */
    @Test
    void anAccountSeesItsOwnMembershipsWithNoOrganizationAnnounced() throws SQLException {
        Shop shop = shops.newShop();

        try (Connection app = TestDatabase.connectAsApp()) {
            assertThat(rows(app, "select count(*) from membership")).isZero();

            announce(app, null, shop.accountId());
            assertThat(rows(app, "select count(*) from membership")).isEqualTo(1);
            assertThat(rows(app, "select count(*) from product")).isZero(); // only membership, nothing else

            announce(app, null, UUID.randomUUID());
            assertThat(rows(app, "select count(*) from membership")).isZero();
        }
    }

    /** The pre-tenant lookups keep working, because each is a SECURITY DEFINER function. */
    @Test
    void theAuthFunctionsAnswerWithNoTenantAnnounced() throws SQLException {
        Shop shop = shops.newShop();

        try (Connection app = TestDatabase.connectAsApp()) {
            assertThat(rows(app, "select count(*) from trillopos.auth_membership('" + shop.membershipId() + "')"))
                    .isEqualTo(1);
            assertThat(rows(app, "select count(*) from trillopos.auth_memberships_for_account('"
                    + shop.accountId() + "')")).isEqualTo(1);
            assertThat(rows(app, "select count(*) from trillopos.auth_membership_active('" + shop.accountId()
                    + "', '" + shop.organizationId() + "')")).isEqualTo(1);
            assertThat(rows(app, "select count(*) from trillopos.auth_membership_by_invite('no-such-hash')")).isZero();
            assertThat(rows(app, "select count(*) from trillopos.auth_register_device('"
                    + UUID.randomUUID() + "')")).isZero();
        }
    }

    /** A table added later without its policy would be readable by every tenant; this fails first. */
    @Test
    void everyTenantTableHasRowLevelSecurityAndATenantPolicy() throws SQLException {
        List<String> unprotected = new ArrayList<>();
        try (Connection app = TestDatabase.connectAsApp(); Statement s = app.createStatement();
                ResultSet rs = s.executeQuery("""
                        select c.relname, c.relrowsecurity,
                               (select count(*) from pg_policy p
                                where p.polrelid = c.oid and p.polname = 'tenant_isolation') as policies
                        from pg_class c
                        join pg_namespace n on n.oid = c.relnamespace
                        where n.nspname = 'trillopos' and c.relkind = 'r'
                          and exists (select 1 from pg_attribute a
                                      where a.attrelid = c.oid and a.attname = 'organization_id' and a.attnum > 0)
                        order by c.relname""")) {
            int tenantTables = 0;
            while (rs.next()) {
                tenantTables++;
                if (!rs.getBoolean(2) || rs.getInt(3) != 1) {
                    unprotected.add(rs.getString(1));
                }
            }
            assertThat(tenantTables).as("tables with an organization_id").isGreaterThanOrEqualTo(27);
        }
        assertThat(unprotected).as("tenant tables without RLS or without their policy").isEmpty();
    }

    /** The application's own pool announces the tenant on every borrow, transaction or not. */
    @Test
    void repositoryReadsOutsideATransactionStillSeeTheirTenant() {
        Shop alpha = shops.newShop();
        shops.product(alpha, "Alpha Oil");
        Shop beta = shops.newShop();

        // a derived query method: Spring Data runs it with no transaction of its own
        assertThat(shops.inTenant(alpha, () -> products.findAllByArchivedAtIsNullOrderByNameAsc())).hasSize(1);
        assertThat(shops.inTenant(beta, () -> products.findAllByArchivedAtIsNullOrderByNameAsc())).isEmpty();
    }

    // ───────────────────────────────────────────────────────────── helpers

    private static void announce(Connection connection, UUID organizationId, UUID accountId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select set_config('app.org', ?, false), set_config('app.account', ?, false)")) {
            statement.setString(1, organizationId == null ? "" : organizationId.toString());
            statement.setString(2, accountId == null ? "" : accountId.toString());
            statement.execute();
        }
    }

    private static void insertCategory(Connection connection, UUID organizationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into category (id, version, created_at, updated_at, organization_id, name)
                values (?, 0, now(), now(), ?, 'Smuggled')""")) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, organizationId);
            statement.executeUpdate();
        }
    }

    private static long rows(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static List<String> names(Connection connection, String sql) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        return names;
    }
}
