-- V6 — build step 5b: payables, "who I owe" (finance). Posting a STOCK_IN from a supplier opens
-- a payable for Σ quantity × unit cost; supplier payments settle it. A stock-in paid on the spot
-- is still a payable plus an immediate settlement: one shape for every supplier debt (spec §7).
--
-- Same conventions as V1–V5. Deliberately a second table, not a generic one shared with
-- receivable: the queries and the screens diverge fast (spec §7).

create table payable (
    id                      uuid          primary key,
    version                 bigint        not null,
    created_at              timestamptz   not null,
    updated_at              timestamptz   not null,
    created_by              uuid,
    updated_by              uuid,
    organization_id         uuid          not null references organization (id),
    archived_at             timestamptz,
    supplier_id             uuid          not null,
    -- the location that received the goods
    location_id             uuid          not null,
    source_type             varchar(32)   not null,
    -- the stock document for STOCK_DOCUMENT; null for MANUAL (a debt from before Ordaro)
    source_id               uuid,
    reference_number        varchar(40),
    original_amount         numeric(19,4) not null,
    settled_amount          numeric(19,4) not null default 0,
    outstanding_amount      numeric(19,4) not null,
    issued_at               timestamptz   not null,
    due_date                date          not null,
    status                  varchar(32)   not null,
    cancelled_at            timestamptz,
    note                    varchar(500),
    constraint payable_organization_id_id_key unique (organization_id, id),
    constraint payable_supplier_fk foreign key (organization_id, supplier_id)
        references supplier (organization_id, id),
    constraint payable_location_fk foreign key (organization_id, location_id)
        references location (organization_id, id),
    constraint payable_source_type_check check (source_type in ('STOCK_DOCUMENT', 'MANUAL')),
    constraint payable_status_check check (status in ('OPEN', 'PARTIALLY_SETTLED', 'SETTLED', 'CANCELLED')),
    constraint payable_source_required_check check (source_type = 'MANUAL' or source_id is not null),
    constraint payable_amounts_range_check check (original_amount > 0 and settled_amount >= 0
        and outstanding_amount >= 0),
    -- CANCELLED (a voided, unpaid stock-in) owes nothing and was paid nothing
    constraint payable_amounts_balance_check check (
        (status = 'CANCELLED' and settled_amount = 0 and outstanding_amount = 0 and cancelled_at is not null)
        or (status <> 'CANCELLED' and settled_amount + outstanding_amount = original_amount)),
    constraint payable_status_amounts_check check (
        (status = 'OPEN' and settled_amount = 0)
        or (status = 'PARTIALLY_SETTLED' and settled_amount > 0 and outstanding_amount > 0)
        or (status = 'SETTLED' and outstanding_amount = 0)
        or status = 'CANCELLED')
);

-- one payable per stock-in
create unique index payable_stock_document_key on payable (source_id) where source_type = 'STOCK_DOCUMENT';
create index payable_due_idx on payable (organization_id, status, due_date);
create index payable_supplier_idx on payable (supplier_id, status);

-- A payment to a supplier. Paid from a drawer ⇒ cashier_shift_id is set and the shift's expected
-- cash goes down by it (spec §6, "What moves drawer cash").
create table payable_settlement (
    id                      uuid          primary key,
    version                 bigint        not null,
    created_at              timestamptz   not null,
    updated_at              timestamptz   not null,
    created_by              uuid,
    updated_by              uuid,
    organization_id         uuid          not null references organization (id),
    archived_at             timestamptz,
    payable_id              uuid          not null,
    -- where the money was paid from
    location_id             uuid          not null,
    cashier_shift_id        uuid,
    method                  varchar(32)   not null,
    amount                  numeric(19,4) not null,
    reference_no            varchar(100),
    paid_at                 timestamptz   not null,
    note                    varchar(500),
    idempotency_key         varchar(100),
    constraint payable_settlement_payable_fk foreign key (organization_id, payable_id)
        references payable (organization_id, id),
    constraint payable_settlement_location_fk foreign key (organization_id, location_id)
        references location (organization_id, id),
    constraint payable_settlement_cashier_shift_fk foreign key (organization_id, cashier_shift_id)
        references cashier_shift (organization_id, id),
    constraint payable_settlement_method_check check (method in
        ('CASH', 'KBZ_PAY', 'WAVE_PAY', 'AYA_PAY', 'CB_PAY', 'BANK_TRANSFER', 'CREDIT', 'OTHER')),
    -- the Payment enum without CREDIT: a supplier is paid, not credited
    constraint payable_settlement_no_credit_check check (method <> 'CREDIT'),
    constraint payable_settlement_amount_range_check check (amount > 0)
);

create index payable_settlement_payable_idx on payable_settlement (payable_id);
create index payable_settlement_shift_idx on payable_settlement (cashier_shift_id)
    where cashier_shift_id is not null;
create unique index payable_settlement_idempotency_key on payable_settlement (payable_id, idempotency_key)
    where idempotency_key is not null;
