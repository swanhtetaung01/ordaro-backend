package app.trillopos.auth;

import java.util.UUID;

import app.trillopos.org.MembershipRole;
import app.trillopos.org.MembershipStatus;

/** What the token layer needs to know about a membership, read without a tenant session. */
public record MembershipSnapshot(UUID id, UUID organizationId, UUID accountId, MembershipRole role,
        MembershipStatus status, UUID locationId) {

    public boolean isActive() {
        return status == MembershipStatus.ACTIVE;
    }
}
