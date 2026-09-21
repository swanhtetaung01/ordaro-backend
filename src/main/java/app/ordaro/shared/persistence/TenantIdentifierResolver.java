package app.ordaro.shared.persistence;

import java.util.UUID;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;

import app.ordaro.shared.tenant.TenantContext;

/**
 * Feeds {@code @TenantId}. With no tenant in context it returns {@link #NO_TENANT} rather
 * than null: a session without a tenant must match <em>no</em> rows, never all of them, and
 * an insert without a tenant fails on the organization foreign key. {@code isRoot} is never
 * used (spec §12 — not a login sentinel); login and the picker read memberships natively.
 */
public class TenantIdentifierResolver implements CurrentTenantIdentifierResolver<UUID> {

    public static final UUID NO_TENANT = new UUID(0L, 0L);

    @Override
    public UUID resolveCurrentTenantIdentifier() {
        return TenantContext.organizationId().orElse(NO_TENANT);
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return false;
    }
}
