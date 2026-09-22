-- V8 — build step 5d: expenses (finance). Rent, electricity, transport: money out that is not a
-- supplier debt. Per location, so branch profitability is answerable. One paid from a drawer
-- names its shift and completes the drawer rule (spec §6, "What moves drawer cash").
--
-- Same conventions as V1–V7.

-- Its own entity, separate from the product category (spec §7).
create table expense_category (
    id                      uuid          primary key,
    version                 bigint        not null,
    created_at              timestamptz   not null,
    updated_at              timestamptz   not null,
    created_by              uuid,
    updated_by              uuid,
    organization_id         uuid          not null references organization (id),
    archived_at             timestamptz,
    name                    varchar(120)  not null,
    constraint expense_category_organization_id_id_key unique (organization_id, id)
);

create unique index expense_category_name_key on expense_category (organization_id, lower(name));

create table expense (
    id                      uuid          primary key,
    version                 bigint        not null,
    created_at              timestamptz   not null,
    updated_at              timestamptz   not null,
    created_by              uuid,
    updated_by              uuid,
    organization_id         uuid          not null references organization (id),
    archived_at             timestamptz,
    category_id             uuid          not null,
    location_id             uuid          not null,
    -- set ⇒ cash left that drawer
    cashier_shift_id        uuid,
    amount                  numeric(19,4) not null,
    method                  varchar(32)   not null,
    -- business time
    paid_at                 timestamptz   not null,
    description             varchar(500),
    reference_no            varchar(100),
    -- a mistaken expense is voided, never deleted: the drawer count already happened
    voided_at               timestamptz,
    constraint expense_category_fk foreign key (organization_id, category_id)
        references expense_category (organization_id, id),
    constraint expense_location_fk foreign key (organization_id, location_id)
        references location (organization_id, id),
    constraint expense_cashier_shift_fk foreign key (organization_id, cashier_shift_id)
        references cashier_shift (organization_id, id),
    -- the Payment enum without CREDIT (spec §7)
    constraint expense_method_check check (method in
        ('CASH', 'KBZ_PAY', 'WAVE_PAY', 'AYA_PAY', 'CB_PAY', 'BANK_TRANSFER', 'OTHER')),
    constraint expense_amount_range_check check (amount > 0),
    constraint expense_shift_cash_only_check check (cashier_shift_id is null or method = 'CASH')
);

create index expense_paid_at_idx on expense (organization_id, location_id, paid_at);
create index expense_shift_idx on expense (cashier_shift_id) where cashier_shift_id is not null;
