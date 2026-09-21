package app.ordaro.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import app.ordaro.auth.MembershipDirectory.Invite;
import app.ordaro.auth.MembershipDirectory.PickerEntry;
import app.ordaro.auth.TokenService.IssuedTokens;
import app.ordaro.org.Account;
import app.ordaro.org.AccountRepository;
import app.ordaro.org.Location;
import app.ordaro.org.LocationRepository;
import app.ordaro.org.LocationType;
import app.ordaro.org.Membership;
import app.ordaro.org.MembershipRepository;
import app.ordaro.org.MembershipStatus;
import app.ordaro.org.Organization;
import app.ordaro.org.OrganizationRepository;
import app.ordaro.org.Phones;
import app.ordaro.org.Slugs;
import app.ordaro.shared.persistence.UuidV7Generator;
import app.ordaro.shared.tenant.TenantContext;
import app.ordaro.shared.web.ApiException;

/**
 * Sign-up, login, the membership picker, switch, refresh, logout and invitation acceptance
 * (spec §12 Tenant resolution and login).
 *
 * <p>Methods that write tenant rows set {@link TenantContext} <em>before</em> opening their
 * transaction, because Hibernate binds the tenant when the session opens.
 */
@Service
public class AuthService {

    public record SignupCommand(String phone, String password, String fullName, String businessName,
            String deviceLabel) {
    }

    public record LoginResult(IssuedTokens tokens, List<PickerEntry> memberships) {
    }

    private final AccountRepository accounts;
    private final OrganizationRepository organizations;
    private final LocationRepository locations;
    private final MembershipRepository memberships;
    private final RefreshTokenRepository refreshTokens;
    private final MembershipDirectory directory;
    private final MembershipCheck membershipCheck;
    private final TokenService tokens;
    private final PasswordEncoder passwords;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final String timingHash;

    public AuthService(AccountRepository accounts, OrganizationRepository organizations,
            LocationRepository locations, MembershipRepository memberships, RefreshTokenRepository refreshTokens,
            MembershipDirectory directory, MembershipCheck membershipCheck, TokenService tokens,
            PasswordEncoder passwords, PlatformTransactionManager transactionManager, Clock clock) {
        this.accounts = accounts;
        this.organizations = organizations;
        this.locations = locations;
        this.memberships = memberships;
        this.refreshTokens = refreshTokens;
        this.directory = directory;
        this.membershipCheck = membershipCheck;
        this.tokens = tokens;
        this.passwords = passwords;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.timingHash = passwords.encode("ordaro-timing-equaliser");
    }

    /**
     * Account + organization + OWNER membership + default STORE location "Main" + tokens, in one
     * transaction (spec §12). The organization id is chosen first so the tenant is known before
     * the session opens.
     */
    public IssuedTokens signup(SignupCommand command) {
        String phone = Phones.normalize(command.phone());
        UUID organizationId = UuidV7Generator.newId();
        return TenantContext.call(new TenantContext.Current(organizationId, null, null),
                () -> transaction.execute(status -> {
                    if (accounts.existsByPhone(phone)) {
                        throw ApiException.conflict("phone_in_use", "phone in use");
                    }
                    Account account = accounts.save(
                            new Account(phone, passwords.encode(command.password()), command.fullName()));
                    organizations.save(new Organization(organizationId, command.businessName(), uniqueSlug(command)));
                    locations.save(new Location("MAIN", "Main", LocationType.STORE));
                    Membership owner = memberships.save(
                            Membership.owner(account.getId(), command.fullName(), clock.instant()));
                    return tokens.forMembership(account.getId(), snapshot(owner, organizationId), null,
                            command.deviceLabel());
                }));
    }

    /** One ACTIVE membership signs straight in; otherwise a picker token and the list. */
    @Transactional
    public LoginResult login(String phone, String password, String deviceLabel) {
        Account account = accounts.findByPhone(Phones.normalize(phone)).orElse(null);
        if (account == null) {
            passwords.matches(password, timingHash);
            throw invalidCredentials();
        }
        if (!passwords.matches(password, account.getPasswordHash())) {
            throw invalidCredentials();
        }
        if (!account.canLogIn()) {
            throw ApiException.forbidden("account_disabled", "this account cannot log in");
        }
        List<PickerEntry> entries = directory.forAccount(account.getId());
        List<PickerEntry> active = entries.stream().filter(e -> e.status() == MembershipStatus.ACTIVE).toList();
        if (active.size() == 1) {
            MembershipSnapshot membership = directory.find(active.getFirst().membershipId()).orElseThrow();
            return new LoginResult(tokens.forMembership(account.getId(), membership, null, deviceLabel), entries);
        }
        return new LoginResult(tokens.forPicker(account.getId(), null, deviceLabel), entries);
    }

    public List<PickerEntry> memberships(UUID accountId) {
        return directory.forAccount(requireAccount(accountId));
    }

    @Transactional
    public IssuedTokens switchTo(UUID accountId, UUID organizationId, String deviceLabel) {
        Account account = activeAccount(requireAccount(accountId));
        MembershipSnapshot membership = directory.activeFor(account.getId(), organizationId)
                .orElseThrow(() -> ApiException.forbidden("no_active_membership",
                        "no active membership in that organization"));
        return tokens.forMembership(account.getId(), membership, null, deviceLabel);
    }

