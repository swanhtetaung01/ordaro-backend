package app.ordaro.shared.persistence;

import java.util.EnumSet;
import java.util.UUID;

import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.generator.BeforeExecutionGenerator;
import org.hibernate.generator.EventType;
import org.hibernate.id.uuid.UuidVersion7Strategy;

public class UuidV7Generator implements BeforeExecutionGenerator {

    @Override
    public Object generate(SharedSessionContractImplementor session, Object owner, Object currentValue,
            EventType eventType) {
        return currentValue != null ? currentValue : newId();
    }

    @Override
    public EnumSet<EventType> getEventTypes() {
        return EnumSet.of(EventType.INSERT);
    }

    @Override
    public boolean allowAssignedIdentifiers() {
        return true;
    }

    /** A new time-ordered id, for code that must know an id before the row is persisted. */
    public static UUID newId() {
        return UuidVersion7Strategy.INSTANCE.generateUuid(null);
    }
}
