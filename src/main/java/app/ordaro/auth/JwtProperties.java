package app.ordaro.auth;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param keysFile a JWK set of EC P-256 private keys; the first key signs, every key verifies
 *                 (rotation: prepend the new key, drop the old one after one access-token TTL).
 *                 Blank means an ephemeral key generated at startup — development only.
 */
@ConfigurationProperties("ordaro.jwt")
public record JwtProperties(String issuer, Duration accessTtl, Duration refreshTtl, String keysFile) {
}
