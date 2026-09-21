-- V4 — build step 3: sales. Register devices and PIN lockout, cashier shifts, sales with their
-- lines and payments, and REGISTER refresh tokens.
--
-- Same conventions as V1/V2. Only a STORE has registers, shifts and receipt numbers; that rule
-- lives in the service (spec §12 Locations), so these tables reference location(id) plainly.

-- Composite references into membership need this key (V1 did not add it).
alter table membership add constraint membership_organization_id_id_key unique (organization_id, id);

-- ─────────────────────────────────────────────────────────────── register devices

-- A register bound to one STORE. Its credential is an opaque secret stored hashed; a PIN is only
-- accepted together with it (spec §12 PIN).
create table register_device (
    id                      uuid          primary key,
    version                 bigint        not null,
    created_at              timestamptz   not null,
    updated_at              timestamptz   not null,
    created_by              uuid,
    updated_by              uuid,
    organization_id         uuid          not null references organization (id),
    archived_at             timestamptz,
    location_id             uuid          not null,
    label                   varchar(100)  not null,
    credential_hash         varchar(64)   not null,
    status                  varchar(32)   not null,
    expires_at              timestamptz   not null,
    -- consecutive wrong PINs on this device across all memberships
    failed_pin_count        integer       not null default 0,
    locked_until            timestamptz,
    last_seen_at            timestamptz,
    revoked_at              timestamptz,
    constraint register_device_organization_id_id_key unique (organization_id, id),
    constraint register_device_credential_hash_key unique (credential_hash),
    constraint register_device_location_fk foreign key (organization_id, location_id)
        references location (organization_id, id),
    constraint register_device_status_check check (status in ('ACTIVE', 'REVOKED')),
    constraint register_device_failed_pin_count_range_check check (failed_pin_count >= 0)
);

-- Consecutive wrong PINs for one membership on one device. Plain table, written by the PIN login.
create table register_pin_lockout (
    organization_id         uuid          not null references organization (id),
    device_id               uuid          not null,
    membership_id           uuid          not null,
    failed_count            integer       not null default 0,
    locked_until            timestamptz,
    constraint register_pin_lockout_pkey primary key (device_id, membership_id),
    constraint register_pin_lockout_device_fk foreign key (organization_id, device_id)
        references register_device (organization_id, id),
    constraint register_pin_lockout_membership_fk foreign key (organization_id, membership_id)
        references membership (organization_id, id),
    constraint register_pin_lockout_failed_count_range_check check (failed_count >= 0)
);

-- Refresh tokens for PIN sessions are bound to their device.
alter table refresh_token add column device_id uuid references register_device (id);
alter table refresh_token drop constraint refresh_token_kind_check,
    add constraint refresh_token_kind_check check (kind in ('USER', 'PICKER', 'REGISTER'));

-- ─────────────────────────────────────────────────────────────── shifts

create table cashier_shift (
    id                      uuid          primary key,
    version                 bigint        not null,
    created_at              timestamptz   not null,
    updated_at              timestamptz   not null,
    created_by              uuid,
    updated_by              uuid,
    organization_id         uuid          not null references organization (id),
    archived_at             timestamptz,
    location_id             uuid          not null,
    opened_by               uuid          not null,
    opened_at               timestamptz   not null,
    opening_float           numeric(19,4) not null,
    closed_by               uuid,
    closed_at               timestamptz,
    expected_cash           numeric(19,4),
    counted_cash            numeric(19,4),
    -- stored at close, never recomputed later (spec §6)
    variance                numeric(19,4),
    status                  varchar(32)   not null,
    constraint cashier_shift_organization_id_id_key unique (organization_id, id),
    constraint cashier_shift_location_fk foreign key (organization_id, location_id)
        references location (organization_id, id),
    constraint cashier_shift_opened_by_fk foreign key (organization_id, opened_by)
        references membership (organization_id, id),
    constraint cashier_shift_closed_by_fk foreign key (organization_id, closed_by)
        references membership (organization_id, id),
    constraint cashier_shift_status_check check (status in ('OPEN', 'CLOSED')),
    constraint cashier_shift_opening_float_range_check check (opening_float >= 0),
    constraint cashier_shift_closed_complete_check check (status <> 'CLOSED'
        or (closed_at is not null and expected_cash is not null and counted_cash is not null
            and variance is not null))
);

-- Only one OPEN shift per location.
create unique index cashier_shift_one_open_key on cashier_shift (location_id) where status = 'OPEN';

-- ─────────────────────────────────────────────────────────────── sales

