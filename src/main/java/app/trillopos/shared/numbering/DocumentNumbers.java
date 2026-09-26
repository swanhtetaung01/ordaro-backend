package app.trillopos.shared.numbering;

import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import app.trillopos.shared.tenant.TenantContext;

/**
 * Business numbers {@code {locationCode}-{type}-{yy}-{seq}}, e.g. {@code YM-GRN-26-000148}
 * (spec §9 Sequences). The counter row is incremented by one upsert, which holds its row lock
 * until the caller's transaction ends: concurrent posters queue, and a rollback returns the
 * number, so a location's book never skips. The year is the business time's year <em>in the
 * organization's timezone</em>. Must run inside the transaction that uses the number.
 */
@Component
public class DocumentNumbers {

    private final JdbcTemplate jdbc;

    public DocumentNumbers(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String next(UUID locationId, String locationCode, DocumentSequenceType type, Instant businessTime,
            ZoneId zone) {
        int year = businessTime.atZone(zone).getYear();
        Long sequence = jdbc.queryForObject("""
                insert into document_sequence (organization_id, location_id, type, year, last_value)
                values (?, ?, ?, ?, 1)
                on conflict (location_id, type, year)
                do update set last_value = document_sequence.last_value + 1
                where document_sequence.organization_id = excluded.organization_id
                returning last_value
                """, Long.class, TenantContext.requireOrganizationId(), locationId, type.name(), year);
        return "%s-%s-%02d-%06d".formatted(locationCode, type.name(), year % 100, sequence);
    }
}
