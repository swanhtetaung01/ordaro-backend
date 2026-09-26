package app.trillopos.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Per-request membership verification behind a short in-process cache (spec §12: 30 s). A
 * membership changed on this instance stops working at once ({@link #evict}); on other
 * instances within the cache TTL; everywhere by the access token's {@code exp} at worst.
 */
@Component
public class MembershipCheck {

    private record Entry(Optional<MembershipSnapshot> membership, Instant loadedAt) {
    }

    private final MembershipDirectory directory;
    private final Clock clock;
    private final Duration ttl;
    private final ConcurrentHashMap<UUID, Entry> cache = new ConcurrentHashMap<>();

    public MembershipCheck(MembershipDirectory directory, Clock clock,
            @Value("${trillopos.tenant.membership-cache-ttl:30s}") Duration ttl) {
        this.directory = directory;
        this.clock = clock;
        this.ttl = ttl;
    }

    public Optional<MembershipSnapshot> find(UUID membershipId) {
        Instant now = clock.instant();
        Entry entry = cache.get(membershipId);
        if (entry == null || !entry.loadedAt().plus(ttl).isAfter(now)) {
            entry = new Entry(directory.find(membershipId), now);
            if (!ttl.isZero()) {
                cache.put(membershipId, entry);
            }
        }
        return entry.membership();
    }

    public void evict(UUID membershipId) {
        cache.remove(membershipId);
    }
}
