package app.ordaro.auth;

import java.util.UUID;

import app.ordaro.org.MembershipRole;
import app.ordaro.org.MembershipStatus;

/** What the token layer needs to know about a membership, read without a tenant session. */
public record MembershipSnapshot(UUID id, UUID organizationId, UUID accountId, MembershipRole role,
        MembershipStatus status, UUID locationId) {

    public boolean isActive() {
        return status == MembershipStatus.ACTIVE;
    }
}
