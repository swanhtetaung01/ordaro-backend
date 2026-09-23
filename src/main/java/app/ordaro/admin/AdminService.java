package app.ordaro.admin;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.ordaro.auth.LoginAttempts;
import app.ordaro.auth.RefreshTokenRepository;
import app.ordaro.org.Account;
import app.ordaro.org.AccountRepository;
import app.ordaro.org.Phones;
import app.ordaro.shared.web.ApiException;

/**
 * What the person who runs the server can do from its command line, for the cases the app
 * cannot handle itself yet: someone forgot their password (self-service reset needs phone
 * verification, which is not built), someone locked themselves out, or a look at which shops
 * exist. Touches only {@code account}, {@code refresh_token} and {@code organization}, none of
 * which is a tenant table, so no tenant is needed.
 */
@Service
public class AdminService {

    /** Letters and digits that read unambiguously over the phone: no 0/O, 1/l/I. */
    private static final String ALPHABET = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789";

    private static final SecureRandom RANDOM = new SecureRandom();

    public record ShopRow(UUID id, String name, String slug, String businessType, Instant createdAt) {
    }

    private final AccountRepository accounts;
    private final PasswordEncoder passwords;
    private final RefreshTokenRepository refreshTokens;
    private final LoginAttempts loginAttempts;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AdminService(AccountRepository accounts, PasswordEncoder passwords, RefreshTokenRepository refreshTokens,
            LoginAttempts loginAttempts, JdbcTemplate jdbc, Clock clock) {
        this.accounts = accounts;
        this.passwords = passwords;
        this.refreshTokens = refreshTokens;
        this.loginAttempts = loginAttempts;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Sets a new random password, ends every session of the account and clears its lockout.
     * Returns the password once; it is stored only as a hash. Tell the person to change it under
     * Account after they sign in.
     */
    @Transactional
    public String resetPassword(String phone) {
        Account account = find(phone);
        String temporary = randomPassword(12);
        account.changePasswordHash(passwords.encode(temporary));
        refreshTokens.revokeAllForAccount(account.getId(), clock.instant());
        loginAttempts.clear(account.getId());
        return temporary;
    }

    /** Lifts a wrong-password lockout without touching the password. */
    @Transactional
    public void unlock(String phone) {
        loginAttempts.clear(find(phone).getId());
    }

    /** Every shop on this server, newest first. */
    @Transactional(readOnly = true)
    public List<ShopRow> shops() {
        return jdbc.query("""
                select id, name, slug, business_type, created_at from organization order by created_at desc
                """, (rs, row) -> new ShopRow(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getString("slug"), rs.getString("business_type"), rs.getTimestamp("created_at").toInstant()));
    }

    private Account find(String phone) {
        return accounts.findByPhone(Phones.normalize(phone))
                .orElseThrow(() -> ApiException.notFound("account_not_found", "no account with phone " + phone));
    }

    static String randomPassword(int length) {
        StringBuilder password = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            password.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return password.toString();
    }
}
