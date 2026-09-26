package app.trillopos.auth;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import app.trillopos.auth.AuthService.LoginResult;
import app.trillopos.auth.AuthService.SignupCommand;
import app.trillopos.auth.MembershipDirectory.PickerEntry;
import app.trillopos.auth.TokenService.IssuedTokens;
import app.trillopos.shared.tenant.TenantContext;

@RestController
@RequestMapping("/auth")
class AuthController {

    record SignupRequest(@NotBlank String phone, @NotBlank @Size(min = 8, max = 128) String password,
            @NotBlank @Size(max = 200) String fullName, @NotBlank @Size(max = 200) String businessName,
            @Size(max = 100) String deviceLabel, @Size(max = 100) String signupCode) {
    }

    record LoginRequest(@NotBlank String phone, @NotBlank String password, @Size(max = 100) String deviceLabel) {
    }

    record RefreshRequest(@NotBlank String refreshToken) {
    }

    record SwitchRequest(@NotNull UUID organizationId, @Size(max = 100) String deviceLabel) {
    }

    record AcceptCodeRequest(@NotBlank @Size(max = 16) String code) {
    }

    record PinLoginRequest(@NotNull UUID membershipId, @NotBlank @Size(max = 6) String pin) {
    }

    record PasswordChange(@NotBlank String currentPassword, @NotBlank @Size(min = 8, max = 128) String newPassword,
            @Size(max = 100) String deviceLabel) {
    }

    /** The register's device credential travels in this header, never in a URL. */
    static final String DEVICE_HEADER = "X-Register-Device";

    private final AuthService auth;
    private final PinLoginService pinLogin;

    AuthController(AuthService auth, PinLoginService pinLogin) {
        this.auth = auth;
        this.pinLogin = pinLogin;
    }

    /** The register's login screen: who may log in here. */
    @GetMapping("/register/staff")
    List<PinLoginService.StaffEntry> registerStaff(@RequestHeader(value = DEVICE_HEADER, required = false) String device) {
        return pinLogin.staff(device);
    }

    /** Name + 6-digit PIN on a bound register: a REGISTER session scoped to its store. */
    @PostMapping("/pin")
    IssuedTokens pinLogin(@RequestHeader(value = DEVICE_HEADER, required = false) String device,
            @Valid @RequestBody PinLoginRequest request) {
        return pinLogin.login(device, request.membershipId(), request.pin());
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    IssuedTokens signup(@Valid @RequestBody SignupRequest request) {
        return auth.signup(new SignupCommand(request.phone(), request.password(), request.fullName(),
                request.businessName(), request.deviceLabel(), request.signupCode()));
    }

    @PostMapping("/login")
    LoginResult login(@Valid @RequestBody LoginRequest request) {
        return auth.login(request.phone(), request.password(), request.deviceLabel());
    }

    @PostMapping("/refresh")
    IssuedTokens refresh(@Valid @RequestBody RefreshRequest request) {
        return auth.refresh(request.refreshToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(@Valid @RequestBody RefreshRequest request) {
        auth.logout(request.refreshToken());
    }

    /** The login picker: this account's ACTIVE and INVITED memberships in every organization. */
    @GetMapping("/memberships")
    List<PickerEntry> memberships() {
        return auth.memberships(currentAccount());
    }

    @PostMapping("/switch")
    IssuedTokens switchOrganization(@Valid @RequestBody SwitchRequest request) {
        return auth.switchTo(currentAccount(), request.organizationId(), request.deviceLabel());
    }

    /** Change your own password; other sessions end, this one gets fresh tokens. */
    @PostMapping("/password")
    IssuedTokens changePassword(@Valid @RequestBody PasswordChange request) {
        return auth.changePassword(currentAccount(), TenantContext.membershipId().orElse(null),
                request.currentPassword(), request.newPassword(), request.deviceLabel());
    }

    @PostMapping("/invitations/accept")
    PickerEntry acceptByCode(@Valid @RequestBody AcceptCodeRequest request) {
        return auth.acceptByCode(currentAccount(), request.code());
    }

    @PostMapping("/invitations/{membershipId}/accept")
    PickerEntry acceptInvitation(@PathVariable UUID membershipId) {
        return auth.acceptInvitation(currentAccount(), membershipId);
    }

    private static UUID currentAccount() {
        return TenantContext.accountId().orElse(null);
    }
}
