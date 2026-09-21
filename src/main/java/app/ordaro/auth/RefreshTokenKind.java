package app.ordaro.auth;

/** USER is bound to a membership; PICKER only to an account (login picker). REGISTER comes in step 3. */
public enum RefreshTokenKind {
    USER,
    PICKER
}
