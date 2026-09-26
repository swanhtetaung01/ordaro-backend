package app.trillopos.pilot;

import static app.trillopos.support.Api.json;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import app.trillopos.support.Api;
import app.trillopos.support.IntegrationTest;

/** A server with {@code trillopos.signup.code} set only lets people with the code create a shop. */
@TestPropertySource(properties = "trillopos.signup.code=yangon-pilot")
class SignupCodeTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void onlySomeoneWithTheCodeCanCreateAShop() throws Exception {
        mvc.perform(json(post("/auth/signup"), body(null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("signup_code_required"));
        mvc.perform(json(post("/auth/signup"), body("\"guess\"")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("signup_code_invalid"));
        mvc.perform(json(post("/auth/signup"), body("\" yangon-pilot \"")))
                .andExpect(status().isCreated());
    }

    private static String body(String code) {
        return """
                {"phone":"%s","password":"correct horse battery","fullName":"Daw Code","businessName":"Code Shop",
                 "signupCode":%s}""".formatted(Api.randomPhone(), code);
    }
}
