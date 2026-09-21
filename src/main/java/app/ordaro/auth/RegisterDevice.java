package app.ordaro.auth;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import app.ordaro.shared.persistence.TenantEntity;

/**
 * A register bound to one STORE (spec §12 Tokens, PIN). Its credential is an opaque 256-bit
 * secret shown once at binding and stored hashed; a 6-digit PIN is accepted only together with
 * it. Valid for a year; revocable. The PIN lockout counters for the whole device live here; the
 * per-membership ones in {@code register_pin_lockout}.
 */
@Entity
@Table(name = "register_device")
public class RegisterDevice extends TenantEntity {

    @Column(name = "location_id", nullable = false, updatable = false)
    private UUID locationId;

    @Column(name = "label", nullable = false, length = 100)
    private String label;

    @Column(name = "credential_hash", nullable = false, length = 64, updatable = false)
    private String credentialHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private RegisterDeviceStatus status;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "failed_pin_count", nullable = false)
    private int failedPinCount;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected RegisterDevice() {
    }

    RegisterDevice(UUID locationId, String label, String credentialHash, Instant expiresAt) {
        this.locationId = locationId;
        this.label = label;
        this.credentialHash = credentialHash;
        this.expiresAt = expiresAt;
        this.status = RegisterDeviceStatus.ACTIVE;
    }

    void revoke(Instant now) {
        if (status == RegisterDeviceStatus.ACTIVE) {
            status = RegisterDeviceStatus.REVOKED;
            revokedAt = now;
        }
    }

    public UUID getLocationId() {
        return locationId;
    }

    public String getLabel() {
        return label;
    }

    public RegisterDeviceStatus getStatus() {
        return status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public int getFailedPinCount() {
        return failedPinCount;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }
}
