package app.ordaro.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import app.ordaro.shared.persistence.UuidV7Generator;

/**
 * Issues an access token (ES256 JWT, 15 minutes) with a refresh token (opaque, 30-day sliding).
 * Callers run inside their own transaction so the refresh token commits with the change that
 * earned it.
 */
@Service
public class TokenService {

    /** Claim names (spec §12 Tokens). */
    public static final String KIND = "kind";
    public static final String ORG = "org";
    public static final String MEM = "mem";
    public static final String ROLE = "role";
    public static final String LOC = "loc";
    public static final String DEV = "dev";

    public record IssuedTokens(String kind, String accessToken, Instant accessTokenExpiresAt, String refreshToken,
            Instant refreshTokenExpiresAt) {
    }

    private final JwtEncoder encoder;
    private final JwtKeys keys;
    private final JwtProperties properties;
    private final RefreshTokenRepository refreshTokens;
    private final Clock clock;

    public TokenService(JwtEncoder encoder, JwtKeys keys, JwtProperties properties,
            RefreshTokenRepository refreshTokens, Clock clock) {
        this.encoder = encoder;
        this.keys = keys;
        this.properties = properties;
        this.refreshTokens = refreshTokens;
        this.clock = clock;
    }

    /** A tenant session for one membership. {@code familyId} null starts a new refresh family. */
    public IssuedTokens forMembership(UUID accountId, MembershipSnapshot membership, UUID familyId,
            String deviceLabel) {
        Instant now = clock.instant();
        JwtClaimsSet.Builder claims = baseClaims(now, RefreshTokenKind.USER, accountId)
                .claim(ORG, membership.organizationId().toString())
                .claim(MEM, membership.id().toString())
                .claim(ROLE, membership.role().name());
        if (membership.locationId() != null) {
            claims.claim(LOC, membership.locationId().toString());
        }
        return issue(claims, now, accountId, membership.id(), null, RefreshTokenKind.USER, familyId, deviceLabel);
    }

    /**
     * A PIN session on a register: the membership, scoped to the register's STORE ({@code loc}), bound
     * to the device ({@code dev}). {@code sub} is the account when the membership has one.
     */
    public IssuedTokens forRegister(MembershipSnapshot membership, DeviceCheck.DeviceSnapshot device, UUID familyId) {
        Instant now = clock.instant();
        JwtClaimsSet.Builder claims = baseClaims(now, RefreshTokenKind.REGISTER, membership.accountId())
                .claim(ORG, membership.organizationId().toString())
                .claim(MEM, membership.id().toString())
                .claim(ROLE, membership.role().name())
                .claim(LOC, device.locationId().toString())
                .claim(DEV, device.id().toString());
        return issue(claims, now, membership.accountId(), membership.id(), device.id(), RefreshTokenKind.REGISTER,
                familyId, null);
    }

    /** No tenant: can only list memberships, switch into one, or accept an invitation. */
    public IssuedTokens forPicker(UUID accountId, UUID familyId, String deviceLabel) {
        Instant now = clock.instant();
        return issue(baseClaims(now, RefreshTokenKind.PICKER, accountId), now, accountId, null, null,
                RefreshTokenKind.PICKER, familyId, deviceLabel);
    }

    private JwtClaimsSet.Builder baseClaims(Instant now, RefreshTokenKind kind, UUID accountId) {
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .issuedAt(now)
                .expiresAt(now.plus(properties.accessTtl()))
                .id(UUID.randomUUID().toString())
                .claim(KIND, kind.name());
        if (accountId != null) {
            claims.subject(accountId.toString());
        }
        return claims;
    }

    private IssuedTokens issue(JwtClaimsSet.Builder claims, Instant now, UUID accountId, UUID membershipId,
            UUID deviceId, RefreshTokenKind kind, UUID familyId, String deviceLabel) {
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.ES256).keyId(keys.signingKeyId()).type("JWT").build();
        JwtClaimsSet claimsSet = claims.build();
        String accessToken = encoder.encode(JwtEncoderParameters.from(header, claimsSet)).getTokenValue();

        String refreshToken = Secrets.newRefreshToken();
        Instant refreshExpiresAt = now.plus(properties.refreshTtl());
        refreshTokens.save(new RefreshToken(accountId, membershipId, deviceId, Secrets.sha256Hex(refreshToken),
                familyId != null ? familyId : UuidV7Generator.newId(), kind, deviceLabel, refreshExpiresAt));
        return new IssuedTokens(kind.name(), accessToken, claimsSet.getExpiresAt(), refreshToken, refreshExpiresAt);
    }
}
