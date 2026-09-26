package app.trillopos.shared.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import app.trillopos.shared.tenant.TenantContext;

/**
 * Row-level security's other half (spec §12 RLS): every connection announces its tenant before
 * the application uses it, so the policies added in V9 can compare {@code organization_id}
 * against {@code app.org}.
 *
 * <p>It happens on <em>borrow</em> rather than at the start of a transaction, because Spring Data
 * runs derived query methods (all the {@code findAllBy…} reads) outside any transaction — a
 * transaction-only hook would leave exactly those reads with no tenant, and RLS would answer them
 * with nothing. Every borrow overwrites both settings, so a pooled connection can never serve one
 * tenant with another's value; a borrow with no tenant in context sets the empty string, and the
 * policies then match no row at all — failing closed, never open.
 *
 * <p>The PIN login, which learns its organization from the database itself, narrows this for its
 * own transaction through {@code TenantSession} ({@code set_config(…, true)}, transaction-local).
 */
public class TenantDataSource extends DelegatingDataSource {

    private static final String SET_TENANT =
            "select set_config('app.org', ?, false), set_config('app.account', ?, false)";

    public TenantDataSource(DataSource delegate) {
        super(delegate);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return announce(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return announce(super.getConnection(username, password));
    }

    private static Connection announce(Connection connection) throws SQLException {
        TenantContext.Current current = TenantContext.current().orElse(null);
        try (PreparedStatement statement = connection.prepareStatement(SET_TENANT)) {
            statement.setString(1, text(current == null ? null : current.organizationId()));
            statement.setString(2, text(current == null ? null : current.accountId()));
            statement.execute();
        } catch (SQLException | RuntimeException e) {
            connection.close();
            throw e;
        }
        return connection;
    }

    private static String text(UUID id) {
        return id == null ? "" : id.toString();
    }
}
