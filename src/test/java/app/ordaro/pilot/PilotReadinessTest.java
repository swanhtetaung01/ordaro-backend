package app.ordaro.pilot;

import static app.ordaro.support.Api.json;
import static app.ordaro.support.Api.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.ThreadLocalRandom;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import app.ordaro.admin.AdminService;
import app.ordaro.support.Api;
import app.ordaro.support.Api.Owner;
import app.ordaro.support.IntegrationTest;

/** What one real shop on the open internet needs (2026-09-23): the pieces that were missing. */
class PilotReadinessTest extends IntegrationTest {

    private static final String PASSWORD = "correct horse battery";

    @Autowired
    MockMvc mvc;

    @Autowired
    AdminService admin;

    Api api;

    @BeforeEach
    void client() {
        api = new Api(mvc);
    }

    // ───────────────────────────────────────────────────────────── passwords

    @Test
    void tenWrongPasswordsLockTheLoginAndARightOneBeforeThatResetsTheCount() throws Exception {
        String phone = signup();
        for (int i = 0; i < 9; i++) {
            login(phone, "wrong password " + i).andExpect(status().isUnauthorized());
        }
        login(phone, PASSWORD).andExpect(status().isOk()); // nine then a right one: the count starts again
        for (int i = 0; i < 10; i++) {
            login(phone, "wrong password " + i).andExpect(status().isUnauthorized());
        }
        login(phone, PASSWORD)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("login_locked"));

