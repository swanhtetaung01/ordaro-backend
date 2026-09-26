package app.trillopos.auth;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Consecutive wrong passwords per account (V10). Ten in a row lock the login for fifteen
 * minutes; a right password clears the count. Atomic SQL rather than the entity, so parallel
 * attempts both count and never collide on the row's version. Must run inside the login
 * transaction, which commits even though the login fails.
 */
@Component
public class LoginAttempts {

    static final int MAX_FAILURES = 10;

    static final Duration LOCK = Duration.ofMinutes(15);

    private final JdbcTemplate jdbc;

    public LoginAttempts(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void recordFailure(UUID accountId, Instant now) {
        jdbc.update("""
                update account
                set failed_login_count = case when failed_login_count + 1 >= ? then 0 else failed_login_count + 1 end,
                    login_locked_until = case when failed_login_count + 1 >= ? then ? else login_locked_until end
                where id = ?
                """, MAX_FAILURES, MAX_FAILURES, Timestamp.from(now.plus(LOCK)), accountId);
    }

    public void clear(UUID accountId) {
        jdbc.update("""
                update account set failed_login_count = 0, login_locked_until = null
                where id = ? and (failed_login_count <> 0 or login_locked_until is not null)
                """, accountId);
    }
}
