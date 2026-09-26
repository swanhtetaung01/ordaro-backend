package app.trillopos.auth;

import static app.trillopos.support.Api.json;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import app.trillopos.support.Api;
import app.trillopos.support.Api.Owner;
import app.trillopos.support.IntegrationTest;

/** 401 and 403 carry the same problem+json body, with a code, as every other error. */
class AccessProblemTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    Api api;

    @BeforeEach
    void client() {
        api = new Api(mvc);
    }

    @Test
    void anUnauthenticatedRequestSaysSoInTheBody() throws Exception {
        mvc.perform(get("/products"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("unauthenticated"))
                .andExpect(jsonPath("$.title").value("unauthenticated"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.detail").isNotEmpty())
                .andExpect(jsonPath("$.instance").value("/products"));
        mvc.perform(get("/products").header("Authorization", "Bearer not-a-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthenticated"));
    }

    @Test
    void aRoleThatCannotReachAnEndpointGetsAForbiddenProblem() throws Exception {
        Owner owner = api.signup();
        String cashier = api.member(owner, "CASHIER");

        api.call(get("/payables"), cashier)
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("forbidden"))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.instance").value("/payables"));
        api.call(json(post("/registers"), "{\"locationId\":\"" + owner.mainLocationId() + "\",\"label\":\"Till\"}"),
                cashier)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden"));
    }
}
