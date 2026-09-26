-- V1 — build step 1: shared columns, org + catalog + identity.
--
-- Runs as trillopos_owner in schema trillopos; both were created by db/bootstrap.sql (as postgres),
-- not here. Once applied, never edit this file: add V2.
--
-- Conventions (spec §1, §12):
--   * every table: id uuid (v7, app-assigned), version, created_at/updated_at, created_by/updated_by
--   * tenant tables add organization_id + archived_at
--   * money NUMERIC(19,4)
--   * enum columns VARCHAR(32) + a named CHECK <table>_<column>_check (spec §12 Enum columns)
--   * references between tenant tables are composite (organization_id, x_id), so no row can
--     point at another tenant's row whatever the application does
--   * RLS policies are step 6; this migration only sets up grants.

-- Everything created below is usable by the app role. Set first so it covers V1's own tables;
-- flyway_schema_history already exists and stays owner-only.
alter default privileges in schema trillopos grant select, insert, update, delete on tables to trillopos_app;
alter default privileges in schema trillopos grant usage, select on sequences to trillopos_app;

-- ─────────────────────────────────────────────────────────────── org

create table organization (
    id                      uuid          primary key,
    version                 bigint        not null,
    created_at              timestamptz   not null,
    updated_at              timestamptz   not null,
    created_by              uuid,
    updated_by              uuid,
    name                    varchar(200)  not null,
    slug                    varchar(64)   not null,
    business_type           varchar(32)   not null,
    currency_code           varchar(3)    not null,
    tax_inclusive_pricing   boolean       not null,
    default_tax_rate        numeric(7,4)  not null,
    round_total_to_nearest  numeric(19,4),
    allow_negative_stock    boolean       not null default false,
    costing_method          varchar(32)   not null,
    timezone                varchar(64)   not null,
    constraint organization_slug_key unique (slug),
    constraint organization_slug_format_check check (slug ~ '^[a-z0-9-]+$'),
    constraint organization_business_type_check check (business_type in
        ('RETAIL', 'WHOLESALE', 'ONLINE', 'DISTRIBUTOR', 'MULTI_BRANCH', 'FNB', 'SERVICE', 'RENTAL', 'OTHER')),
    constraint organization_costing_method_check check (costing_method in ('WEIGHTED_AVERAGE')),
    constraint organization_default_tax_rate_range_check check (default_tax_rate >= 0),
    constraint organization_round_total_positive_check check (round_total_to_nearest > 0)
);

create table account (
    id                      uuid          primary key,
    version                 bigint        not null,
    created_at              timestamptz   not null,
    updated_at              timestamptz   not null,
    created_by              uuid,
    updated_by              uuid,
    phone                   varchar(20),
    phone_verified_at       timestamptz,
    password_hash           varchar(255)  not null,
    full_name               varchar(200)  not null,
    status                  varchar(32)   not null,
    login_disabled_reason   varchar(32),
    constraint account_status_check check (status in ('ACTIVE', 'SUSPENDED')),
    constraint account_login_disabled_reason_check check (login_disabled_reason in ('PHONE_RECLAIMED'))
);

-- Phone is unique system-wide where present; null after a phone claim moves the number.
create unique index account_phone_key on account (phone) where phone is not null;

-- V1 shape (spec §12 Locations): STORE · WAREHOUSE. No parent_location_id yet.
create table location (
    id                      uuid          primary key,
    version                 bigint        not null,
    created_at              timestamptz   not null,
    updated_at              timestamptz   not null,
    created_by              uuid,
    updated_by              uuid,
    organization_id         uuid          not null references organization (id),
    archived_at             timestamptz,
    code                    varchar(16)   not null,
    name                    varchar(200)  not null,
    type                    varchar(32)   not null,
    address                 varchar(500),
    phone                   varchar(20),
    active                  boolean       not null default true,
    constraint location_organization_id_id_key unique (organization_id, id),
    constraint location_code_key unique (organization_id, code),
    constraint location_type_check check (type in ('STORE', 'WAREHOUSE'))
);

create table membership (
    id                      uuid          primary key,
    version                 bigint        not null,
    created_at              timestamptz   not null,
    updated_at              timestamptz   not null,
    created_by              uuid,
    updated_by              uuid,
    organization_id         uuid          not null references organization (id),
    archived_at             timestamptz,
    account_id              uuid          references account (id),
    display_name            varchar(200)  not null,
    role                    varchar(32)   not null,
    location_id             uuid,
    pin_hash                varchar(255),
    status                  varchar(32)   not null,
    invited_phone           varchar(20),
    invite_code_hash        varchar(64),
    invite_expires_at       timestamptz,
    accepted_at             timestamptz,
    constraint membership_location_fk foreign key (organization_id, location_id)
        references location (organization_id, id),
    constraint membership_role_check check (role in ('OWNER', 'STOCK_MANAGER', 'CASHIER', 'PACKER')),
    constraint membership_status_check check (status in ('INVITED', 'ACTIVE', 'SUSPENDED', 'REMOVED'))
);

-- One membership per (organization, account).
create unique index membership_account_key on membership (organization_id, account_id)
    where account_id is not null;
