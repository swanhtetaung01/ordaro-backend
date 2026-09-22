package app.ordaro.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtTypeValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless bearer-token API (Spring Security 7, lambda DSL). After the JWT is verified,
 * {@link TenantMembershipFilter} checks the membership it names and sets the tenant.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfig {

    /** Authority for any tenant session: a USER or REGISTER token naming a verified membership. */
    public static final String TENANT_SESSION = "TENANT";

    /** An account session (phone + password). */
    public static final String USER_SESSION = "KIND_USER";

    /** A PIN session on a bound register, scoped to its store. */
    public static final String REGISTER_SESSION = "KIND_REGISTER";

    /** Authority for a picker session: an account with no tenant chosen yet. */
    public static final String PICKER_SESSION = "KIND_PICKER";

    @Bean
    SecurityFilterChain api(HttpSecurity http, JwtDecoder jwtDecoder, MembershipCheck membershipCheck,
            DeviceCheck deviceCheck) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.POST, "/auth/signup", "/auth/login", "/auth/refresh",
                                "/auth/logout", "/auth/pin").permitAll()
                        .requestMatchers(HttpMethod.GET, "/auth/register/staff").permitAll()
						.requestMatchers(HttpMethod.GET, "/.well-known/jwks.json", "/actuator/health",
								"/v3/api-docs", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/auth/**").hasAnyAuthority(USER_SESSION, PICKER_SESSION)
                        .anyRequest().hasAuthority(TENANT_SESSION))
                .oauth2ResourceServer(resourceServer -> resourceServer.jwt(jwt -> jwt.decoder(jwtDecoder)))
                .addFilterAfter(new TenantMembershipFilter(membershipCheck, deviceCheck),
                        BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder(JwtKeys keys, JwtProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSource(keys.verificationSource())
                .jwsAlgorithm(SignatureAlgorithm.ES256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(),
                new JwtIssuerValidator(properties.issuer()), JwtTypeValidator.jwt()));
        return decoder;
    }

    @Bean
    JwtEncoder jwtEncoder(JwtKeys keys) {
        return new NimbusJwtEncoder(keys.signingSource());
    }

    /** BCrypt through the delegating encoder, so the hash format can change later (spec §3). */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
