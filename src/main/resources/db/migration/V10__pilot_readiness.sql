-- V10 — pilot readiness (2026-09-23): what one real shop on the open internet needs.
--
-- account: consecutive wrong passwords lock the login for a while. The register PIN has had a
-- lockout since step 3; the password had none, and the server is now reachable by anyone.
-- The counter is written with atomic updates, never through the entity, so two wrong attempts
-- at the same moment both count.
--
-- organization: the credit terms a new customer starts with. An online shop that sells cash on
-- delivery records each parcel as a credit sale to the buyer until the courier pays; without a
-- default, every new buyer would need a limit set by hand before their first order.

alter table account
    add column failed_login_count integer not null default 0,
    add column login_locked_until timestamptz,
    add constraint account_failed_login_count_range_check check (failed_login_count >= 0);

alter table organization
    add column default_credit_limit numeric(19,4) not null default 0,
    add column default_credit_term_days integer not null default 0,
    add constraint organization_default_credit_limit_range_check check (default_credit_limit >= 0),
    add constraint organization_default_credit_term_days_range_check
        check (default_credit_term_days between 0 and 365);
