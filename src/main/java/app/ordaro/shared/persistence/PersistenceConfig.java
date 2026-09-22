package app.ordaro.shared.persistence;

import java.time.Clock;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.beans.factory.config.BeanPostProcessor;
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

    /**
     * Wraps the application's pool so every connection announces its tenant to PostgreSQL for
     * row-level security (spec §12 RLS). A {@code static} bean-post-processor, so it is in place
     * before anything injects a {@link DataSource}. Flyway builds its own pool from its own
     * properties and is untouched: it runs as the owner, which policies do not apply to.
     */
    @Bean
    static BeanPostProcessor tenantDataSourceWrapper() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                return bean instanceof DataSource dataSource && !(bean instanceof TenantDataSource)
                        ? new TenantDataSource(dataSource)
                        : bean;
            }
        };
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
