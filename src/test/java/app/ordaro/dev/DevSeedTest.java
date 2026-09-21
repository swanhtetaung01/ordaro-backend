package app.ordaro.dev;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import app.ordaro.auth.AuthService;
import app.ordaro.org.AccountRepository;
import app.ordaro.support.IntegrationTest;

@ActiveProfiles("seed")
@TestPropertySource(properties = "ordaro.seed.phone=+959000000777")
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
