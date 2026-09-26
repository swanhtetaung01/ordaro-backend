package app.trillopos.shared.persistence;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.hibernate.annotations.IdGeneratorType;

/**
 * UUID v7 primary key that keeps a pre-assigned value.
 *
 * <p>Hibernate's own {@code @UuidGenerator} does not allow assigned identifiers
 * ({@code Generator.allowAssignedIdentifiers()} is false in ORM 7.4), so an id chosen by an
 * offline client — or by sign-up, which needs the organization id before its transaction
 * opens — would be overwritten. This generator uses Hibernate's own v7 strategy and keeps
 * any id already set.
 */
@IdGeneratorType(UuidV7Generator.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface UuidV7 {
}
