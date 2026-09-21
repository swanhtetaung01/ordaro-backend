package app.ordaro.auth;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import app.ordaro.shared.tenant.TenantContext;

/**
 * Tenant from the access token, membership verified per request (spec §12). A USER token must
 * name an ACTIVE membership that belongs to the token's {@code org} and to its {@code sub}; a
 * hand-crafted {@code org} with another tenant's {@code mem} is refused here. Authorities come
 * from the membership row, not from the token's {@code role} claim.
 *
 * <p>Not a bean: Boot would register it a second time as a plain servlet filter.
 */
final class TenantMembershipFilter extends OncePerRequestFilter {

    private final MembershipCheck membershipCheck;
    private final DeviceCheck deviceCheck;

    TenantMembershipFilter(MembershipCheck membershipCheck, DeviceCheck deviceCheck) {
        this.membershipCheck = membershipCheck;
        this.deviceCheck = deviceCheck;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        SecurityContextHolderStrategy strategy = SecurityContextHolder.getContextHolderStrategy();
        if (!(strategy.getContext().getAuthentication() instanceof JwtAuthenticationToken token)) {
            chain.doFilter(request, response);
            return;
        }
        Jwt jwt = token.getToken();
        UUID accountId = uuid(jwt.getSubject());
        String kind = jwt.getClaimAsString(TokenService.KIND);

        TenantContext.Current current;
        List<GrantedAuthority> authorities;
        if (RefreshTokenKind.USER.name().equals(kind) || RefreshTokenKind.REGISTER.name().equals(kind)) {
            UUID organizationId = uuid(jwt.getClaimAsString(TokenService.ORG));
            UUID membershipId = uuid(jwt.getClaimAsString(TokenService.MEM));
            Optional<MembershipSnapshot> membership = membershipId == null
                    ? Optional.empty()
                    : membershipCheck.find(membershipId);
            if (organizationId == null || membership.isEmpty() || !membership.get().isActive()
                    || !organizationId.equals(membership.get().organizationId())
                    || !Objects.equals(accountId, membership.get().accountId())) {
                reject(response);
                return;
            }
            UUID scope = membership.get().locationId();
            String sessionKind = SecurityConfig.USER_SESSION;
            if (RefreshTokenKind.REGISTER.name().equals(kind)) {
                // a PIN session: the register must still be active, in this organization, at the token's store
                UUID deviceId = uuid(jwt.getClaimAsString(TokenService.DEV));
                UUID location = uuid(jwt.getClaimAsString(TokenService.LOC));
                Optional<DeviceCheck.DeviceSnapshot> device = deviceId == null ? Optional.empty()
                        : deviceCheck.find(deviceId);
                if (device.isEmpty() || !device.get().usable(Instant.now())
                        || !organizationId.equals(device.get().organizationId())
                        || !device.get().locationId().equals(location)) {
                    reject(response);
                    return;
                }
                scope = location;
                sessionKind = SecurityConfig.REGISTER_SESSION;
            }
            current = new TenantContext.Current(organizationId, membershipId, accountId, scope);
            authorities = List.of(new SimpleGrantedAuthority(SecurityConfig.TENANT_SESSION),
                    new SimpleGrantedAuthority(sessionKind),
                    new SimpleGrantedAuthority("ROLE_" + membership.get().role().name()));
        } else if (RefreshTokenKind.PICKER.name().equals(kind) && accountId != null) {
            current = new TenantContext.Current(null, null, accountId);
            authorities = List.of(new SimpleGrantedAuthority(SecurityConfig.PICKER_SESSION));
        } else {
            reject(response);
            return;
        }

        SecurityContext context = strategy.createEmptyContext();
        String name = jwt.getSubject() != null ? jwt.getSubject() : jwt.getClaimAsString(TokenService.MEM);
        context.setAuthentication(new JwtAuthenticationToken(jwt, authorities, name));
        strategy.setContext(context);

        TenantContext.Current previous = TenantContext.enter(current);
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.restore(previous);
        }
    }

    private static UUID uuid(String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void reject(HttpServletResponse response) throws IOException {
        SecurityContextHolder.clearContext();
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("""
                {"type":"about:blank","title":"membership_invalid","status":401,\
                "detail":"the token does not name an active membership","code":"membership_invalid"}""");
    }
}
