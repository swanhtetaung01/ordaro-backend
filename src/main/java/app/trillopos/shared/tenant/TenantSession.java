package app.trillopos.shared.tenant;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adopts a tenant for the rest of the current transaction, for the one flow that learns which
 * organization it is in from the database itself: a register's PIN login, which starts with a
 * credential and no session. Everything after this goes through the ordinary policies.
 *
 * <p>Transaction-local, like the hook in {@code TenantTransactionManager}, so the connection
 * returns to the pool with nothing set.
 */
@Component
public class TenantSession {

    private final JdbcTemplate jdbc;

    public TenantSession(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void adopt(UUID organizationId) {
        jdbc.queryForObject("select set_config('app.org', ?, true)", String.class,
                organizationId == null ? "" : organizationId.toString());
    }
}
