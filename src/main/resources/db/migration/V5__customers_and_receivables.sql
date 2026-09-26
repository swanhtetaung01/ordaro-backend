-- V5 — build step 5a: customers (crm) and receivables (finance). A CREDIT payment at checkout
-- opens a receivable; repayments are settlements against it, never more payments on the sale.
--
-- Same conventions as V1–V4. Receivable carries counterparty_type (only CUSTOMER for now) and one
-- typed, foreign-keyed column per counterparty kind: COD adds courier_id later without reshaping
-- the table (spec §12 Locations, "Step 5 as built").

-- ─────────────────────────────────────────────────────────────── customers

-- Walk-ins are not customers: they are a null customer_id on the sale (spec §8).
create table customer (
    id                      uuid          primary key,
    version                 bigint        not null,
    created_at              timestamptz   not null,
    updated_at              timestamptz   not null,
    created_by              uuid,
    updated_by              uuid,
    organization_id         uuid          not null references organization (id),
    archived_at             timestamptz,
    name                    varchar(200)  not null,
    -- the real identifier in this market; E.164
    phone                   varchar(20),
    type                    varchar(32)   not null,
    default_price_type      varchar(32)   not null,
    -- 0 means no credit: a CREDIT payment needs a limit set by the owner
    credit_limit            numeric(19,4) not null default 0,
    credit_term_days        integer       not null default 0,
    loyalty_points          integer       not null default 0,
    address                 varchar(500),
    note                    varchar(500),
    constraint customer_organization_id_id_key unique (organization_id, id),
    constraint customer_type_check check (type in ('MEMBER', 'B2B')),
    constraint customer_default_price_type_check check (default_price_type in ('RETAIL', 'WHOLESALE')),
    constraint customer_credit_limit_range_check check (credit_limit >= 0),
    constraint customer_credit_term_days_range_check check (credit_term_days between 0 and 365),
    constraint customer_loyalty_points_range_check check (loyalty_points >= 0)
);

create unique index customer_phone_key on customer (organization_id, phone) where phone is not null;
create index customer_name_idx on customer (organization_id, lower(name));

-- V4 left sale.customer_id without a foreign key until this table existed.
alter table sale add constraint sale_customer_fk foreign key (organization_id, customer_id)
    references customer (organization_id, id);

-- ─────────────────────────────────────────────────────────────── receivables

-- "Who owes me money": outstanding_amount is the live truth; sale.due_amount is frozen history.
create table receivable (
    id                      uuid          primary key,
    version                 bigint        not null,
    created_at              timestamptz   not null,
    updated_at              timestamptz   not null,
    created_by              uuid,
    updated_by              uuid,
    organization_id         uuid          not null references organization (id),
    archived_at             timestamptz,
    counterparty_type       varchar(32)   not null,
    customer_id             uuid,
    -- where the credit was given; a repayment may be taken anywhere
    location_id             uuid          not null,
    source_type             varchar(32)   not null,
    -- the sale for SALE; null for MANUAL (a debt carried over from before TrilloPOS)
    source_id               uuid,
    reference_number        varchar(40),
    original_amount         numeric(19,4) not null,
    settled_amount          numeric(19,4) not null default 0,
    written_off_amount      numeric(19,4) not null default 0,
    outstanding_amount      numeric(19,4) not null,
    issued_at               timestamptz   not null,
    -- OVERDUE is a query on due_date, never a status a job must set
    due_date                date          not null,
    status                  varchar(32)   not null,
    written_off_at          timestamptz,
    note                    varchar(500),
    constraint receivable_organization_id_id_key unique (organization_id, id),
    constraint receivable_customer_fk foreign key (organization_id, customer_id)
        references customer (organization_id, id),
    constraint receivable_location_fk foreign key (organization_id, location_id)
        references location (organization_id, id),
    constraint receivable_counterparty_type_check check (counterparty_type in ('CUSTOMER')),
    constraint receivable_source_type_check check (source_type in ('SALE', 'ORDER', 'MANUAL')),
    constraint receivable_status_check check (status in ('OPEN', 'PARTIALLY_SETTLED', 'SETTLED', 'WRITTEN_OFF')),
    -- a credit sale to a nameless walk-in is not allowed, and the model says so
    constraint receivable_counterparty_required_check check (counterparty_type <> 'CUSTOMER'
        or customer_id is not null),
    constraint receivable_source_required_check check (source_type = 'MANUAL' or source_id is not null),
    constraint receivable_amounts_range_check check (original_amount > 0 and settled_amount >= 0
        and written_off_amount >= 0 and outstanding_amount >= 0),
    constraint receivable_amounts_balance_check check (settled_amount + written_off_amount
        + outstanding_amount = original_amount),
    constraint receivable_status_amounts_check check (
        (status = 'OPEN' and settled_amount = 0 and written_off_amount = 0)
        or (status = 'PARTIALLY_SETTLED' and settled_amount > 0 and outstanding_amount > 0
            and written_off_amount = 0)
        or (status = 'SETTLED' and outstanding_amount = 0 and written_off_amount = 0)
        or (status = 'WRITTEN_OFF' and outstanding_amount = 0 and written_off_amount > 0
            and written_off_at is not null))
);

-- one receivable per credit sale
create unique index receivable_sale_key on receivable (source_id) where source_type = 'SALE';
create index receivable_due_idx on receivable (organization_id, status, due_date);
create index receivable_customer_idx on receivable (customer_id, status) where customer_id is not null;

-- Partial repayments are many rows, each with its own method and reference. A cash repayment
-- taken at a register names the shift: it is cash into that drawer.
create table receivable_settlement (
    id                      uuid          primary key,
    version                 bigint        not null,
    created_at              timestamptz   not null,
    updated_at              timestamptz   not null,
    created_by              uuid,
    updated_by              uuid,
    organization_id         uuid          not null references organization (id),
    archived_at             timestamptz,
    receivable_id           uuid          not null,
    -- where the money was received
    location_id             uuid          not null,
    cashier_shift_id        uuid,
    method                  varchar(32)   not null,
    amount                  numeric(19,4) not null,
    reference_no            varchar(100),
    paid_at                 timestamptz   not null,
    note                    varchar(500),
    -- client-supplied: a retried repayment returns the first instead of paying twice
    idempotency_key         varchar(100),
    constraint receivable_settlement_receivable_fk foreign key (organization_id, receivable_id)
        references receivable (organization_id, id),
    constraint receivable_settlement_location_fk foreign key (organization_id, location_id)
        references location (organization_id, id),
    constraint receivable_settlement_cashier_shift_fk foreign key (organization_id, cashier_shift_id)
        references cashier_shift (organization_id, id),
    constraint receivable_settlement_method_check check (method in
        ('CASH', 'KBZ_PAY', 'WAVE_PAY', 'AYA_PAY', 'CB_PAY', 'BANK_TRANSFER', 'CREDIT', 'OTHER')),
    -- CREDIT here would mean "a return reduced the debt"; returns arrive in step 5c
    constraint receivable_settlement_no_credit_check check (method <> 'CREDIT'),
    constraint receivable_settlement_amount_range_check check (amount > 0)
);

create index receivable_settlement_receivable_idx on receivable_settlement (receivable_id);
create index receivable_settlement_shift_idx on receivable_settlement (cashier_shift_id)
    where cashier_shift_id is not null;
create unique index receivable_settlement_idempotency_key on receivable_settlement (receivable_id, idempotency_key)
    where idempotency_key is not null;
