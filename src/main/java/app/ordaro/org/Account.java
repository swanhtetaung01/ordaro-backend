package app.ordaro.org;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import app.ordaro.shared.persistence.BaseEntity;

/** A person with a global phone-and-password login (spec §3). Not tenant-owned. */
@Entity
@Table(name = "account")
public class Account extends BaseEntity {

    /** E.164, unique system-wide where not null. Null after a phone claim moves the number. */
    @Column(name = "phone", length = 20)
    private String phone;

    @Column(name = "phone_verified_at")
    private Instant phoneVerifiedAt;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 200)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private AccountStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "login_disabled_reason", length = 32)
    private LoginDisabledReason loginDisabledReason;

    protected Account() {
    }

    public Account(String phone, String passwordHash, String fullName) {
        this.phone = phone;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.status = AccountStatus.ACTIVE;
    }

    public boolean canLogIn() {
        return status == AccountStatus.ACTIVE && loginDisabledReason == null;
    }

    public boolean isPhoneVerified() {
        return phoneVerifiedAt != null;
    }

    public String getPhone() {
        return phone;
    }

    public Instant getPhoneVerifiedAt() {
        return phoneVerifiedAt;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getFullName() {
        return fullName;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public LoginDisabledReason getLoginDisabledReason() {
        return loginDisabledReason;
    }
}
