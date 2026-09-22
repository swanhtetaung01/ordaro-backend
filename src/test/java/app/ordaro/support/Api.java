package app.ordaro.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.ThreadLocalRandom;

import com.jayway.jsonpath.JsonPath;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** HTTP fixtures: a signed-up owner, a cashier invited into the same shop, authenticated calls. */
public final class Api {

    public record Owner(String token, String mainLocationId) {
    }

    private final MockMvc mvc;

    public Api(MockMvc mvc) {
        this.mvc = mvc;
    }

    public Owner signup() throws Exception {
        String body = mvc.perform(json(post("/auth/signup"), """
                {"phone":"%s","password":"correct horse battery","fullName":"Daw Test","businessName":"Api Shop"}"""
                .formatted(randomPhone()))).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString();
        String token = JsonPath.read(body, "$.accessToken");
        String locations = call(get("/locations"), token).andReturn().getResponse().getContentAsString();
        return new Owner(token, JsonPath.read(locations, "$[0].id"));
    }

    /** An account invited as {@code role} and switched into the owner's shop. */
    public String member(Owner owner, String role) throws Exception {
        String invite = call(json(post("/memberships"), "{\"displayName\":\"Staff\",\"role\":\"" + role + "\"}"),
                owner.token()).andReturn().getResponse().getContentAsString();
        String code = JsonPath.read(invite, "$.inviteCode");
        String home = JsonPath.read(mvc.perform(json(post("/auth/signup"), """
                {"phone":"%s","password":"correct horse battery","fullName":"Ko Staff","businessName":"Home"}"""
                .formatted(randomPhone()))).andReturn().getResponse().getContentAsString(), "$.accessToken");
        String accepted = call(json(post("/auth/invitations/accept"), "{\"code\":\"" + code + "\"}"), home)
                .andReturn().getResponse().getContentAsString();
        String organizationId = JsonPath.read(accepted, "$.organizationId");
        return JsonPath.read(call(json(post("/auth/switch"), "{\"organizationId\":\"" + organizationId + "\"}"), home)
                .andReturn().getResponse().getContentAsString(), "$.accessToken");
    }

    /** A product with {@code quantity} on hand at the main location, at {@code unitCost}. */
    public String stockedProduct(Owner owner, String name, int retailPrice, int quantity, int unitCost)
            throws Exception {
        String body = call(json(post("/products"), """
                {"name":"%s","unit":"PIECE","retailPrice":%d,
                 "openingStock":[{"locationId":"%s","quantity":%d,"unitCost":%d}]}"""
                .formatted(name, retailPrice, owner.mainLocationId(), quantity, unitCost)), owner.token())
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    /** A product with no stock yet. */
    public String product(Owner owner, String name, int retailPrice) throws Exception {
        return read(call(json(post("/products"), "{\"name\":\"%s\",\"unit\":\"PIECE\",\"retailPrice\":%d}"
                .formatted(name, retailPrice)), owner.token()).andExpect(status().isCreated()), "$.id");
    }

    public String openShift(Owner owner, int openingFloat) throws Exception {
        return read(call(json(post("/shifts"), "{\"locationId\":\"%s\",\"openingFloat\":%d}"
                .formatted(owner.mainLocationId(), openingFloat)), owner.token())
                .andExpect(status().isCreated()), "$.id");
    }

    public ResultActions call(MockHttpServletRequestBuilder request, String token) throws Exception {
        return mvc.perform(request.header("Authorization", "Bearer " + token));
    }

    public static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    public static String read(ResultActions result, String path) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), path).toString();
    }

    public static String randomPhone() {
        return "+9597" + ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999);
    }
}
