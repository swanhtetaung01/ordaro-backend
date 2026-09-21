package app.ordaro.shared.persistence;

import java.time.Clock;
import java.util.UUID;

import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import app.ordaro.shared.tenant.TenantContext;

@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing(auditorAwareRef = "membershipAuditor")
public class PersistenceConfig {

    @Bean
    HibernatePropertiesCustomizer tenantResolverCustomizer() {
        return properties -> properties.put("hibernate.tenant_identifier_resolver", new TenantIdentifierResolver());
    }

    /** {@code createdBy} / {@code updatedBy} record the acting membership (spec §1). */
    @Bean
    AuditorAware<UUID> membershipAuditor() {
        return TenantContext::membershipId;
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
