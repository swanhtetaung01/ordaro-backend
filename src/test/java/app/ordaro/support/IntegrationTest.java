package app.ordaro.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Full application against the embedded PostgreSQL. Flyway migrates as {@code ordaro_owner};
 * the pool connects as {@code ordaro_app}. The membership cache is off so status changes are
 * visible to the very next request.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class IntegrationTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestDatabase::jdbcUrl);
        registry.add("spring.datasource.username", () -> TestDatabase.APP);
        registry.add("spring.datasource.password", () -> TestDatabase.APP_PASSWORD);
        registry.add("spring.flyway.url", TestDatabase::jdbcUrl);
        registry.add("spring.flyway.user", () -> TestDatabase.OWNER);
        registry.add("spring.flyway.password", () -> TestDatabase.OWNER_PASSWORD);
        registry.add("ordaro.tenant.membership-cache-ttl", () -> "0s");
    }
}