    /**
     * Rotates the refresh token. A token presented twice revokes its whole family; that
     * revocation must commit even though the call fails, hence {@code noRollbackFor}.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public IssuedTokens refresh(String rawToken) {
        Instant now = clock.instant();
        RefreshToken token = refreshTokens.findByTokenHash(Secrets.sha256Hex(rawToken))
                .orElseThrow(() -> ApiException.unauthorized("refresh_token_invalid", "unknown refresh token"));
        if (token.isRevoked()) {
            refreshTokens.revokeFamily(token.getFamilyId(), now);
            throw ApiException.unauthorized("refresh_token_reused", "refresh token already used; session revoked");
        }
        if (token.isExpired(now)) {
            throw ApiException.unauthorized("refresh_token_expired", "refresh token expired");
        }
        token.markUsed(now);

        Account account = accounts.findById(token.getAccountId()).filter(Account::canLogIn).orElse(null);
        if (account == null) {
            refreshTokens.revokeFamily(token.getFamilyId(), now);
            throw ApiException.unauthorized("account_disabled", "this account cannot log in");
        }
        if (token.getKind() == RefreshTokenKind.PICKER) {
            return tokens.forPicker(account.getId(), token.getFamilyId(), token.getDeviceLabel());
        }
        MembershipSnapshot membership = directory.find(token.getMembershipId())
                .filter(m -> m.isActive() && account.getId().equals(m.accountId()))
                .orElse(null);
        if (membership == null) {
            refreshTokens.revokeFamily(token.getFamilyId(), now);
            throw ApiException.unauthorized("membership_inactive", "the membership is no longer active");
        }
        return tokens.forMembership(account.getId(), membership, token.getFamilyId(), token.getDeviceLabel());
    }

    @Transactional
    public void logout(String rawToken) {
        refreshTokens.findByTokenHash(Secrets.sha256Hex(rawToken))
                .ifPresent(token -> refreshTokens.revokeFamily(token.getFamilyId(), clock.instant()));
    }

    /** Invite-by-code attaches the membership to whichever account enters the code (spec §12). */
    public PickerEntry acceptByCode(UUID accountId, String code) {
        UUID account = requireAccount(accountId);
        Invite invite = directory.findInviteByCodeHash(Secrets.sha256Hex(Secrets.normalizeInviteCode(code)))
                .filter(i -> i.status() == MembershipStatus.INVITED)
                .orElseThrow(() -> ApiException.notFound("invite_not_found", "no invitation with that code"));
        if (invite.expiresAt() != null && !invite.expiresAt().isAfter(clock.instant())) {
            throw ApiException.gone("invite_expired", "the invitation has expired");
        }
        return accept(account, invite.organizationId(), invite.membershipId());
    }

    /** Accept an invitation that reached the picker through a verified phone match. */
    public PickerEntry acceptInvitation(UUID accountId, UUID membershipId) {
        UUID account = requireAccount(accountId);
        MembershipSnapshot invited = directory.find(membershipId)
                .filter(m -> m.status() == MembershipStatus.INVITED && account.equals(m.accountId()))
                .orElseThrow(() -> ApiException.notFound("invite_not_found", "no invitation for this account"));
        return accept(account, invited.organizationId(), membershipId);
    }

    private PickerEntry accept(UUID accountId, UUID organizationId, UUID membershipId) {
        return TenantContext.call(new TenantContext.Current(organizationId, membershipId, accountId),
                () -> transaction.execute(status -> {
                    activeAccount(accountId);
                    Membership membership = memberships.findById(membershipId).orElseThrow();
                    if (memberships.existsByAccountId(accountId) && !accountId.equals(membership.getAccountId())) {
                        throw ApiException.conflict("already_member", "this account is already a member");
                    }
                    membership.accept(accountId, clock.instant());
                    membershipCheck.evict(membershipId);
                    Organization organization = organizations.findById(organizationId).orElseThrow();
                    return new PickerEntry(membershipId, organizationId, organization.getName(),
                            membership.getDisplayName(), membership.getRole(), membership.getStatus());
                }));
    }

    private String uniqueSlug(SignupCommand command) {
        for (int attempt = 0; attempt < 5; attempt++) {
            String slug = Slugs.generate(command.businessName());
            if (!organizations.existsBySlug(slug)) {
                return slug;
            }
        }
        throw ApiException.conflict("slug_unavailable", "could not generate a unique link; try again");
    }

    private Account activeAccount(UUID accountId) {
        return accounts.findById(accountId).filter(Account::canLogIn)
                .orElseThrow(() -> ApiException.forbidden("account_disabled", "this account cannot log in"));
    }

    private static UUID requireAccount(UUID accountId) {
        if (accountId == null) {
            throw ApiException.forbidden("account_required", "this action needs an account login");
        }
        return accountId;
    }

    private static MembershipSnapshot snapshot(Membership membership, UUID organizationId) {
        return new MembershipSnapshot(membership.getId(), organizationId, membership.getAccountId(),
                membership.getRole(), membership.getStatus(), membership.getLocationId());
    }

    private static ApiException invalidCredentials() {
        return ApiException.unauthorized("invalid_credentials", "phone or password is wrong");
    }
}
