package app.ordaro.auth;

/** USER is bound to a membership; PICKER only to an account; REGISTER to a membership on one register device. */
public enum RefreshTokenKind {
    USER,
    PICKER,
    REGISTER
}
