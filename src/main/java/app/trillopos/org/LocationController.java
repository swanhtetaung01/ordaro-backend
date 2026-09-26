package app.trillopos.org;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import app.trillopos.shared.web.ApiException;

@RestController
@RequestMapping("/locations")
class LocationController {

    record LocationView(UUID id, String code, String name, LocationType type, String address, String phone,
            boolean active) {

        static LocationView of(Location l) {
            return new LocationView(l.getId(), l.getCode(), l.getName(), l.getType(), l.getAddress(), l.getPhone(),
                    l.isActive());
        }
    }

    record LocationCreate(@NotBlank @Pattern(regexp = "[A-Za-z0-9-]{1,16}") String code,
            @NotBlank @Size(max = 200) String name, @NotNull LocationType type, @Size(max = 500) String address,
            String phone) {
    }

    record LocationUpdate(@Size(min = 1, max = 200) String name, @Size(max = 500) String address, String phone,
            Boolean active) {
    }

    private final LocationRepository locations;

    LocationController(LocationRepository locations) {
        this.locations = locations;
    }

    @GetMapping
    List<LocationView> list() {
        return locations.findAllByOrderByCodeAsc().stream().map(LocationView::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('OWNER')")
    @Transactional
    LocationView create(@Valid @RequestBody LocationCreate request) {
        String code = request.code().toUpperCase(Locale.ROOT);
        if (locations.existsByCode(code)) {
            throw ApiException.conflict("location_code_taken", "a location already uses code " + code);
        }
        Location location = new Location(code, request.name(), request.type());
        location.setAddress(request.address());
        location.setPhone(Phones.normalize(request.phone()));
        return LocationView.of(locations.save(location));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('OWNER')")
    @Transactional
    LocationView update(@PathVariable UUID id, @Valid @RequestBody LocationUpdate request) {
        Location location = locations.findById(id)
                .orElseThrow(() -> ApiException.notFound("location_not_found", "no such location"));
        if (request.name() != null) {
            location.setName(request.name());
        }
        if (request.address() != null) {
            location.setAddress(request.address());
        }
        if (request.phone() != null) {
            location.setPhone(Phones.normalize(request.phone()));
        }
        if (request.active() != null) {
            location.setActive(request.active());
        }
        return LocationView.of(location);
    }
}