create table sale (
    id                      uuid          primary key,
    version                 bigint        not null,
    created_at              timestamptz   not null,
    updated_at              timestamptz   not null,
    created_by              uuid,
    updated_by              uuid,
    organization_id         uuid          not null references organization (id),
    archived_at             timestamptz,
    location_id             uuid          not null,
    cashier_shift_id        uuid,
    channel                 varchar(32)   not null,
    -- Order and Customer tables arrive later; their foreign keys come with them
    order_id                uuid,
    customer_id             uuid,
    idempotency_key         varchar(100),
    receipt_number          varchar(40),
    status                  varchar(32)   not null,
    price_type              varchar(32)   not null,
    tax_inclusive           boolean       not null,
    subtotal                numeric(19,4) not null,
    line_discount_total     numeric(19,4) not null,
    cart_discount_amount    numeric(19,4) not null,
    tax_amount              numeric(19,4) not null,
    rounding_adjustment     numeric(19,4) not null,
    total                   numeric(19,4) not null,
    paid_amount             numeric(19,4) not null,
    due_amount              numeric(19,4) not null,
    sold_at                 timestamptz,
    voided_at               timestamptz,
    constraint sale_organization_id_id_key unique (organization_id, id),
    constraint sale_location_fk foreign key (organization_id, location_id)
        references location (organization_id, id),
    constraint sale_cashier_shift_fk foreign key (organization_id, cashier_shift_id)
        references cashier_shift (organization_id, id),
    constraint sale_channel_check check (channel in ('POS', 'ONLINE', 'ORDER')),
    constraint sale_status_check check (status in
        ('DRAFT', 'HELD', 'COMPLETED', 'VOID', 'PARTIALLY_REFUNDED', 'REFUNDED')),
    constraint sale_price_type_check check (price_type in ('RETAIL', 'WHOLESALE')),
    -- a completed POS sale belongs to a shift; ONLINE and ORDER sales have no register
    constraint sale_pos_shift_check check (channel <> 'POS' or status in ('DRAFT', 'HELD', 'VOID')
        or cashier_shift_id is not null),
    constraint sale_order_required_check check (channel <> 'ORDER' or order_id is not null),
    -- the receipt number and business time are fixed on completion, never on draft
    constraint sale_completed_numbered_check check (status in ('DRAFT', 'HELD', 'VOID')
        or (receipt_number is not null and sold_at is not null)),
    constraint sale_amounts_range_check check (subtotal >= 0 and line_discount_total >= 0
        and cart_discount_amount >= 0 and tax_amount >= 0 and total >= 0 and paid_amount >= 0)
);

-- A retried completion finds the original (spec §9.2 step 0).
create unique index sale_idempotency_key on sale (location_id, idempotency_key) where idempotency_key is not null;
create unique index sale_receipt_number_key on sale (organization_id, receipt_number) where receipt_number is not null;
create index sale_sold_at_idx on sale (organization_id, location_id, sold_at);
create index sale_cashier_shift_idx on sale (cashier_shift_id) where cashier_shift_id is not null;

-- Snapshots: a receipt must render the same in three years (spec §6).
create table sale_line (
    id                      uuid          primary key,
    version                 bigint        not null,
    created_at              timestamptz   not null,
    updated_at              timestamptz   not null,
    created_by              uuid,
    updated_by              uuid,
    organization_id         uuid          not null references organization (id),
    archived_at             timestamptz,
    sale_id                 uuid          not null,
    position                integer       not null,
    product_id              uuid          not null,
    product_name            varchar(200)  not null,
    sku                     varchar(64)   not null,
    quantity                numeric(19,4) not null,
    unit_price              numeric(19,4) not null,
    discount_amount         numeric(19,4) not null,
    cart_discount_allocated numeric(19,4) not null,
    tax_rate                numeric(7,4)  not null,
    tax_amount              numeric(19,4) not null,
    -- the weighted average at the instant of sale; 0 when the product does not track inventory
    unit_cost               numeric(19,4) not null,
    -- tax-inclusive in both pricing modes: what the customer pays for this line
    line_total              numeric(19,4) not null,
    constraint sale_line_sale_fk foreign key (organization_id, sale_id)
        references sale (organization_id, id),
    constraint sale_line_product_fk foreign key (organization_id, product_id)
        references product (organization_id, id),
    constraint sale_line_position_key unique (sale_id, position),
    constraint sale_line_quantity_range_check check (quantity > 0),
    constraint sale_line_amounts_range_check check (unit_price >= 0 and discount_amount >= 0
        and cart_discount_allocated >= 0 and tax_rate >= 0 and tax_amount >= 0 and unit_cost >= 0
        and line_total >= 0)
);

create index sale_line_sale_idx on sale_line (sale_id);

create table payment (
    id                      uuid          primary key,
    version                 bigint        not null,
    created_at              timestamptz   not null,
    updated_at              timestamptz   not null,
    created_by              uuid,
    updated_by              uuid,
    organization_id         uuid          not null references organization (id),
    archived_at             timestamptz,
    sale_id                 uuid          not null,
    method                  varchar(32)   not null,
    amount                  numeric(19,4) not null,
    tendered_amount         numeric(19,4),
    change_amount           numeric(19,4),
    reference_no            varchar(100),
    constraint payment_sale_fk foreign key (organization_id, sale_id)
        references sale (organization_id, id),
    constraint payment_method_check check (method in
        ('CASH', 'KBZ_PAY', 'WAVE_PAY', 'AYA_PAY', 'CB_PAY', 'BANK_TRANSFER', 'CREDIT', 'OTHER')),
    constraint payment_amount_range_check check (amount > 0),
    -- tendered and change reproduce the drawer maths, cash only
    constraint payment_cash_only_check check (method = 'CASH'
        or (tendered_amount is null and change_amount is null)),
    constraint payment_change_check check (tendered_amount is null
        or (tendered_amount >= amount and change_amount = tendered_amount - amount))
);

create index payment_sale_idx on payment (sale_id);
