package app.trillopos.pilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import app.trillopos.auth.JwtKeys;
import app.trillopos.auth.JwtProperties;
import app.trillopos.org.Phones;
import app.trillopos.shared.web.ApiException;

/** The pilot's pieces that need no database. */
class PilotUnitTest {

    @Test
    void phonesAreStoredInternationallyWhicheverWayTheyAreTyped() {
        assertThat(Phones.normalize("09 7911 22333")).isEqualTo("+959791122333");
        assertThat(Phones.normalize("09-791-122-333")).isEqualTo("+959791122333");
        assertThat(Phones.normalize("+95 9 791 122 333")).isEqualTo("+959791122333");
        assertThat(Phones.normalize("0066812345678")).isEqualTo("+66812345678");
        assertThat(Phones.normalize(null)).isNull();
        assertThatThrownBy(() -> Phones.normalize("hello")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Phones.normalize("0")).isInstanceOf(ApiException.class);
    }

    /** Every start after the first signs with the same key, so a deploy logs nobody out. */
    @Test
    void theSigningKeyIsCreatedOnceAndKept(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("secrets").resolve("jwt-keys.json");
        JwtProperties properties = new JwtProperties("trillopos", Duration.ofMinutes(15), Duration.ofDays(30),
                file.toString());

        JwtKeys first = new JwtKeys(properties);
        assertThat(file).exists();
        assertThat(Files.readString(file)).contains("\"d\""); // the private part is kept
        JwtKeys second = new JwtKeys(properties);

        assertThat(second.signingKeyId()).isEqualTo(first.signingKeyId());
    }
}
