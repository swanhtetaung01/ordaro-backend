package app.trillopos.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

/**
 * One real PostgreSQL per test JVM, without Docker. It is bootstrapped with the production
 * {@code db/bootstrap.sql}, so every test runs against the same role split as production:
 * Flyway as {@code trillopos_owner}, the application as {@code trillopos_app}.
 */
public final class TestDatabase {

    public static final String OWNER = "trillopos_owner";
    public static final String OWNER_PASSWORD = "owner-test-password";
    public static final String APP = "trillopos_app";
    public static final String APP_PASSWORD = "app-test-password";

    private static EmbeddedPostgres postgres;

    private TestDatabase() {
    }

    public static synchronized void start() {
        if (postgres != null) {
            return;
        }
        try {
            postgres = EmbeddedPostgres.builder().start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    postgres.close();
                } catch (IOException ignored) {
                    // the JVM is exiting
                }
            }));
            bootstrap();
        } catch (IOException | SQLException e) {
            throw new IllegalStateException("cannot start the embedded PostgreSQL", e);
        }
    }

    /** No user in the URL: pgjdbc would let a URL parameter override the configured username. */
    public static String jdbcUrl() {
        start();
        return "jdbc:postgresql://localhost:" + postgres.getPort() + "/postgres";
    }

    public static Connection connect(String user, String password) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), user, password);
    }

    public static Connection connectAsOwner() throws SQLException {
        return connect(OWNER, OWNER_PASSWORD);
    }

    public static Connection connectAsApp() throws SQLException {
        return connect(APP, APP_PASSWORD);
    }

    /** Runs db/bootstrap.sql as the superuser, with psql variables substituted. */
    private static void bootstrap() throws SQLException {
        String script;
        try {
            script = Files.readString(Path.of("db", "bootstrap.sql"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        script = script.replace(":'owner_password'", "'" + OWNER_PASSWORD + "'")
                .replace(":'app_password'", "'" + APP_PASSWORD + "'");
        String withoutComments = script.lines()
                .filter(line -> !line.strip().startsWith("--") && !line.strip().startsWith("\\"))
                .collect(Collectors.joining("\n"));
        List<String> statements = Arrays.stream(withoutComments.split(";"))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
        try (Connection connection = postgres.getPostgresDatabase().getConnection();
                Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }
}
