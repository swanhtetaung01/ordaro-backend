package app.ordaro.auth;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Register device lookups without a tenant session: by id for the per-request check of a
 * REGISTER token (cached like memberships, evicted on revoke), and by credential hash — locked —
 * for PIN login. Both are deliberate unscoped native reads keyed by an unguessable value.
 */
@Component
public class DeviceCheck {

    public record DeviceSnapshot(UUID id, UUID organizationId, UUID locationId, RegisterDeviceStatus status,
            Instant expiresAt, int failedPinCount, Instant lockedUntil) {

        public boolean usable(Instant now) {
            return status == RegisterDeviceStatus.ACTIVE && expiresAt.isAfter(now);
        }
    }

    private record Entry(Optional<DeviceSnapshot> device, Instant loadedAt) {
    }

    private static final String COLUMNS = """
            select id, organization_id, location_id, status, expires_at, failed_pin_count, locked_until
            from register_device
            """;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Duration ttl;
    private final ConcurrentHashMap<UUID, Entry> cache = new ConcurrentHashMap<>();

    public DeviceCheck(JdbcTemplate jdbc, Clock clock,
            @Value("${ordaro.tenant.membership-cache-ttl:30s}") Duration ttl) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.ttl = ttl;
    }

    public Optional<DeviceSnapshot> find(UUID deviceId) {
        Instant now = clock.instant();
        Entry entry = cache.get(deviceId);
        if (entry == null || !entry.loadedAt().plus(ttl).isAfter(now)) {
            entry = new Entry(load(COLUMNS + "where id = ?", deviceId), now);
            if (!ttl.isZero()) {
                cache.put(deviceId, entry);
            }
        }
        return entry.device();
    }

    /** For the register's staff list: no lock. */
    public Optional<DeviceSnapshot> findByCredential(String credential) {
        return load(COLUMNS + "where credential_hash = ?", Secrets.sha256Hex(credential));
    }

    /** For PIN login: the device row is locked for the rest of the transaction. */
    public Optional<DeviceSnapshot> lockByCredential(String credential) {
        return load(COLUMNS + "where credential_hash = ? for update", Secrets.sha256Hex(credential));
    }

    /** Uncached, for refresh: a revoked register must not renew a session. */
    public Optional<DeviceSnapshot> lockByIdForRefresh(UUID deviceId) {
        return deviceId == null ? Optional.empty() : load(COLUMNS + "where id = ?", deviceId);
    }

    public void evict(UUID deviceId) {
        cache.remove(deviceId);
    }

    private Optional<DeviceSnapshot> load(String sql, Object key) {
        return jdbc.query(sql, (rs, i) -> new DeviceSnapshot(rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                RegisterDeviceStatus.valueOf(rs.getString(4)), rs.getTimestamp(5).toInstant(), rs.getInt(6),
                instant(rs.getTimestamp(7))), key).stream().findFirst();
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
