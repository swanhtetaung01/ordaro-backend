package app.ordaro.sales;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import app.ordaro.support.IntegrationTest;

/** Register binding and 6-digit PIN login with lockout (spec §12 PIN), over HTTP. */
class RegisterPinTest extends IntegrationTest {

    static final String DEVICE = "X-Register-Device";

    @Autowired
    MockMvc mvc;

    String owner;
    String main;
    String credential;
    String registerId;
    String till1;
    String till2;

    @BeforeEach
    void storeWithARegisterAndTwoTills() throws Exception {
        String signup = mvc.perform(json(post("/auth/signup"), """
                {"phone":"%s","password":"correct horse battery","fullName":"Daw Owner","businessName":"Till Shop"}"""
                .formatted(phone()))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        owner = JsonPath.read(signup, "$.accessToken");
        main = JsonPath.read(body(call(get("/locations"), owner)), "$[0].id");

        String bound = body(call(json(post("/registers"), "{\"locationId\":\"" + main + "\",\"label\":\"Till A\"}"),
                owner).andExpect(status().isCreated()));
        credential = JsonPath.read(bound, "$.deviceCredential");
        registerId = JsonPath.read(bound, "$.register.id");

        till1 = memberWithPin("Ma Aye", "482913");
        till2 = memberWithPin("Ko Min", "650274");
    }

    @Test
    void theRegisterListsItsStaffAndAPinOpensAStoreScopedSession() throws Exception {
        mvc.perform(get("/auth/register/staff").header(DEVICE, credential))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].displayName").value("Ko Min"));
        mvc.perform(get("/auth/register/staff")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("register_required"));
        mvc.perform(get("/auth/register/staff").header(DEVICE, "not-a-register"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("register_invalid"));

        String session = body(pin(till1, "482913").andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("REGISTER")));
        String register = JsonPath.read(session, "$.accessToken");

        // a cashier on the register: open a shift, stock something, sell it
        String product = JsonPath.read(body(call(json(post("/products"),
                "{\"name\":\"Oil\",\"unit\":\"PIECE\",\"retailPrice\":1000,\"openingStock\":[{\"locationId\":\"" + main
                        + "\",\"quantity\":5,\"unitCost\":600}]}"), owner).andExpect(status().isCreated())), "$.id");
        String shift = JsonPath.read(body(call(json(post("/shifts"),
                "{\"locationId\":\"" + main + "\",\"openingFloat\":20000}"), register).andExpect(status().isCreated())),
                "$.id");
        String checkout = """
                {"idempotencyKey":"till-a-1","locationId":"%s","channel":"POS","cashierShiftId":"%s",
                 "lines":[{"productId":"%s","quantity":2}],
                 "payments":[{"method":"CASH","amount":2000,"tenderedAmount":5000}]}""".formatted(main, shift, product);
        call(json(post("/sales/checkout"), checkout), register)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replay", "false"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.receiptNumber").value(org.hamcrest.Matchers.startsWith("MAIN-RCP-")))
                .andExpect(jsonPath("$.payments[0].changeAmount").value(3000))
                .andExpect(jsonPath("$.lines[0].unitCost").doesNotExist()); // a cashier does not see cost
        call(json(post("/sales/checkout"), checkout), register)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"));

        // the session is scoped to this store and is not an owner's account session
        String branch = JsonPath.read(body(call(json(post("/locations"),
                "{\"code\":\"B2\",\"name\":\"Branch\",\"type\":\"STORE\"}"), owner)), "$.id");
        call(json(post("/shifts"), "{\"locationId\":\"" + branch + "\",\"openingFloat\":0}"), register)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("location_out_of_scope"));
        call(get("/memberships"), register).andExpect(status().isForbidden());
        call(get("/registers"), register).andExpect(status().isForbidden());
        call(get("/auth/memberships"), register).andExpect(status().isForbidden());
    }

    @Test
    void aPinWithoutARegisterIsRefused() throws Exception {
        mvc.perform(json(post("/auth/pin"), "{\"membershipId\":\"" + till1 + "\",\"pin\":\"482913\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("register_required"));
        mvc.perform(json(post("/auth/pin"), "{\"membershipId\":\"" + till1 + "\",\"pin\":\"482913\"}")
                .header(DEVICE, "forged")).andExpect(status().isUnauthorized());
    }

    /** Five wrong PINs for one person lock that person on this register; others can still log in. */
    @Test
    void fiveWrongPinsLockTheMembershipOnThisRegister() throws Exception {
        for (int i = 0; i < 4; i++) {
            pin(till1, "000000").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("invalid_pin"));
        }
        pin(till2, "650274").andExpect(status().isOk()); // resets the register's streak, not Ma Aye's
        pin(till1, "000000").andExpect(status().isUnauthorized());

        pin(till1, "482913").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("membership_locked"));
        pin(till2, "650274").andExpect(status().isOk());
    }

    /** Five wrong PINs across any people lock the whole register. */
    @Test
    void fiveWrongPinsAcrossPeopleLockTheRegister() throws Exception {
        pin(till1, "000000").andExpect(status().isUnauthorized());
        pin(till2, "000000").andExpect(status().isUnauthorized());
        pin(till1, "111111").andExpect(status().isUnauthorized());
        pin(UUID.randomUUID().toString(), "123456").andExpect(status().isUnauthorized());
        pin(till2, "222222").andExpect(status().isUnauthorized());

        pin(till2, "650274").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("register_locked"));
    }

    @Test
    void revokingARegisterEndsItsSessionsAndItsLogins() throws Exception {
        String session = body(pin(till1, "482913").andExpect(status().isOk()));
        String access = JsonPath.read(session, "$.accessToken");
        String refresh = JsonPath.read(session, "$.refreshToken");

        String renewed = body(mvc.perform(json(post("/auth/refresh"), "{\"refreshToken\":\"" + refresh + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.kind").value("REGISTER")));
        String renewedRefresh = JsonPath.read(renewed, "$.refreshToken");

        call(post("/registers/" + registerId + "/revoke"), owner).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));

        call(get("/products"), access).andExpect(status().isUnauthorized());
        mvc.perform(json(post("/auth/refresh"), "{\"refreshToken\":\"" + renewedRefresh + "\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("register_session_ended"));
        pin(till1, "482913").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("register_invalid"));
    }

    @Test
    void onlyAStoreGetsARegisterAndOnlyAnOwnersAccountSessionBindsOne() throws Exception {
        String warehouse = JsonPath.read(body(call(json(post("/locations"),
                "{\"code\":\"WH\",\"name\":\"Warehouse\",\"type\":\"WAREHOUSE\"}"), owner)), "$.id");
        call(json(post("/registers"), "{\"locationId\":\"" + warehouse + "\",\"label\":\"No\"}"), owner)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("not_a_store"));

        String register = JsonPath.read(body(pin(till1, "482913")), "$.accessToken");
        call(json(post("/registers"), "{\"locationId\":\"" + main + "\",\"label\":\"Sneaky\"}"), register)
                .andExpect(status().isForbidden());
    }

    // ───────────────────────────────────────────────────────────── helpers

    private String memberWithPin(String name, String pin) throws Exception {
        String invite = body(call(json(post("/memberships"),
                "{\"displayName\":\"" + name + "\",\"role\":\"CASHIER\",\"pin\":\"" + pin + "\"}"), owner)
                .andExpect(status().isCreated()));
        return JsonPath.read(invite, "$.membership.id");
    }

    private ResultActions pin(String membershipId, String pin) throws Exception {
        return mvc.perform(json(post("/auth/pin"), "{\"membershipId\":\"" + membershipId + "\",\"pin\":\"" + pin + "\"}")
                .header(DEVICE, credential));
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String token) throws Exception {
        return mvc.perform(request.header("Authorization", "Bearer " + token));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }

    private static String phone() {
        return "+9596" + ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999);
    }
}
