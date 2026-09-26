package app.trillopos.auth;

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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Register devices. Owner only, and only from an account session — a PIN session on a register
 * cannot bind or revoke registers.
 */
@RestController
@RequestMapping("/registers")
@PreAuthorize("hasRole('OWNER') and hasAuthority('" + SecurityConfig.USER_SESSION + "')")
class RegisterController {

    record BindRequest(@NotNull UUID locationId, @NotBlank @Size(max = 100) String label) {
    }

    record RegisterView(UUID id, UUID locationId, String label, RegisterDeviceStatus status, Instant expiresAt,
            Instant lastSeenAt, Instant revokedAt) {

        static RegisterView of(RegisterDevice d) {
            return new RegisterView(d.getId(), d.getLocationId(), d.getLabel(), d.getStatus(), d.getExpiresAt(),
                    d.getLastSeenAt(), d.getRevokedAt());
        }
    }

    /** The credential is shown once; the register stores it and sends it as {@code X-Register-Device}. */
    record BoundView(RegisterView register, String deviceCredential) {
    }

    private final RegisterDeviceService registers;

    RegisterController(RegisterDeviceService registers) {
        this.registers = registers;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    BoundView bind(@Valid @RequestBody BindRequest request) {
        RegisterDeviceService.Bound bound = registers.bind(request.locationId(), request.label());
        return new BoundView(RegisterView.of(bound.device()), bound.credential());
    }

    @GetMapping
    List<RegisterView> list() {
        return registers.list().stream().map(RegisterView::of).toList();
    }

    @PostMapping("/{id}/revoke")
    RegisterView revoke(@PathVariable UUID id) {
        return RegisterView.of(registers.revoke(id));
    }
}
