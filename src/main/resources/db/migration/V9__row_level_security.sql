-- V9 — build step 6b: row-level security, the second half of the split decided in §12 (RLS).
--
-- Every table with an organization_id gets RLS and one policy: the row's organization must equal
-- app.org, which the transaction hook sets as its first statement. A missing hook fails *closed*
-- (current_setting returns null, the comparison is null, no rows), which is why every reporting
-- path, the rebuild job and the login picker were wired before this migration.
--
-- membership carries a second, permissive SELECT policy on account_id, so the picker can see an
-- account's own memberships across organizations (spec §12 RLS).
--
-- The lookups that run *before* a tenant is known — the per-request membership check, the picker,
-- the invite code, register devices — cannot satisfy any policy, so each gets a SECURITY DEFINER
-- function owned by trillopos_owner. Each is keyed by an id or an unguessable hash and returns only
-- the columns its caller needs, so the privileged surface stays small and readable.
--
-- The app role owns nothing and has no BYPASSRLS (step 1); trillopos_owner, the table owner, is not
-- subject to these policies, so Flyway and any owner-role maintenance keep working.

-- ─────────────────────────────────────────────────────── the pre-tenant lookups

-- Membership by id: the per-request check. The caller compares the organization with the token.
create function trillopos.auth_membership(p_membership_id uuid)
    returns table (id uuid, organization_id uuid, account_id uuid, role varchar, status varchar, location_id uuid)
    language sql stable security definer set search_path = trillopos, pg_temp
as $$
    select m.id, m.organization_id, m.account_id, m.role, m.status, m.location_id
    from trillopos.membership m where m.id = p_membership_id
$$;

-- One account's active membership in one organization: the switch.
create function trillopos.auth_membership_active(p_account_id uuid, p_organization_id uuid)
    returns table (id uuid, organization_id uuid, account_id uuid, role varchar, status varchar, location_id uuid)
    language sql stable security definer set search_path = trillopos, pg_temp
as $$
    select m.id, m.organization_id, m.account_id, m.role, m.status, m.location_id
    from trillopos.membership m
    where m.account_id = p_account_id and m.organization_id = p_organization_id and m.status = 'ACTIVE'
$$;

-- The login picker: every organization this account belongs to.
create function trillopos.auth_memberships_for_account(p_account_id uuid)
    returns table (id uuid, organization_id uuid, organization_name varchar, display_name varchar, role varchar,
                   status varchar)
    language sql stable security definer set search_path = trillopos, pg_temp
as $$
    select m.id, m.organization_id, o.name, m.display_name, m.role, m.status
    from trillopos.membership m
    join trillopos.organization o on o.id = m.organization_id
    where m.account_id = p_account_id
      and m.status in ('ACTIVE', 'INVITED')
      and m.archived_at is null
    order by o.name, m.created_at
$$;

-- An invitation by the hash of its 6-character code: the one lookup that crosses organizations
-- with no account to key on (spec §12 Step 1 as built).
create function trillopos.auth_membership_by_invite(p_code_hash varchar)
    returns table (id uuid, organization_id uuid, status varchar, invite_expires_at timestamptz)
    language sql stable security definer set search_path = trillopos, pg_temp
as $$
    select m.id, m.organization_id, m.status, m.invite_expires_at
    from trillopos.membership m where m.invite_code_hash = p_code_hash
$$;

-- A register by id: the per-request check of a REGISTER token.
create function trillopos.auth_register_device(p_device_id uuid)
    returns table (id uuid, organization_id uuid, location_id uuid, status varchar, expires_at timestamptz,
                   failed_pin_count integer, locked_until timestamptz)
    language sql stable security definer set search_path = trillopos, pg_temp
as $$
    select d.id, d.organization_id, d.location_id, d.status, d.expires_at, d.failed_pin_count, d.locked_until
    from trillopos.register_device d where d.id = p_device_id
$$;

-- A register by its credential (the staff list), optionally locked for the PIN login transaction.
-- After this returns, the caller adopts the device's organization (set_config('app.org', …)) and
-- everything else it does goes through the ordinary policies.
create function trillopos.auth_register_device_by_credential(p_credential_hash varchar, p_lock boolean)
    returns table (id uuid, organization_id uuid, location_id uuid, status varchar, expires_at timestamptz,
                   failed_pin_count integer, locked_until timestamptz)
    language plpgsql volatile security definer set search_path = trillopos, pg_temp
as $$
begin
    if p_lock then
        return query
            select d.id, d.organization_id, d.location_id, d.status, d.expires_at, d.failed_pin_count, d.locked_until
            from trillopos.register_device d where d.credential_hash = p_credential_hash for update;
    else
        return query
            select d.id, d.organization_id, d.location_id, d.status, d.expires_at, d.failed_pin_count, d.locked_until
            from trillopos.register_device d where d.credential_hash = p_credential_hash;
    end if;
end;
$$;

revoke all on function trillopos.auth_membership(uuid) from public;
revoke all on function trillopos.auth_membership_active(uuid, uuid) from public;
revoke all on function trillopos.auth_memberships_for_account(uuid) from public;
revoke all on function trillopos.auth_membership_by_invite(varchar) from public;
revoke all on function trillopos.auth_register_device(uuid) from public;
revoke all on function trillopos.auth_register_device_by_credential(varchar, boolean) from public;

grant execute on function trillopos.auth_membership(uuid) to trillopos_app;
grant execute on function trillopos.auth_membership_active(uuid, uuid) to trillopos_app;
grant execute on function trillopos.auth_memberships_for_account(uuid) to trillopos_app;
grant execute on function trillopos.auth_membership_by_invite(varchar) to trillopos_app;
grant execute on function trillopos.auth_register_device(uuid) to trillopos_app;
grant execute on function trillopos.auth_register_device_by_credential(varchar, boolean) to trillopos_app;

-- ─────────────────────────────────────────────────────── the policies

-- nullif: an unset app.org reads as '' through current_setting(…, true), and ''::uuid would raise
-- rather than simply match nothing.
do $$
declare
    t text;
begin
    for t in
        select c.table_name from information_schema.columns c
        join information_schema.tables x
          on x.table_schema = c.table_schema and x.table_name = c.table_name and x.table_type = 'BASE TABLE'
        where c.table_schema = 'trillopos' and c.column_name = 'organization_id'
        order by c.table_name
    loop
        execute format('alter table trillopos.%I enable row level security', t);
        execute format($p$
            create policy tenant_isolation on trillopos.%I
                using (organization_id = nullif(current_setting('app.org', true), '')::uuid)
                with check (organization_id = nullif(current_setting('app.org', true), '')::uuid)
        $p$, t);
    end loop;
end;
$$;

-- The picker's own rows: an account may always read the memberships that name it, in any
-- organization. Permissive, so it is ORed with tenant_isolation.
create policy membership_own_account on trillopos.membership
    for select
    using (account_id = nullif(current_setting('app.account', true), '')::uuid);
