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
 * Step 6 (RLS): the first two are covered by {@code app.org} / {@code app.account}; the invite
 * lookup crosses organizations and needs its own path before the policies switch on.
 */
@Component
public class MembershipDirectory {

    /** One row of the login picker. */
    public record PickerEntry(UUID membershipId, UUID organizationId, String organizationName, String displayName,
            MembershipRole role, MembershipStatus status) {
    }

    public record Invite(UUID membershipId, UUID organizationId, MembershipStatus status, Instant expiresAt) {
    }

    private static final String SNAPSHOT = """
            select id, organization_id, account_id, role, status, location_id
            from membership
            """;

    private final JdbcTemplate jdbc;

    public MembershipDirectory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<MembershipSnapshot> find(UUID membershipId) {
        return jdbc.query(SNAPSHOT + "where id = ?", MembershipDirectory::snapshot, membershipId)
                .stream().findFirst();
    }

    public Optional<MembershipSnapshot> activeFor(UUID accountId, UUID organizationId) {
        return jdbc.query(SNAPSHOT + "where account_id = ? and organization_id = ? and status = 'ACTIVE'",
                MembershipDirectory::snapshot, accountId, organizationId).stream().findFirst();
    }

    /** The picker: one native query, filtered by account (spec §12). */
    public List<PickerEntry> forAccount(UUID accountId) {
        return jdbc.query("""
                select m.id, m.organization_id, o.name, m.display_name, m.role, m.status
                from membership m
                join organization o on o.id = m.organization_id
                where m.account_id = ?
                  and m.status in ('ACTIVE', 'INVITED')
                  and m.archived_at is null
                order by o.name, m.created_at
                """,
                (rs, i) -> new PickerEntry(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getString(4), MembershipRole.valueOf(rs.getString(5)),
                        MembershipStatus.valueOf(rs.getString(6))),
                accountId);
    }

    public Optional<Invite> findInviteByCodeHash(String codeHash) {
        return jdbc.query("""
                select id, organization_id, status, invite_expires_at
                from membership
                where invite_code_hash = ?
                """,
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
