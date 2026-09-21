package app.ordaro.shared.tenant;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Who is acting, for the current thread. Set by the request filter from a verified access
 * token, or explicitly by code that must act for a tenant it has just created (sign-up) or
 * resolved (invite acceptance).
 *
 * <p>Hibernate binds the tenant when a session opens — with open-in-view off, at the start of
 * a transaction — so the context must be set <em>before</em> the transaction begins.
 */
public final class TenantContext {

    /** @param organizationId null outside a tenant (login, picker, refresh). */
    public record Current(UUID organizationId, UUID membershipId, UUID accountId) {
    }

    private static final ThreadLocal<Current> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static Optional<Current> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static Optional<UUID> organizationId() {
        return current().map(Current::organizationId);
    }

    public static Optional<UUID> membershipId() {
        return current().map(Current::membershipId);
    }

    public static Optional<UUID> accountId() {
        return current().map(Current::accountId);
    }

    public static UUID requireOrganizationId() {
        return organizationId().orElseThrow(() -> new IllegalStateException("no tenant in context"));
    }

    public static UUID requireMembershipId() {
        return membershipId().orElseThrow(() -> new IllegalStateException("no membership in context"));
    }

    public static <T> T call(Current current, Supplier<T> work) {
        Current previous = CURRENT.get();
        CURRENT.set(current);
        try {
            return work.get();
        } finally {
            restore(previous);
        }
    }

    public static void run(Current current, Runnable work) {
        call(current, () -> {
            work.run();
            return null;
        });
    }

    /** For the request filter, which cannot wrap a chain call that throws checked exceptions. */
    public static Current enter(Current current) {
        Current previous = CURRENT.get();
        CURRENT.set(current);
        return previous;
    }

    public static void restore(Current previous) {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }
}