        admin.unlock(phone);
        login(phone, PASSWORD).andExpect(status().isOk());
    }

    @Test
    void changingThePasswordEndsTheOtherSessionsAndKeepsThisOne() throws Exception {
        String phone = signup();
        String login = body(login(phone, PASSWORD));
        String token = JsonPath.read(login, "$.tokens.accessToken");
        String otherDevice = JsonPath.read(body(login(phone, PASSWORD)), "$.tokens.refreshToken");

        api.call(json(post("/auth/password"), """
                {"currentPassword":"not it","newPassword":"a brand new one"}"""), token)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("wrong_password"));
        String fresh = body(api.call(json(post("/auth/password"), """
                {"currentPassword":"%s","newPassword":"a brand new one"}""".formatted(PASSWORD)), token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("USER")));

        mvc.perform(json(post("/auth/refresh"), "{\"refreshToken\":\"" + otherDevice + "\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(json(post("/auth/refresh"),
                "{\"refreshToken\":\"" + JsonPath.read(fresh, "$.refreshToken") + "\"}"))
                .andExpect(status().isOk());
        login(phone, PASSWORD).andExpect(status().isUnauthorized());
        login(phone, "a brand new one").andExpect(status().isOk());
    }

    @Test
    void theOperatorCanResetAForgottenPassword() throws Exception {
        String phone = signup();
        String refresh = JsonPath.read(body(login(phone, PASSWORD)), "$.tokens.refreshToken");

        String temporary = admin.resetPassword(phone);

        assertThat(temporary).hasSize(12).doesNotContain("0", "O", "1", "l", "I");
        login(phone, PASSWORD).andExpect(status().isUnauthorized());
        login(phone, temporary).andExpect(status().isOk());
        mvc.perform(json(post("/auth/refresh"), "{\"refreshToken\":\"" + refresh + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aPhoneTypedTheLocalWayIsTheSameAccount() throws Exception {
        int digits = ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999);
        mvc.perform(json(post("/auth/signup"), """
                {"phone":"09 77%d","password":"%s","fullName":"Daw Local","businessName":"Local Shop"}"""
                .formatted(digits, PASSWORD))).andExpect(status().isCreated());

        login("+95977" + digits, PASSWORD).andExpect(status().isOk());
        login("0977" + digits, PASSWORD).andExpect(status().isOk());
        login("0095977" + digits, PASSWORD).andExpect(status().isOk());
    }

    // ───────────────────────────────────────────────────────────── the register PIN

    @Test
    void anOwnerResetsAForgottenPinAndOnlyAnOwnerMay() throws Exception {
        Owner owner = api.signup();
        String cashierId = read(api.call(json(post("/memberships"), """
                {"displayName":"Ma Aye","role":"CASHIER","pin":"111111"}"""), owner.token())
                .andExpect(status().isCreated()), "$.membership.id");
        String device = read(api.call(json(post("/registers"), """
                {"locationId":"%s","label":"Till"}""".formatted(owner.mainLocationId())), owner.token()), "$.deviceCredential");

        api.call(json(put("/memberships/" + cashierId + "/pin"), "{\"pin\":\"12345\"}"), owner.token())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_pin"));
        api.call(json(put("/memberships/" + cashierId + "/pin"), "{\"pin\":\"482913\"}"), owner.token())
                .andExpect(status().isOk());

        mvc.perform(json(post("/auth/pin"), "{\"membershipId\":\"" + cashierId + "\",\"pin\":\"111111\"}")
                .header("X-Register-Device", device)).andExpect(status().isUnauthorized());
        String register = JsonPath.read(body(mvc.perform(json(post("/auth/pin"),
                "{\"membershipId\":\"" + cashierId + "\",\"pin\":\"482913\"}").header("X-Register-Device", device))
                .andExpect(status().isOk())), "$.accessToken");

        String manager = api.member(owner, "STOCK_MANAGER");
        api.call(json(put("/memberships/" + cashierId + "/pin"), "{\"pin\":\"000000\"}"), manager)
                .andExpect(status().isForbidden());
        api.call(json(put("/memberships/" + cashierId + "/pin"), "{\"pin\":\"000000\"}"), register)
                .andExpect(status().isForbidden());
    }

    // ───────────────────────────────────────────────────────────── an online shop

    /** Cash on delivery is a credit sale to the buyer until the courier pays; new buyers need terms. */
    @Test
    void newCustomersStartOnTheShopsDefaultCreditTerms() throws Exception {
        Owner owner = api.signup();
        String cashier = api.member(owner, "CASHIER");
        api.call(json(patch("/organization"), "{\"defaultCreditLimit\":200000,\"defaultCreditTermDays\":14}"),
                cashier).andExpect(status().isForbidden());
        api.call(json(patch("/organization"), "{\"defaultCreditLimit\":200000,\"defaultCreditTermDays\":14}"),
                owner.token())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultCreditLimit").value(200000))
                .andExpect(jsonPath("$.defaultCreditTermDays").value(14));

        api.call(json(post("/customers"), "{\"name\":\"Ko Buyer\",\"phone\":\"09 7911 2233\"}"), cashier)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.phone").value("+95979112233"))
                .andExpect(jsonPath("$.creditLimit").value(200000))
                .andExpect(jsonPath("$.creditTermDays").value(14))
                .andExpect(jsonPath("$.availableCredit").value(200000));
    }

    @Test
    void anOnlineShopsProductsAreSoldOnlineAndAnOnlineOrderNeedsNoShift() throws Exception {
        Owner owner = api.signup();
        api.call(json(patch("/organization"), "{\"businessType\":\"ONLINE\",\"defaultCreditLimit\":100000}"),
                owner.token()).andExpect(status().isOk());
        String dress = read(api.call(json(post("/products"), """
                {"name":"Longyi","unit":"PIECE","retailPrice":25000,
                 "openingStock":[{"locationId":"%s","quantity":5,"unitCost":15000}]}"""
                .formatted(owner.mainLocationId())), owner.token())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sellOnline").value(true)), "$.id");
        String buyer = read(api.call(json(post("/customers"), "{\"name\":\"Ma Online\"}"), owner.token()), "$.id");

        // prepaid by wallet
        api.call(json(post("/sales/checkout"), """
                {"idempotencyKey":"web-1","locationId":"%s","channel":"ONLINE","customerId":"%s",
                 "lines":[{"productId":"%s","quantity":1}],
                 "payments":[{"method":"KBZ_PAY","amount":25000,"referenceNo":"KBZ-555"}]}"""
                .formatted(owner.mainLocationId(), buyer, dress)), owner.token())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.channel").value("ONLINE"))
                .andExpect(jsonPath("$.cashierShiftId").doesNotExist());
        // cash on delivery: a credit sale until the courier pays
        api.call(json(post("/sales/checkout"), """
                {"idempotencyKey":"web-2","locationId":"%s","channel":"ONLINE","customerId":"%s",
                 "lines":[{"productId":"%s","quantity":2}],"payments":[{"method":"CREDIT","amount":50000}]}"""
                .formatted(owner.mainLocationId(), buyer, dress)), owner.token())
                .andExpect(status().isCreated());
        api.call(get("/receivables?customerId=" + buyer), owner.token())
                .andExpect(jsonPath("$[0].outstandingAmount").value(50000));
        api.call(get("/stock-balances?productId=" + dress), owner.token())
                .andExpect(jsonPath("$[0].quantity").value(2));
    }

    // ───────────────────────────────────────────────────────────── helpers

    private String signup() throws Exception {
        String phone = Api.randomPhone();
        mvc.perform(json(post("/auth/signup"), """
                {"phone":"%s","password":"%s","fullName":"Daw Pilot","businessName":"Pilot Shop"}"""
                .formatted(phone, PASSWORD))).andExpect(status().isCreated());
        return phone;
    }

    private ResultActions login(String phone, String password) throws Exception {
        return mvc.perform(json(post("/auth/login"), """
                {"phone":"%s","password":"%s"}""".formatted(phone, password)));
    }

    private static String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }
}