-- Invite codes are entered without an organization, so the hash is unique across all of them
-- (stricter than the spec's per-organization index, which it implies).
create unique index membership_invite_code_key on membership (invite_code_hash)
    where invite_code_hash is not null;
-- The login picker filters by account alone.
create index membership_account_idx on membership (account_id) where account_id is not null;

-- ─────────────────────────────────────────────────────────────── identity (not tenant-owned)

create table refresh_token (
    id                      uuid          primary key,
    version                 bigint        not null,
    created_at              timestamptz   not null,
    updated_at              timestamptz   not null,
    created_by              uuid,
    updated_by              uuid,
    account_id              uuid          references account (id),
    membership_id           uuid          references membership (id),
    token_hash              varchar(64)   not null,
    family_id               uuid          not null,
    kind                    varchar(32)   not null,
    device_label            varchar(100),
    expires_at              timestamptz   not null,
    revoked_at              timestamptz,
    last_used_at            timestamptz,
    constraint refresh_token_token_hash_key unique (token_hash),
    constraint refresh_token_kind_check check (kind in ('USER', 'PICKER'))
);

create index refresh_token_family_idx on refresh_token (family_id);

-- Plain table, not an entity: the Telegram bot and phone-claim flow ship before public launch.
create table phone_verification (
    id                      uuid          primary key,
    account_id              uuid          not null references account (id),
    channel                 varchar(32)   not null,
    nonce_hash              varchar(64)   not null,
    phone                   varchar(20),
    expires_at              timestamptz   not null,
    completed_at            timestamptz,
    created_at              timestamptz   not null default now(),
    constraint phone_verification_nonce_hash_key unique (nonce_hash),
    constraint phone_verification_channel_check check (channel in ('TELEGRAM'))
);

-- ─────────────────────────────────────────────────────────────── catalog

create table category (
    id                      uuid          primary key,
    version                 bigint        not null,
    created_at              timestamptz   not null,
    updated_at              timestamptz   not null,
    created_by              uuid,
    updated_by              uuid,
    organization_id         uuid          not null references organization (id),
    archived_at             timestamptz,
    name                    varchar(120)  not null,
    parent_id               uuid,
    constraint category_organization_id_id_key unique (organization_id, id),
    constraint category_parent_fk foreign key (organization_id, parent_id)
        references category (organization_id, id)
);

create table supplier (
    id                      uuid          primary key,
    version                 bigint        not null,
    created_at              timestamptz   not null,
    updated_at              timestamptz   not null,
    created_by              uuid,
    updated_by              uuid,
    organization_id         uuid          not null references organization (id),
    archived_at             timestamptz,
    name                    varchar(200)  not null,
    phone                   varchar(20),
    address                 varchar(500),
    payment_terms_days      integer       not null default 0,
    constraint supplier_organization_id_id_key unique (organization_id, id),
    constraint supplier_payment_terms_days_range_check check (payment_terms_days >= 0)
);

create table product (
    id                      uuid          primary key,
    version                 bigint        not null,
    created_at              timestamptz   not null,
    updated_at              timestamptz   not null,
    created_by              uuid,
    updated_by              uuid,
    organization_id         uuid          not null references organization (id),
    archived_at             timestamptz,
    sku                     varchar(64)   not null,
    name                    varchar(200)  not null,
    category_id             uuid,
    default_supplier_id     uuid,
    unit                    varchar(32)   not null,
    size_label              varchar(32),
    product_group_key       varchar(64),
    retail_price            numeric(19,4) not null,
    wholesale_price         numeric(19,4),
    taxable                 boolean       not null default true,
    track_inventory         boolean       not null default true,
    reorder_point           integer       not null default 0,
    sell_in_pos             boolean       not null default true,
    sell_online             boolean       not null default false,
    active                  boolean       not null default true,
    constraint product_organization_id_id_key unique (organization_id, id),
    -- Archived SKUs stay reserved: the constraint is not partial.
    constraint product_sku_key unique (organization_id, sku),
    constraint product_category_fk foreign key (organization_id, category_id)
        references category (organization_id, id),
    constraint product_default_supplier_fk foreign key (organization_id, default_supplier_id)
        references supplier (organization_id, id),
    constraint product_unit_check check (unit in ('PIECE', 'BAG', 'BOX', 'KG', 'LITRE', 'PACK')),
    constraint product_retail_price_range_check check (retail_price >= 0),
    constraint product_wholesale_price_range_check check (wholesale_price >= 0),
    constraint product_reorder_point_range_check check (reorder_point >= 0)
);

-- The hottest lookup in the system; archived barcodes are not reusable.
create table product_barcode (
    id                      uuid          primary key,
    version                 bigint        not null,
    created_at              timestamptz   not null,
    updated_at              timestamptz   not null,
    created_by              uuid,
    updated_by              uuid,
    organization_id         uuid          not null references organization (id),
    archived_at             timestamptz,
    product_id              uuid          not null,
    barcode                 varchar(64)   not null,
    constraint product_barcode_key unique (organization_id, barcode),
    constraint product_barcode_product_fk foreign key (organization_id, product_id)
        references product (organization_id, id)
);

create index product_barcode_product_idx on product_barcode (product_id);

create table location_product (
    id                      uuid          primary key,
    version                 bigint        not null,
    created_at              timestamptz   not null,
    updated_at              timestamptz   not null,
    created_by              uuid,
    updated_by              uuid,
    organization_id         uuid          not null references organization (id),
    archived_at             timestamptz,
    location_id             uuid          not null,
    product_id              uuid          not null,
    reorder_point           integer,
    shelf_location          varchar(32),
    constraint location_product_key unique (location_id, product_id),
    constraint location_product_location_fk foreign key (organization_id, location_id)
        references location (organization_id, id),
    constraint location_product_product_fk foreign key (organization_id, product_id)
        references product (organization_id, id),
    constraint location_product_reorder_point_range_check check (reorder_point >= 0)
);
