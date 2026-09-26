package app.trillopos.shared.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;

import org.hibernate.annotations.TenantId;

/**
 * Every tenant-owned entity. {@code organizationId} is filled and filtered by Hibernate from
 * the current tenant ({@link TenantIdentifierResolver}); application code never sets it.
 */
@MappedSuperclass
public abstract class TenantEntity extends BaseEntity {

    @TenantId
    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "archived_at")
    private Instant archivedAt;

    public UUID getOrganizationId() {
        return organizationId;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public boolean isArchived() {
        return archivedAt != null;
    }

    public void archive(Instant at) {
        if (archivedAt == null) {
            archivedAt = at;
        }
    }
}
