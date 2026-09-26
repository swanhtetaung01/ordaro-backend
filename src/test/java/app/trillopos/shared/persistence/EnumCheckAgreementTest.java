package app.trillopos.shared.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import jakarta.persistence.Column;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.metamodel.EntityType;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import app.trillopos.support.IntegrationTest;
import app.trillopos.support.TestDatabase;

/**
 * Spec §12 Enum columns: every {@code @Enumerated} column has a CHECK named
 * {@code <table>_<column>_check} that allows exactly the Java enum's constants. A constant
 * added without its migration — or a migration without its constant — fails here, not on a
 * live insert. Reads the migrated database, so it stays true as later migrations widen CHECKs.
 */
class EnumCheckAgreementTest extends IntegrationTest {

    private static final Pattern QUOTED = Pattern.compile("'([^']+)'");

    record EnumColumn(String table, String column, Class<?> enumType) {
    }

    @Autowired
    EntityManagerFactory entityManagerFactory;

    @Test
    void everyEnumColumnHasACheckThatMatchesItsJavaEnum() throws SQLException {
        List<EnumColumn> columns = enumColumns();
        // V1 has 9 on entities; phone_verification.channel is on a plain table, outside the scan.
        assertThat(columns).as("enum columns found by the scan").hasSizeGreaterThanOrEqualTo(9);

        try (Connection connection = TestDatabase.connectAsApp()) {
            for (EnumColumn column : columns) {
                String constraint = column.table() + "_" + column.column() + "_check";
                String definition = checkDefinition(connection, column.table(), constraint);
                assertThat(definition).as("CHECK %s", constraint).isNotNull();

                Set<String> allowed = new TreeSet<>();
                Matcher matcher = QUOTED.matcher(definition);
                while (matcher.find()) {
                    allowed.add(matcher.group(1));
                }
                Set<String> constants = Arrays.stream(column.enumType().getEnumConstants())
                        .map(c -> ((Enum<?>) c).name())
                        .collect(Collectors.toCollection(TreeSet::new));
                assertThat(allowed).as("values allowed by %s", constraint).isEqualTo(constants);
            }
        }
    }

    private List<EnumColumn> enumColumns() {
        List<EnumColumn> columns = new ArrayList<>();
        for (EntityType<?> entity : entityManagerFactory.getMetamodel().getEntities()) {
            Class<?> type = entity.getJavaType();
            String table = type.getAnnotation(Table.class).name();
            for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field field : c.getDeclaredFields()) {
                    if (field.isAnnotationPresent(Enumerated.class)) {
                        columns.add(new EnumColumn(table, field.getAnnotation(Column.class).name(), field.getType()));
                    }
                }
            }
        }
        return columns;
    }

    private static String checkDefinition(Connection connection, String table, String constraint)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select pg_get_constraintdef(c.oid)
                from pg_constraint c
                join pg_class t on t.oid = c.conrelid
                join pg_namespace n on n.oid = t.relnamespace
                where n.nspname = 'trillopos' and t.relname = ? and c.conname = ? and c.contype = 'c'
                """)) {
            statement.setString(1, table);
            statement.setString(2, constraint);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }
}
