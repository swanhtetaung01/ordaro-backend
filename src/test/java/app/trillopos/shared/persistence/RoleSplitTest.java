package app.trillopos.shared.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import app.trillopos.support.IntegrationTest;
import app.trillopos.support.TestDatabase;

/**
 * Spec §12 RLS, first half (step 1): the app connects as a role that owns nothing and cannot
 * bypass RLS; Flyway ran as the owner; V1's default privileges cover every V1 table. The
 * Supabase-specific half (a non-superuser {@code postgres}, the pooler) is the spike's job.
 */
class RoleSplitTest extends IntegrationTest {

    @Test
    void theAppRoleIsNeitherSuperuserNorRlsBypassing() throws SQLException {
        try (Connection app = TestDatabase.connectAsApp(); Statement s = app.createStatement();
                ResultSet rs = s.executeQuery(
                        "select rolsuper, rolbypassrls from pg_roles where rolname = current_user")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getBoolean(1)).isFalse();
            assertThat(rs.getBoolean(2)).isFalse();
        }
    }

    @Test
    void everyTableIsOwnedByTheOwnerRole() throws SQLException {
        List<String> owners = new ArrayList<>();
        try (Connection app = TestDatabase.connectAsApp(); Statement s = app.createStatement();
                ResultSet rs = s.executeQuery("select distinct tableowner from pg_tables where schemaname = 'trillopos'")) {
            while (rs.next()) {
                owners.add(rs.getString(1));
            }
        }
        assertThat(owners).containsExactly(TestDatabase.OWNER);
    }

    @Test
    void defaultPrivilegesLetTheAppReadEveryTable() throws SQLException {
        try (Connection app = TestDatabase.connectAsApp(); Statement s = app.createStatement()) {
            for (String table : List.of("organization", "account", "location", "membership", "refresh_token",
                    "phone_verification", "category", "supplier", "product", "product_barcode",
                    "location_product", "document_sequence", "stock_document", "stock_document_line",
                    "stock_balance", "stock_movement")) {
                s.executeQuery("select count(*) from " + table).close();
            }
        }
    }

    @Test
    void theAppRoleCannotChangeTheSchema() throws SQLException {
        try (Connection app = TestDatabase.connectAsApp(); Statement s = app.createStatement()) {
            assertThatThrownBy(() -> s.execute("create table evil (x int)")).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> s.execute("alter table product disable row level security"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> s.execute("drop table flyway_schema_history")).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> s.execute("set role trillopos_owner")).isInstanceOf(SQLException.class);
        }
    }

    @Test
    void flywayHistoryIsNotReadableByTheApp() throws SQLException {
        try (Connection app = TestDatabase.connectAsApp(); Statement s = app.createStatement()) {
            assertThatThrownBy(() -> s.executeQuery("select * from flyway_schema_history"))
                    .isInstanceOf(SQLException.class);
        }
    }
}
