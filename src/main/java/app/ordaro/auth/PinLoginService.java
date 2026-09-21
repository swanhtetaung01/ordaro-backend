package app.ordaro.auth;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.ordaro.auth.DeviceCheck.DeviceSnapshot;
import app.ordaro.auth.TokenService.IssuedTokens;
import app.ordaro.org.MembershipRole;
import app.ordaro.shared.web.ApiException;

/**
 * Register login: a membership's 6-digit PIN, accepted only together with a bound device
 * credential (spec §12 PIN). Lockout:
 * <ul>
 * <li>five consecutive wrong PINs for a membership on a device lock <em>that membership on
 * that device</em> for five minutes;</li>
 * <li>five consecutive wrong PINs on a device, across any memberships, lock <em>the device</em>
 * for five minutes.</li>
 * </ul>
 * The counters are independent; a correct PIN resets both for that pair. Attempts on one device
 * are serialized by locking its row, and a failed attempt's counters commit even though the call
 * fails ({@code noRollbackFor}).
 */
@Service
public class PinLoginService {

    static final int MAX_FAILURES = 5;
    static final Duration LOCKOUT = Duration.ofMinutes(5);

    /** One tile on the register's login screen. */
    public record StaffEntry(UUID membershipId, String displayName, MembershipRole role) {
    }

    private final DeviceCheck devices;
    private final MembershipDirectory memberships;
    private final PinHasher pins;
    private final TokenService tokens;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public PinLoginService(DeviceCheck devices, MembershipDirectory memberships, PinHasher pins,
            TokenService tokens, JdbcTemplate jdbc, Clock clock) {
        this.devices = devices;
        this.memberships = memberships;
        this.pins = pins;
        this.tokens = tokens;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Who can log in on this register: ACTIVE memberships with a PIN, allowed at its location. */
    @Transactional(readOnly = true)
    public List<StaffEntry> staff(String credential) {
        DeviceSnapshot device = usableDevice(credential, false);
        return jdbc.query("""
                select id, display_name, role from membership
                where organization_id = ? and status = 'ACTIVE' and pin_hash is not null
                  and archived_at is null and (location_id is null or location_id = ?)
                order by display_name
                """, (rs, i) -> new StaffEntry(rs.getObject(1, UUID.class), rs.getString(2),
                MembershipRole.valueOf(rs.getString(3))), device.organizationId(), device.locationId());
    }

    @Transactional(noRollbackFor = ApiException.class)
    public IssuedTokens login(String credential, UUID membershipId, String pin) {
        Instant now = clock.instant();
        DeviceSnapshot device = usableDevice(credential, true);
        if (device.lockedUntil() != null && device.lockedUntil().isAfter(now)) {
            throw locked("register_locked", "too many wrong PINs on this register; try again later");
        }

        String pinHash = membershipId == null ? null : jdbc.query("""
                select pin_hash from membership
                where id = ? and organization_id = ? and status = 'ACTIVE' and pin_hash is not null
                  and archived_at is null and (location_id is null or location_id = ?)
                """, (rs, i) -> rs.getString(1), membershipId, device.organizationId(), device.locationId())
                .stream().findFirst().orElse(null);

        if (pinHash != null) {
            // a lockout row exists after the first failure, with locked_until still null
            List<Timestamp> lockRows = jdbc.query(
                    "select locked_until from register_pin_lockout where device_id = ? and membership_id = ?",
                    (rs, i) -> rs.getTimestamp(1), device.id(), membershipId);
            Instant memberLockedUntil = lockRows.isEmpty() || lockRows.getFirst() == null ? null
                    : lockRows.getFirst().toInstant();
            if (memberLockedUntil != null && memberLockedUntil.isAfter(now)) {
                throw locked("membership_locked", "too many wrong PINs for this person on this register");
            }
        }

        if (pinHash == null || !pins.matches(pin, pinHash)) {
            recordFailure(device, membershipId, pinHash != null, now);
            throw ApiException.unauthorized("invalid_pin", "wrong PIN");
        }

        jdbc.update("delete from register_pin_lockout where device_id = ? and membership_id = ?", device.id(),
                membershipId);
        jdbc.update("""
                update register_device set failed_pin_count = 0, locked_until = null, last_seen_at = ?
                where id = ?""", Timestamp.from(now), device.id());
        MembershipSnapshot membership = memberships.find(membershipId).orElseThrow();
        return tokens.forRegister(membership, device, null);
    }

    private void recordFailure(DeviceSnapshot device, UUID membershipId, boolean knownMembership, Instant now) {
        Timestamp until = Timestamp.from(now.plus(LOCKOUT));
        if (knownMembership) {
            jdbc.update("""
                    insert into register_pin_lockout (organization_id, device_id, membership_id, failed_count)
                    values (?, ?, ?, 1)
                    on conflict (device_id, membership_id)
                    do update set failed_count = register_pin_lockout.failed_count + 1
                    """, device.organizationId(), device.id(), membershipId);
            jdbc.update("""
                    update register_pin_lockout set failed_count = 0, locked_until = ?
                    where device_id = ? and membership_id = ? and failed_count >= ?
                    """, until, device.id(), membershipId, MAX_FAILURES);
        }
        jdbc.update("""
                update register_device
                set failed_pin_count = case when failed_pin_count + 1 >= ? then 0 else failed_pin_count + 1 end,
                    locked_until = case when failed_pin_count + 1 >= ? then ? else locked_until end
                where id = ?""", MAX_FAILURES, MAX_FAILURES, until, device.id());
    }

    private DeviceSnapshot usableDevice(String credential, boolean lock) {
        if (credential == null || credential.isBlank()) {
            throw ApiException.unauthorized("register_required", "this call needs a bound register");
        }
        return (lock ? devices.lockByCredential(credential) : devices.findByCredential(credential))
                .filter(d -> d.usable(clock.instant()))
                .orElseThrow(() -> ApiException.unauthorized("register_invalid",
                        "unknown, revoked or expired register"));
    }

    private static ApiException locked(String code, String message) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, code, message);
    }
}
