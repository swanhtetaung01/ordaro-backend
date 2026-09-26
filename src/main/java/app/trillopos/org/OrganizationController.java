package app.trillopos.org;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import app.trillopos.shared.tenant.TenantContext;
import app.trillopos.shared.web.ApiException;

/** The current organization's settings. Organization is the tenant root, so it is loaded by id. */
@RestController
@RequestMapping("/organization")
class OrganizationController {

    record OrganizationView(UUID id, String name, String slug, BusinessType businessType, String currencyCode,
            boolean taxInclusivePricing, BigDecimal defaultTaxRate, BigDecimal roundTotalToNearest,
            boolean allowNegativeStock, CostingMethod costingMethod, String timezone, BigDecimal defaultCreditLimit,
            int defaultCreditTermDays) {

        static OrganizationView of(Organization o) {
            return new OrganizationView(o.getId(), o.getName(), o.getSlug(), o.getBusinessType(),
                    o.getCurrencyCode(), o.isTaxInclusivePricing(), o.getDefaultTaxRate(),
                    o.getRoundTotalToNearest(), o.isAllowNegativeStock(), o.getCostingMethod(), o.getTimezone(),
                    o.getDefaultCreditLimit(), o.getDefaultCreditTermDays());
        }
    }

    /** {@code defaultCreditLimit}/{@code defaultCreditTermDays}: what new customers start with. */
    record OrganizationUpdate(@Size(min = 1, max = 200) String name, BusinessType businessType,
            @Pattern(regexp = "[A-Z]{3}") String currencyCode, Boolean taxInclusivePricing,
            @DecimalMin("0") BigDecimal defaultTaxRate, @DecimalMin(value = "0", inclusive = false)
            BigDecimal roundTotalToNearest, Boolean allowNegativeStock, @Size(min = 1, max = 64) String timezone,
            @DecimalMin("0") BigDecimal defaultCreditLimit, @Min(0) @Max(365) Integer defaultCreditTermDays) {
    }

    private final OrganizationRepository organizations;

    OrganizationController(OrganizationRepository organizations) {
        this.organizations = organizations;
    }

    @GetMapping
    OrganizationView get() {
        return OrganizationView.of(current());
    }

    @PatchMapping
    @PreAuthorize("hasRole('OWNER')")
    @Transactional
    OrganizationView update(@Valid @RequestBody OrganizationUpdate update) {
        Organization o = current();
        if (update.name() != null) {
            o.setName(update.name());
        }
        if (update.businessType() != null) {
            o.setBusinessType(update.businessType());
        }
        if (update.currencyCode() != null) {
            o.setCurrencyCode(update.currencyCode());
        }
        if (update.taxInclusivePricing() != null) {
            o.setTaxInclusivePricing(update.taxInclusivePricing());
        }
        if (update.defaultTaxRate() != null) {
            o.setDefaultTaxRate(update.defaultTaxRate());
        }
        if (update.roundTotalToNearest() != null) {
            o.setRoundTotalToNearest(update.roundTotalToNearest());
        }
        if (update.allowNegativeStock() != null) {
            o.setAllowNegativeStock(update.allowNegativeStock());
        }
        if (update.defaultCreditLimit() != null) {
            o.setDefaultCreditLimit(update.defaultCreditLimit());
        }
        if (update.defaultCreditTermDays() != null) {
            o.setDefaultCreditTermDays(update.defaultCreditTermDays());
        }
        if (update.timezone() != null) {
            try {
                o.setTimezone(ZoneId.of(update.timezone()).getId());
            } catch (DateTimeException e) {
                throw ApiException.badRequest("invalid_timezone", "unknown timezone " + update.timezone());
            }
        }
        return OrganizationView.of(o);
    }

    private Organization current() {
        return organizations.findById(TenantContext.requireOrganizationId()).orElseThrow();
    }
}
