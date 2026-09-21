package app.ordaro.auth;

/** Column register_device.status. A revoked register can no longer take PIN logins. */
public enum RegisterDeviceStatus {
    ACTIVE,
    REVOKED
}
