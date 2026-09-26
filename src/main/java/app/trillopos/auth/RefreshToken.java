package app.trillopos.auth;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import app.trillopos.shared.persistence.BaseEntity;

/**
 * Opaque refresh token, stored as a SHA-256 hash. 30-day sliding, rotates on use; presenting a
 * rotated (revoked) token again revokes the whole family (spec §12 Tokens). Not tenant-owned:
 * the hash lookup on refresh is the one deliberately unscoped read.
 */
@Entity
@Table(name = "refresh_token")
public class RefreshToken extends BaseEntity {

    @Column(name = "account_id")
    private UUID accountId;

    /** Set for {@code USER} tokens: the membership the next access token will name. */
    @Column(name = "membership_id")
    private UUID membershipId;

    /** Set for {@code REGISTER} tokens: the register the session is bound to. */
    @Column(name = "device_id")
    private UUID deviceId;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 32)
    private RefreshTokenKind kind;

    @Column(name = "device_label", length = 100)
    private String deviceLabel;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    protected RefreshToken() {
    }

    public RefreshToken(UUID accountId, UUID membershipId, UUID deviceId, String tokenHash, UUID familyId,
            RefreshTokenKind kind, String deviceLabel, Instant expiresAt) {
        this.accountId = accountId;
        this.membershipId = membershipId;
        this.deviceId = deviceId;
        this.tokenHash = tokenHash;
        this.familyId = familyId;
        this.kind = kind;
        this.deviceLabel = deviceLabel;
        this.expiresAt = expiresAt;
    }

    /** Rotation: a used token is revoked at once, so presenting it again is detectable reuse. */
    public void markUsed(Instant now) {
        this.lastUsedAt = now;
        this.revokedAt = now;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public UUID getAccountId() {
        return accountId;
    }

    public UUID getMembershipId() {
        return membershipId;
    }

    public UUID getDeviceId() {
        return deviceId;
    }

    public UUID getFamilyId() {
        return familyId;
    }

    public RefreshTokenKind getKind() {
        return kind;
    }

    public String getDeviceLabel() {
        return deviceLabel;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }
}
