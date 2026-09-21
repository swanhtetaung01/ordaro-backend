package app.ordaro.auth;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.ordaro.org.Location;
import app.ordaro.org.LocationRepository;
import app.ordaro.shared.tenant.TenantContext;
import app.ordaro.shared.web.ApiException;

/** Binding and revoking register devices. Only a STORE has registers (spec §12 Locations). */
@Service
public class RegisterDeviceService {

    static final Duration DEVICE_TTL = Duration.ofDays(365);

    /** {@code credential} is returned once, at binding; only its hash is stored. */
    public record Bound(RegisterDevice device, String credential) {
    }

    private final RegisterDeviceRepository devices;
    private final LocationRepository locations;
    private final DeviceCheck deviceCheck;
    private final Clock clock;

    public RegisterDeviceService(RegisterDeviceRepository devices, LocationRepository locations,
            DeviceCheck deviceCheck, Clock clock) {
        this.devices = devices;
        this.locations = locations;
        this.deviceCheck = deviceCheck;
        this.clock = clock;
    }

    @Transactional
    public Bound bind(UUID locationId, String label) {
        TenantContext.requireLocationInScope(locationId);
        Location location = locations.findById(locationId)
                .orElseThrow(() -> ApiException.badRequest("location_not_found", "no such location"));
        if (!location.isStore()) {
            throw ApiException.badRequest("not_a_store",
                    location.getCode() + " is a " + location.getType() + "; only a STORE has registers");
        }
        if (!location.isActive()) {
            throw ApiException.badRequest("location_inactive", location.getCode() + " is closed");
        }
        String credential = Secrets.newRefreshToken();
        RegisterDevice device = devices.save(new RegisterDevice(locationId, label, Secrets.sha256Hex(credential),
                clock.instant().plus(DEVICE_TTL)));
        return new Bound(device, credential);
    }

    @Transactional
    public RegisterDevice revoke(UUID deviceId) {
        RegisterDevice device = devices.findById(deviceId)
                .orElseThrow(() -> ApiException.notFound("register_not_found", "no such register"));
        TenantContext.requireLocationInScope(device.getLocationId());
        device.revoke(clock.instant());
        deviceCheck.evict(deviceId);
        return device;
    }

    public List<RegisterDevice> list() {
        return devices.findAllByOrderByCreatedAtDesc();
    }
}
