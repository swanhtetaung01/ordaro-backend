package app.ordaro.inventory;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.ThreadLocalRandom;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import app.ordaro.support.IntegrationTest;

class InventoryApiTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    record Owner(String token, String mainLocationId) {
    }

    @Test
    void aStockInPostedOverHttpShowsInBalancesAndTheLedger() throws Exception {
        Owner owner = signup();
        String product = createProduct(owner, "Oil", "");

        call(json(post("/stock-documents"), """
                {"type":"STOCK_IN","locationId":"%s","post":true,
                 "lines":[{"productId":"%s","quantity":12,"unitCost":3600}]}""".formatted(owner.mainLocationId(),
                product)), owner.token())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andExpect(jsonPath("$.documentNumber").value(org.hamcrest.Matchers.startsWith("MAIN-GRN-")))
                .andExpect(jsonPath("$.lines[0].unitCost").value(3600));

        call(get("/stock-balances?productId=" + product), owner.token())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].quantity").value(12))
                .andExpect(jsonPath("$[0].averageCost").value(3600));
        call(get("/stock-movements?locationId=" + owner.mainLocationId() + "&productId=" + product), owner.token())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].type").value("STOCK_IN"))
                .andExpect(jsonPath("$[0].balanceAfter").value(12));
        call(get("/inventory/verification"), owner.token())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mismatches.length()").value(0));
    }

    @Test
    void openingStockCanBeGivenWhenAProductIsCreated() throws Exception {
        Owner owner = signup();
        String product = createProduct(owner, "Rice",
                ",\"openingStock\":[{\"locationId\":\"" + owner.mainLocationId() + "\",\"quantity\":24,\"unitCost\":17500}]");

        call(get("/stock-balances?locationId=" + owner.mainLocationId() + "&productId=" + product), owner.token())
                .andExpect(jsonPath("$[0].quantity").value(24))
                .andExpect(jsonPath("$[0].averageCost").value(17500));
    }

    @Test
    void aStockOutBeyondStockIsAConflict() throws Exception {
        Owner owner = signup();
        String product = createProduct(owner, "Oil", "");
        call(json(post("/stock-documents"), """
                {"type":"STOCK_OUT","locationId":"%s","post":true,
                 "lines":[{"productId":"%s","quantity":1,"reason":"DAMAGED"}]}""".formatted(owner.mainLocationId(),
                product)), owner.token())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("insufficient_stock"));
    }

    @Test
    void cashiersSeeQuantitiesButNotCostsAndCannotPost() throws Exception {
        Owner owner = signup();
        String product = createProduct(owner, "Oil", "");
        call(json(post("/stock-documents"), """
                {"type":"STOCK_IN","locationId":"%s","post":true,
                 "lines":[{"productId":"%s","quantity":5,"unitCost":1000}]}""".formatted(owner.mainLocationId(),
                product)), owner.token()).andExpect(status().isCreated());

        String cashier = cashierOf(owner);
        call(get("/stock-balances?productId=" + product), cashier)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].quantity").value(5))
                .andExpect(jsonPath("$[0].averageCost").doesNotExist());
        call(json(post("/stock-documents"), """
                {"type":"STOCK_IN","locationId":"%s","lines":[{"productId":"%s","quantity":1,"unitCost":1}]}"""
                .formatted(owner.mainLocationId(), product)), cashier)
                .andExpect(status().isForbidden());
        call(get("/stock-movements?locationId=" + owner.mainLocationId() + "&productId=" + product), cashier)
                .andExpect(status().isForbidden());
        call(get("/inventory/verification"), cashier).andExpect(status().isForbidden());
    }

    @Test
    void anotherTenantsDocumentIsNotFound() throws Exception {
        Owner alpha = signup();
        Owner beta = signup();
        String product = createProduct(alpha, "Oil", "");
        String body = call(json(post("/stock-documents"), """
                {"type":"STOCK_IN","locationId":"%s",
                 "lines":[{"productId":"%s","quantity":5,"unitCost":1000}]}""".formatted(alpha.mainLocationId(),
                product)), alpha.token()).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString();
        String documentId = JsonPath.read(body, "$.id");

        call(get("/stock-documents/" + documentId), beta.token()).andExpect(status().isNotFound());
        call(post("/stock-documents/" + documentId + "/post"), beta.token()).andExpect(status().isNotFound());
        call(post("/stock-documents/" + documentId + "/post"), alpha.token()).andExpect(status().isOk());
    }

    // ───────────────────────────────────────────────────────────── helpers

    private Owner signup() throws Exception {
        String body = mvc.perform(json(post("/auth/signup"), """
                {"phone":"%s","password":"correct horse battery","fullName":"Daw Test","businessName":"Stock Shop"}"""
                .formatted(randomPhone()))).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString();
        String token = JsonPath.read(body, "$.accessToken");
        String locations = call(get("/locations"), token).andReturn().getResponse().getContentAsString();
        return new Owner(token, JsonPath.read(locations, "$[0].id"));
    }

    private String cashierOf(Owner owner) throws Exception {
        String invite = call(json(post("/memberships"), "{\"displayName\":\"Till\",\"role\":\"CASHIER\"}"),
                owner.token()).andReturn().getResponse().getContentAsString();
        String code = JsonPath.read(invite, "$.inviteCode");
        String home = JsonPath.read(mvc.perform(json(post("/auth/signup"), """
                {"phone":"%s","password":"correct horse battery","fullName":"Ko Till","businessName":"Home"}"""
                .formatted(randomPhone()))).andReturn().getResponse().getContentAsString(), "$.accessToken");
        String accepted = call(json(post("/auth/invitations/accept"), "{\"code\":\"" + code + "\"}"), home)
                .andReturn().getResponse().getContentAsString();
        String organizationId = JsonPath.read(accepted, "$.organizationId");
        return JsonPath.read(call(json(post("/auth/switch"), "{\"organizationId\":\"" + organizationId + "\"}"), home)
                .andReturn().getResponse().getContentAsString(), "$.accessToken");
    }

    private String createProduct(Owner owner, String name, String extra) throws Exception {
        String body = call(json(post("/products"),
                "{\"name\":\"" + name + "\",\"unit\":\"PIECE\",\"retailPrice\":4500" + extra + "}"), owner.token())
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String token) throws Exception {
        return mvc.perform(request.header("Authorization", "Bearer " + token));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String randomPhone() {
        return "+9597" + ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999);
    }
}
