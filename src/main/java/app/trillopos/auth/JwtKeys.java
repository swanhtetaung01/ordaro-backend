package app.trillopos.auth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.text.ParseException;
import java.util.Map;
import java.util.UUID;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** ES256 signing keys with {@code kid} rotation; the public half is served as JWKS. */
@Component
public class JwtKeys {

    private static final Logger log = LoggerFactory.getLogger(JwtKeys.class);

    private final JWKSet privateKeys;
    private final String signingKeyId;

    public JwtKeys(JwtProperties properties) {
        this.privateKeys = load(properties.keysFile());
        this.signingKeyId = privateKeys.getKeys().getFirst().getKeyID();
    }

    public String signingKeyId() {
        return signingKeyId;
    }

    public JWKSource<SecurityContext> signingSource() {
        return new ImmutableJWKSet<>(privateKeys);
    }

    public JWKSource<SecurityContext> verificationSource() {
        return new ImmutableJWKSet<>(privateKeys.toPublicJWKSet());
    }

    public Map<String, Object> publicJwks() {
        return privateKeys.toPublicJWKSet().toJSONObject();
    }

    private static JWKSet load(String keysFile) {
        try {
            if (keysFile == null || keysFile.isBlank()) {
                log.warn("trillopos.jwt.keys-file is not set: using an ephemeral signing key; tokens die on restart");
                return new JWKSet(generate());
            }
            Path path = Path.of(keysFile);
            if (!Files.exists(path)) {
                return create(path);
            }
            JWKSet set = JWKSet.load(path.toFile());
            if (set.getKeys().isEmpty() || !(set.getKeys().getFirst() instanceof ECKey key) || !key.isPrivate()) {
                throw new IllegalStateException("the first key in " + keysFile + " must be a private EC key");
            }
            return set;
        } catch (IOException | ParseException e) {
            throw new IllegalStateException("cannot read JWT keys from " + keysFile, e);
        }
    }

    /**
     * First start with a configured path: mint a key and keep it there, so every later start —
     * and every deploy — signs with the same key and nobody is logged out. The file holds a
     * private key: owner-only permissions where the file system has them.
     */
    private static JWKSet create(Path path) throws IOException {
        JWKSet set = new JWKSet(generate());
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.writeString(path, set.toString(false));
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            // Windows: no POSIX permissions; development only
        }
        log.info("created a new JWT signing key at {}", path);
        return set;
    }

    /** A new P-256 signing key; also what an operator runs to mint a key for rotation. */
    public static ECKey generate() {
        try {
            return new ECKeyGenerator(Curve.P_256)
                    .keyID(UUID.randomUUID().toString())
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.ES256)
                    .generate();
        } catch (JOSEException e) {
            throw new IllegalStateException("cannot generate an EC key", e);
        }
    }
}
