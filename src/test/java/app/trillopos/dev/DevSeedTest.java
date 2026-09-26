package app.trillopos.dev;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import app.trillopos.auth.AuthService;
import app.trillopos.org.AccountRepository;
import app.trillopos.support.IntegrationTest;

@ActiveProfiles("seed")
@TestPropertySource(properties = "trillopos.seed.phone=+959000000777")
class DevSeedTest extends IntegrationTest {

    @Autowired
    DevSeed seed;

    @Autowired
    AccountRepository accounts;

    @Autowired
    AuthService auth;

    @Test
    void seedsADemoShopOnceAndCanLogIn() {
        assertThat(accounts.existsByPhone("+959000000777")).isTrue();

        ApplicationArguments none = new DefaultApplicationArguments();
        seed.run(none);

        var login = auth.login("+959000000777", "demo-password", "test");
        assertThat(login.memberships()).hasSize(1);
        assertThat(login.tokens().kind()).isEqualTo("USER");
    }
}
