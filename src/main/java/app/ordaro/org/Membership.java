package app.ordaro.org;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import app.ordaro.shared.persistence.TenantEntity;

/**
 * Org-local, mandatory: every tenant-scoped action is performed by a membership (spec §3).
 * {@code accountId} is null for register-only staff (a PIN membership with no global account).
 * Keeps {@code @TenantId}; the login picker reads memberships with a native query instead.
 */
@Entity
@Table(name = "membership")
public class Membership extends TenantEntity {

    @Column(name = "account_id")
    private UUID accountId;

    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 32)
    private MembershipRole role;

    /** Null means every location — how an owner is expressed. */
    @Column(name = "location_id")
    private UUID locationId;

    /** 6-digit PIN, bcrypt at a higher cost than passwords; only accepted from a bound register (step 3). */
    @Column(name = "pin_hash", length = 255)
    private String pinHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private MembershipStatus status;

    @Column(name = "invited_phone", length = 20)
    private String invitedPhone;

    @Column(name = "invite_code_hash", length = 64)
    private String inviteCodeHash;

    @Column(name = "invite_expires_at")
    private Instant inviteExpiresAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    protected Membership() {
    }

    private Membership(UUID accountId, String displayName, MembershipRole role, UUID locationId,
            MembershipStatus status) {
        this.accountId = accountId;
        this.displayName = displayName;
        this.role = role;
        this.locationId = locationId;
        this.status = status;
    }

    public static Membership owner(UUID accountId, String displayName, Instant now) {
        Membership m = new Membership(accountId, displayName, MembershipRole.OWNER, null, MembershipStatus.ACTIVE);
        m.acceptedAt = now;
        return m;
    }

    /** Owner adds name + PIN; the person can link a global account later. */
    public static Membership registerOnly(String displayName, MembershipRole role, UUID locationId, String pinHash) {
        Membership m = new Membership(null, displayName, role, locationId, MembershipStatus.ACTIVE);
        m.pinHash = pinHash;
        return m;
    }

    /** A verified phone matched an account: the invitation shows up in that account's picker. */
    public static Membership invitedAccount(UUID accountId, String displayName, MembershipRole role, UUID locationId,
            String phone) {
        Membership m = new Membership(accountId, displayName, role, locationId, MembershipStatus.INVITED);
        m.invitedPhone = phone;
        return m;
    }

    /** The code is the authority; the phone is only a hint. */
    public static Membership invitedByCode(String displayName, MembershipRole role, UUID locationId, String phone,
            String codeHash, Instant expiresAt) {
        Membership m = new Membership(null, displayName, role, locationId, MembershipStatus.INVITED);
        m.invitedPhone = phone;
        m.inviteCodeHash = codeHash;
        m.inviteExpiresAt = expiresAt;
        return m;
    }

    public void accept(UUID accountId, Instant now) {
        if (status != MembershipStatus.INVITED) {
            throw new IllegalStateException("only an invited membership can be accepted");
        }
        this.accountId = accountId;
        this.status = MembershipStatus.ACTIVE;
        this.acceptedAt = now;
        this.inviteCodeHash = null;
        this.inviteExpiresAt = null;
    }

    public void changeStatus(MembershipStatus status) {
        this.status = status;
    }

    public boolean isActive() {
        return status == MembershipStatus.ACTIVE;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public MembershipRole getRole() {
        return role;
    }

    public UUID getLocationId() {
        return locationId;
    }

    public String getPinHash() {
        return pinHash;
    }

    public MembershipStatus getStatus() {
        return status;
    }

    public String getInvitedPhone() {
        return invitedPhone;
    }

    public Instant getInviteExpiresAt() {
        return inviteExpiresAt;
    }

    public Instant getAcceptedAt() {
        return acceptedAt;
    }
}
