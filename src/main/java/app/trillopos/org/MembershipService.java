package app.trillopos.org;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.trillopos.auth.MembershipCheck;
import app.trillopos.auth.PinHasher;
import app.trillopos.auth.Secrets;
import app.trillopos.shared.tenant.TenantContext;
import app.trillopos.shared.web.ApiException;

/**
 * Invitations attach a membership, never create an account (spec §12):
 * <ul>
 * <li>phone omitted + PIN → a register-only ACTIVE membership;</li>
 * <li>phone of a <em>verified</em> account → INVITED, shown in that account's picker;</li>
 * <li>otherwise → INVITED with a 6-character code valid for 7 days (the phone is a hint).</li>
 * </ul>
 */
@Service
public class MembershipService {

    static final Duration INVITE_TTL = Duration.ofDays(7);

    public record InviteCommand(String displayName, MembershipRole role, UUID locationId, String phone, String pin) {
    }

    /** {@code inviteCode} is returned once, at creation; only its hash is stored. */
    public record InviteResult(Membership membership, String inviteCode) {
    }

    private final MembershipRepository memberships;
    private final LocationRepository locations;
    private final AccountRepository accounts;
    private final PinHasher pins;
    private final MembershipCheck membershipCheck;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public MembershipService(MembershipRepository memberships, LocationRepository locations,
            AccountRepository accounts, PinHasher pins, MembershipCheck membershipCheck, JdbcTemplate jdbc,
            Clock clock) {
        this.memberships = memberships;
        this.locations = locations;
        this.accounts = accounts;
        this.pins = pins;
        this.membershipCheck = membershipCheck;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public InviteResult invite(InviteCommand command) {
        if (command.locationId() != null && locations.findById(command.locationId()).isEmpty()) {
            throw ApiException.badRequest("location_not_found", "no such location");
        }
        String phone = Phones.normalize(command.phone());

        if (command.pin() != null) {
            if (phone != null) {
                throw ApiException.badRequest("pin_with_phone",
                        "a PIN membership is register-only; invite a phone without a PIN instead");
            }
            return new InviteResult(memberships.save(Membership.registerOnly(command.displayName(), command.role(),
                    command.locationId(), pins.hash(command.pin()))), null);
        }

        if (phone != null) {
            Account verified = accounts.findByPhone(phone).filter(Account::isPhoneVerified).orElse(null);
            if (verified != null) {
                if (memberships.existsByAccountId(verified.getId())) {
                    throw ApiException.conflict("already_member", "that person is already a member");
                }
                return new InviteResult(memberships.save(Membership.invitedAccount(verified.getId(),
                        command.displayName(), command.role(), command.locationId(), phone)), null);
            }
        }

        String code = Secrets.newInviteCode();
        Membership invited = Membership.invitedByCode(command.displayName(), command.role(), command.locationId(),
                phone, Secrets.sha256Hex(code), clock.instant().plus(INVITE_TTL));
        return new InviteResult(memberships.save(invited), code);
    }

    /**
     * Sets a new register PIN — for the cashier who forgot theirs. Their PIN lockouts on every
     * register are cleared, since the owner has just vouched for them.
     */
    @Transactional
    public Membership setPin(UUID membershipId, String pin) {
        Membership membership = memberships.findById(membershipId)
                .orElseThrow(() -> ApiException.notFound("membership_not_found", "no such membership"));
        if (membership.getStatus() == MembershipStatus.REMOVED) {
            throw ApiException.badRequest("membership_removed", "a removed membership has no PIN");
        }
        membership.changePinHash(pins.hash(pin));
        jdbc.update("delete from register_pin_lockout where membership_id = ?", membershipId);
        membershipCheck.evict(membershipId);
        return membership;
    }

    @Transactional
    public Membership changeStatus(UUID membershipId, MembershipStatus status) {
        if (status == MembershipStatus.INVITED) {
            throw ApiException.badRequest("invalid_status", "a membership cannot be put back to INVITED");
        }
        if (membershipId.equals(TenantContext.requireMembershipId()) && status != MembershipStatus.ACTIVE) {
            throw ApiException.badRequest("cannot_remove_self", "you cannot suspend or remove yourself");
        }
        Membership membership = memberships.findById(membershipId)
                .orElseThrow(() -> ApiException.notFound("membership_not_found", "no such membership"));
        if (membership.getStatus() == MembershipStatus.INVITED && status == MembershipStatus.ACTIVE) {
            throw ApiException.badRequest("invalid_status", "an invitation is activated by accepting it");
        }
        membership.changeStatus(status);
        membershipCheck.evict(membershipId);
        return membership;
    }
}
