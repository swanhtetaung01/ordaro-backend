package app.ordaro.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import app.ordaro.support.IntegrationTest;
import app.ordaro.support.TestDatabase;

/** The step-1 tests of spec §12 Tenant resolution — V1 tables and tests. */
class AuthFlowTest extends IntegrationTest {

    private static final String PASSWORD = "correct horse battery";

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtEncoder jwtEncoder;

    @Autowired
    JwtKeys jwtKeys;

    /** A signed-up owner: phone, tokens, organization and membership ids. */
    record Session(String phone, String accessToken, String refreshToken, UUID organizationId, UUID membershipId) {
    }

    @Test
    void signupCreatesAccountOrganizationOwnerAndStoreInOneGo() throws Exception {
        Session owner = signup("Golden Rice Shop");

        call(get("/locations"), owner.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].code").value("MAIN"))
                .andExpect(jsonPath("$[0].type").value("STORE"));
        call(get("/memberships"), owner.accessToken())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].role").value("OWNER"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));
        call(get("/organization"), owner.accessToken())
                .andExpect(jsonPath("$.currencyCode").value("MMK"))
                .andExpect(jsonPath("$.timezone").value("Asia/Yangon"));
    }

    @Test
    void signingUpTwiceWithOnePhoneIsPhoneInUse() throws Exception {
        Session owner = signup("First Shop");
        mvc.perform(json(post("/auth/signup"), signupBody(owner.phone(), "Second Shop")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("phone_in_use"));
    }

    @Test
    void loginWithOneActiveMembershipSignsStraightIn() throws Exception {
        Session owner = signup("Solo Shop");
        login(owner.phone())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokens.kind").value("USER"))
                .andExpect(jsonPath("$.memberships.length()").value(1));
    }

    @Test
    void anAccountInTwoOrganizationsGetsThePickerAndCannotReadAcrossTenants() throws Exception {
        Session alpha = signup("Alpha Mart");
        Session beta = signup("Beta Store");
        String code = inviteByCode(alpha, "CASHIER");
        call(json(post("/auth/invitations/accept"), "{\"code\":\"" + code + "\"}"), beta.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        String betaProduct = createProduct(beta, "Beta only");

        String loginResponse = login(beta.phone())
                .andExpect(jsonPath("$.tokens.kind").value("PICKER"))
                .andExpect(jsonPath("$.memberships.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        String picker = JsonPath.read(loginResponse, "$.tokens.accessToken");

        call(get("/auth/memberships"), picker).andExpect(jsonPath("$.length()").value(2));
        call(get("/products"), picker).andExpect(status().isForbidden());

        String alphaToken = accessToken(call(json(post("/auth/switch"),
                "{\"organizationId\":\"" + alpha.organizationId() + "\"}"), picker).andExpect(status().isOk()));

        call(get("/products/" + betaProduct), alphaToken).andExpect(status().isNotFound());
        String alphaList = call(get("/products"), alphaToken).andReturn().getResponse().getContentAsString();
        assertThat(alphaList).doesNotContain(betaProduct);
        call(get("/memberships"), alphaToken).andExpect(status().isForbidden());
    }

    @Test
    void aTokenNamingAnotherTenantsMembershipIsRejected() throws Exception {
        Session alpha = signup("Alpha Forge");
        Session beta = signup("Beta Forge");

        String forged = sign(alpha.phone(), beta.organizationId(), alpha.membershipId());
        call(get("/locations"), forged)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("membership_invalid"));
    }

    @Test
    void aRemovedMembershipLosesAccessRefreshAndSwitch() throws Exception {
        Session owner = signup("Removal Shop");
        Session staff = signup("Staff Home");
        String code = inviteByCode(owner, "STOCK_MANAGER");
        String accepted = call(json(post("/auth/invitations/accept"), "{\"code\":\"" + code + "\"}"),
                staff.accessToken()).andReturn().getResponse().getContentAsString();
        String staffMembership = JsonPath.read(accepted, "$.membershipId");

        String switched = call(json(post("/auth/switch"), "{\"organizationId\":\"" + owner.organizationId() + "\"}"),
                staff.accessToken()).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String staffAccess = JsonPath.read(switched, "$.accessToken");
        String staffRefresh = JsonPath.read(switched, "$.refreshToken");
        call(get("/products"), staffAccess).andExpect(status().isOk());

        call(json(patch("/memberships/" + staffMembership), "{\"status\":\"REMOVED\"}"), owner.accessToken())
                .andExpect(status().isOk());

        call(get("/products"), staffAccess).andExpect(status().isUnauthorized());
        refresh(staffRefresh).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("membership_inactive"));
        call(json(post("/auth/switch"), "{\"organizationId\":\"" + owner.organizationId() + "\"}"),
                staff.accessToken()).andExpect(status().isForbidden());
    }

    @Test
    void refreshRotatesAndReusingATokenRevokesTheFamily() throws Exception {
        Session owner = signup("Rotation Shop");

        String rotated = refresh(owner.refreshToken()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String second = JsonPath.read(rotated, "$.refreshToken");
        assertThat(second).isNotEqualTo(owner.refreshToken());

        refresh(owner.refreshToken()).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("refresh_token_reused"));
        refresh(second).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesTheRefreshToken() throws Exception {
        Session owner = signup("Logout Shop");
        mvc.perform(json(post("/auth/logout"), "{\"refreshToken\":\"" + owner.refreshToken() + "\"}"))
                .andExpect(status().isNoContent());
        refresh(owner.refreshToken()).andExpect(status().isUnauthorized());
    }

    @Test
    void anExpiredInviteCodeIsRefused() throws Exception {
        Session owner = signup("Expiry Shop");
        Session other = signup("Other Shop");
        String code = inviteByCode(owner, "CASHIER");
        expireInvites(owner.organizationId());

        call(json(post("/auth/invitations/accept"), "{\"code\":\"" + code + "\"}"), other.accessToken())
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("invite_expired"));
    }

    @Test
    void aVerifiedPhoneInviteAppearsInThePickerAndCanBeAccepted() throws Exception {
        Session owner = signup("Picker Shop");
        Session invitee = signup("Invitee Home");
        markPhoneVerified(invitee.phone());

        String invite = call(json(post("/memberships"),
                "{\"displayName\":\"Ko Aung\",\"role\":\"CASHIER\",\"phone\":\"" + invitee.phone() + "\"}"),
                owner.accessToken())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.inviteCode").doesNotExist())
                .andExpect(jsonPath("$.membership.status").value("INVITED"))
                .andReturn().getResponse().getContentAsString();
        String membershipId = JsonPath.read(invite, "$.membership.id");

        String picker = call(get("/auth/memberships"), invitee.accessToken())
                .andReturn().getResponse().getContentAsString();
        List<String> invited = JsonPath.read(picker, "$[?(@.status == 'INVITED')].membershipId");
        assertThat(invited).containsExactly(membershipId);

        call(post("/auth/invitations/" + membershipId + "/accept"), invitee.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void anUnverifiedPhoneGetsACodeInstead() throws Exception {
        Session owner = signup("Code Shop");
        Session invitee = signup("Unverified Home");
        call(json(post("/memberships"),
                "{\"displayName\":\"Ma Hla\",\"role\":\"CASHIER\",\"phone\":\"" + invitee.phone() + "\"}"),
                owner.accessToken())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.inviteCode").isString())
                .andExpect(jsonPath("$.membership.accountId").doesNotExist());
    }

    @Test
    void aRegisterOnlyMembershipIsActiveWithNoAccount() throws Exception {
        Session owner = signup("Register Shop");
        call(json(post("/memberships"), "{\"displayName\":\"Till 1\",\"role\":\"CASHIER\",\"pin\":\"482913\"}"),
                owner.accessToken())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.membership.status").value("ACTIVE"))
                .andExpect(jsonPath("$.membership.accountId").doesNotExist())
                .andExpect(jsonPath("$.inviteCode").doesNotExist());
        call(json(post("/memberships"), "{\"displayName\":\"Till 2\",\"role\":\"CASHIER\",\"pin\":\"1234\"}"),
                owner.accessToken())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_pin"));
    }

    @Test
    void aCashierCannotEditTheCatalog() throws Exception {
        Session owner = signup("Roles Shop");
        Session cashier = signup("Cashier Home");
        String code = inviteByCode(owner, "CASHIER");
        call(json(post("/auth/invitations/accept"), "{\"code\":\"" + code + "\"}"), cashier.accessToken())
                .andExpect(status().isOk());
        String cashierToken = accessToken(call(json(post("/auth/switch"),
                "{\"organizationId\":\"" + owner.organizationId() + "\"}"), cashier.accessToken()));

        call(get("/products"), cashierToken).andExpect(status().isOk());
        call(json(post("/products"), "{\"name\":\"Oil\",\"unit\":\"PIECE\",\"retailPrice\":1000}"), cashierToken)
                .andExpect(status().isForbidden());
    }

    @Test
    void theJwksEndpointPublishesOnlyPublicKeys() throws Exception {
        String jwks = mvc.perform(get("/.well-known/jwks.json")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(jwks, "$.keys[*].kid")).contains(jwtKeys.signingKeyId());
        assertThat(jwks).doesNotContain("\"d\"");
    }

    // ───────────────────────────────────────────────────────────── helpers

    private Session signup(String businessName) throws Exception {
        String phone = randomPhone();
        String body = mvc.perform(json(post("/auth/signup"), signupBody(phone, businessName)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("USER"))
                .andReturn().getResponse().getContentAsString();
        String access = JsonPath.read(body, "$.accessToken");
        String refresh = JsonPath.read(body, "$.refreshToken");
        String memberships = call(get("/memberships"), access).andReturn().getResponse().getContentAsString();
        String organization = call(get("/organization"), access).andReturn().getResponse().getContentAsString();
        return new Session(phone, access, refresh, UUID.fromString(JsonPath.read(organization, "$.id")),
                UUID.fromString(JsonPath.read(memberships, "$[0].id")));
    }

    private String inviteByCode(Session owner, String role) throws Exception {
        String body = call(json(post("/memberships"), "{\"displayName\":\"Staff\",\"role\":\"" + role + "\"}"),
                owner.accessToken())
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.inviteCode");
    }

    private String createProduct(Session owner, String name) throws Exception {
        String body = call(json(post("/products"),
                "{\"name\":\"" + name + "\",\"unit\":\"PIECE\",\"retailPrice\":2500,\"barcodes\":[\""
                        + UUID.randomUUID().toString().substring(0, 12) + "\"]}"),
                owner.accessToken())
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private ResultActions login(String phone) throws Exception {
        return mvc.perform(json(post("/auth/login"),
                "{\"phone\":\"" + phone + "\",\"password\":\"" + PASSWORD + "\"}"));
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mvc.perform(json(post("/auth/refresh"), "{\"refreshToken\":\"" + refreshToken + "\"}"));
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String accessToken) throws Exception {
        return mvc.perform(request.header("Authorization", "Bearer " + accessToken));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String accessToken(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.accessToken");
    }

    private static String signupBody(String phone, String businessName) {
        return "{\"phone\":\"" + phone + "\",\"password\":\"" + PASSWORD + "\",\"fullName\":\"Daw Test\","
                + "\"businessName\":\"" + businessName + "\"}";
    }

    private static String randomPhone() {
        return "+9599" + ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999);
    }

    /** A validly signed USER token whose org and mem do not belong together. */
    private String sign(String phone, UUID organizationId, UUID membershipId) throws SQLException {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("ordaro")
                .subject(accountIdFor(phone).toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(600))
                .id(UUID.randomUUID().toString())
                .claim(TokenService.KIND, "USER")
                .claim(TokenService.ORG, organizationId.toString())
                .claim(TokenService.MEM, membershipId.toString())
                .claim(TokenService.ROLE, "OWNER")
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.ES256).keyId(jwtKeys.signingKeyId()).type("JWT").build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private static UUID accountIdFor(String phone) throws SQLException {
        try (Connection owner = TestDatabase.connectAsOwner();
                PreparedStatement s = owner.prepareStatement("select id from account where phone = ?")) {
            s.setString(1, phone);
            var rs = s.executeQuery();
            rs.next();
            return rs.getObject(1, UUID.class);
        }
    }

    private static void markPhoneVerified(String phone) throws SQLException {
        try (Connection owner = TestDatabase.connectAsOwner();
                PreparedStatement s = owner.prepareStatement(
                        "update account set phone_verified_at = now() where phone = ?")) {
            s.setString(1, phone);
            assertThat(s.executeUpdate()).isEqualTo(1);
        }
    }

    private static void expireInvites(UUID organizationId) throws SQLException {
        try (Connection owner = TestDatabase.connectAsOwner();
                PreparedStatement s = owner.prepareStatement("""
                        update membership set invite_expires_at = now() - interval '1 day'
                        where organization_id = ? and status = 'INVITED'""")) {
            s.setObject(1, organizationId);
            assertThat(s.executeUpdate()).isPositive();
        }
    }
}
