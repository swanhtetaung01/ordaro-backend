package app.ordaro.auth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import app.ordaro.org.MembershipRole;
import app.ordaro.org.MembershipStatus;

/**
 * The deliberately unscoped native reads of {@code membership} (spec §1 Tenant enforcement,
 * §12 Tenant resolution). Each runs before a tenant is known and is keyed so it can only return
 * rows the caller is entitled to:
 * <ul>
 * <li>{@link #find} — by membership id, for the per-request token check; the caller compares
 * the organization with the token's {@code org} claim.</li>
 * <li>{@link #forAccount} / {@link #activeFor} — the login picker and switch, by account id.</li>
 * <li>{@link #findInviteByCodeHash} — by the hash of a 6-character invite code.</li>
 * </ul>
 * Step 6 (RLS): each of these runs before a tenant is known, so none of them can satisfy a
 * policy. They go through the {@code ordaro.auth_*} SECURITY DEFINER functions of V9, which are
 * owned by {@code ordaro_owner}, keyed by an id or an unguessable hash, and return only these
 * columns.
 */
@Component
public class MembershipDirectory {

    /** One row of the login picker. */
    public record PickerEntry(UUID membershipId, UUID organizationId, String organizationName, String displayName,
            MembershipRole role, MembershipStatus status) {
    }

    public record Invite(UUID membershipId, UUID organizationId, MembershipStatus status, Instant expiresAt) {
    }

    private static final String SNAPSHOT =
            "select id, organization_id, account_id, role, status, location_id from ";

    private final JdbcTemplate jdbc;

    public MembershipDirectory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<MembershipSnapshot> find(UUID membershipId) {
        return jdbc.query(SNAPSHOT + "ordaro.auth_membership(?)", MembershipDirectory::snapshot, membershipId)
                .stream().findFirst();
    }

    public Optional<MembershipSnapshot> activeFor(UUID accountId, UUID organizationId) {
        return jdbc.query(SNAPSHOT + "ordaro.auth_membership_active(?, ?)",
                MembershipDirectory::snapshot, accountId, organizationId).stream().findFirst();
    }

    /** The picker: one native query, filtered by account (spec §12). */
    public List<PickerEntry> forAccount(UUID accountId) {
        return jdbc.query("select * from ordaro.auth_memberships_for_account(?)",
                (rs, i) -> new PickerEntry(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getString(4), MembershipRole.valueOf(rs.getString(5)),
                        MembershipStatus.valueOf(rs.getString(6))),
                accountId);
    }

    public Optional<Invite> findInviteByCodeHash(String codeHash) {
        return jdbc.query("select * from ordaro.auth_membership_by_invite(?)",
                (rs, i) -> new Invite(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        MembershipStatus.valueOf(rs.getString(3)), instant(rs.getTimestamp(4))),
                codeHash).stream().findFirst();
    }

    private static MembershipSnapshot snapshot(ResultSet rs, int row) throws SQLException {
        return new MembershipSnapshot(rs.getObject("id", UUID.class), rs.getObject("organization_id", UUID.class),
                rs.getObject("account_id", UUID.class), MembershipRole.valueOf(rs.getString("role")),
                MembershipStatus.valueOf(rs.getString("status")), rs.getObject("location_id", UUID.class));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
