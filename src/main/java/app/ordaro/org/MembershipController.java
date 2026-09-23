package app.ordaro.org;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import app.ordaro.org.MembershipService.InviteCommand;
import app.ordaro.org.MembershipService.InviteResult;

/** The organization's staff. Owner only. */
@RestController
@RequestMapping("/memberships")
@PreAuthorize("hasRole('OWNER')")
class MembershipController {

    record MembershipView(UUID id, UUID accountId, String displayName, MembershipRole role, UUID locationId,
            MembershipStatus status, String invitedPhone, Instant inviteExpiresAt, Instant acceptedAt) {

        static MembershipView of(Membership m) {
            return new MembershipView(m.getId(), m.getAccountId(), m.getDisplayName(), m.getRole(),
                    m.getLocationId(), m.getStatus(), m.getInvitedPhone(), m.getInviteExpiresAt(), m.getAcceptedAt());
        }
    }

    record InviteRequest(@NotBlank @Size(max = 200) String displayName, @NotNull MembershipRole role,
            UUID locationId, String phone, String pin) {
    }

    record InviteResponse(MembershipView membership, String inviteCode) {
    }

    record StatusChange(@NotNull MembershipStatus status) {
    }

    record PinChange(@NotBlank String pin) {
    }

    private final MembershipRepository memberships;
    private final MembershipService service;

    MembershipController(MembershipRepository memberships, MembershipService service) {
        this.memberships = memberships;
        this.service = service;
    }

    @GetMapping
    List<MembershipView> list() {
        return memberships.findAllByOrderByCreatedAtAsc().stream().map(MembershipView::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    InviteResponse invite(@Valid @RequestBody InviteRequest request) {
        InviteResult result = service.invite(new InviteCommand(request.displayName(), request.role(),
                request.locationId(), request.phone(), request.pin()));
        return new InviteResponse(MembershipView.of(result.membership()), result.inviteCode());
    }

    @PatchMapping("/{id}")
    MembershipView changeStatus(@PathVariable UUID id, @Valid @RequestBody StatusChange request) {
        return MembershipView.of(service.changeStatus(id, request.status()));
    }

    /** Set or reset someone's 6-digit register PIN. */
    @PutMapping("/{id}/pin")
    MembershipView setPin(@PathVariable UUID id, @Valid @RequestBody PinChange request) {
        return MembershipView.of(service.setPin(id, request.pin()));
    }
}
