package app.ordaro.auth;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import app.ordaro.shared.web.ApiException;

/**
 * 6-digit register PINs (spec §12 PIN): bcrypt at a higher cost than passwords, because the
 * space is tiny. PIN login itself — bound device and lockout — is step 3.
 */
@Component
public class PinHasher {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);

    public String hash(String pin) {
        if (pin == null || !pin.matches("\\d{6}")) {
            throw ApiException.badRequest("invalid_pin", "a PIN is exactly 6 digits");
        }
        return encoder.encode(pin);
    }

    public boolean matches(String pin, String hash) {
        return pin != null && hash != null && encoder.matches(pin, hash);
    }
}
