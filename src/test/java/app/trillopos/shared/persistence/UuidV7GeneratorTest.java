package app.trillopos.shared.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.hibernate.generator.EventType;
import org.junit.jupiter.api.Test;

class UuidV7GeneratorTest {

    private final UuidV7Generator generator = new UuidV7Generator();

    @Test
    void generatesVersion7Ids() {
        UUID id = (UUID) generator.generate(null, null, null, EventType.INSERT);
        assertThat(id.version()).isEqualTo(7);
        assertThat(UuidV7Generator.newId().version()).isEqualTo(7);
    }

    @Test
    void keepsAnIdTheCallerAlreadyAssigned() {
        UUID assigned = UUID.randomUUID();
        assertThat(generator.generate(null, null, assigned, EventType.INSERT)).isEqualTo(assigned);
        assertThat(generator.allowAssignedIdentifiers()).isTrue();
    }

    @Test
    void idsAreTimeOrdered() {
        UUID first = UuidV7Generator.newId();
        UUID second = UuidV7Generator.newId();
        assertThat(second.getMostSignificantBits() >>> 16)
                .isGreaterThanOrEqualTo(first.getMostSignificantBits() >>> 16);
    }
}
