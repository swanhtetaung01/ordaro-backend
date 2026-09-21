-- V2 — build step 2: inventory. The ledger (stock_movement), its projection (stock_balance),
-- stock documents, and the per-location document-number sequence.
--
-- Same conventions as V1: composite (organization_id, x_id) references between tenant tables,
-- enum columns VARCHAR(32) + CHECK <table>_<column>_check, quantities and money NUMERIC(19,4).
-- Default privileges from V1 already grant ordaro_app on every table created here.

-- ─────────────────────────────────────────────────────────────── document numbers

-- {locationCode}-{type}-{yy}-{seq}. A counter row per (location, type, year), incremented under
-- its row lock in the posting transaction: a rollback gives the number back, so books never skip.
-- Not a Postgres sequence (global, gaps). Moved up from step 3: stock documents need it now.
create table document_sequence (
    organization_id         uuid          not null references organization (id),
    location_id             uuid          not null,
    type                    varchar(32)   not null,
    year                    integer       not null,
    last_value              bigint        not null,
    constraint document_sequence_pkey primary key (location_id, type, year),
    constraint document_sequence_location_fk foreign key (organization_id, location_id)
        references location (organization_id, id),
    constraint document_sequence_type_check check (type in
        ('RCP', 'RTN', 'ORD', 'OPN', 'GRN', 'OUT', 'ADJ', 'TFR')),
    constraint document_sequence_last_value_range_check check (last_value > 0)
);

-- ─────────────────────────────────────────────────────────────── stock documents

create table stock_document (
    id                        uuid          primary key,
    version                   bigint        not null,
    created_at                timestamptz   not null,
    updated_at                timestamptz   not null,
    created_by                uuid,
    updated_by                uuid,
    organization_id           uuid          not null references organization (id),
    archived_at               timestamptz,
    type                      varchar(32)   not null,
    location_id               uuid          not null,
    counterparty_location_id  uuid,
    supplier_id               uuid,
    document_number           varchar(40),
    status                    varchar(32)   not null,
    occurred_at               timestamptz   not null,
    note                      varchar(500),
    posted_at                 timestamptz,
    voided_at                 timestamptz,
    constraint stock_document_organization_id_id_key unique (organization_id, id),
    constraint stock_document_location_fk foreign key (organization_id, location_id)
        references location (organization_id, id),
    constraint stock_document_counterparty_location_fk foreign key (organization_id, counterparty_location_id)
        references location (organization_id, id),
    constraint stock_document_supplier_fk foreign key (organization_id, supplier_id)
        references supplier (organization_id, id),
    constraint stock_document_type_check check (type in
        ('OPENING', 'STOCK_IN', 'STOCK_OUT', 'ADJUSTMENT', 'TRANSFER')),
    constraint stock_document_status_check check (status in ('DRAFT', 'POSTED', 'VOID')),
    -- a TRANSFER names its destination; nothing else does; never itself
    constraint stock_document_counterparty_required_check check
        ((type = 'TRANSFER') = (counterparty_location_id is not null)),
    constraint stock_document_counterparty_distinct_check check (counterparty_location_id <> location_id),
    -- numbers are assigned on posting (a draft that is never posted burns no number)
    constraint stock_document_number_required_check check (status <> 'POSTED' or document_number is not null)
);

create unique index stock_document_number_key on stock_document (organization_id, document_number)
    where document_number is not null;
create index stock_document_recent_idx on stock_document (organization_id, created_at desc);

create table stock_document_line (
    id                      uuid          primary key,
    version                 bigint        not null,
    created_at              timestamptz   not null,
    updated_at              timestamptz   not null,
    created_by              uuid,
    updated_by              uuid,
    organization_id         uuid          not null references organization (id),
    archived_at             timestamptz,
    document_id             uuid          not null,
    position                integer       not null,
    product_id              uuid          not null,
    -- positive on every type except ADJUSTMENT, where it is the signed difference from the count
    quantity                numeric(19,4) not null,
    unit_cost               numeric(19,4),
    reason                  varchar(32),
    constraint stock_document_line_document_fk foreign key (organization_id, document_id)
        references stock_document (organization_id, id),
    constraint stock_document_line_product_fk foreign key (organization_id, product_id)
        references product (organization_id, id),
    constraint stock_document_line_position_key unique (document_id, position),
    constraint stock_document_line_reason_check check (reason in
        ('DAMAGED', 'EXPIRED', 'INTERNAL_USE', 'SAMPLE', 'THEFT', 'COUNT_CORRECTION', 'VOID_REVERSAL')),
    constraint stock_document_line_quantity_nonzero_check check (quantity <> 0),
    constraint stock_document_line_unit_cost_range_check check (unit_cost >= 0)
);

create index stock_document_line_document_idx on stock_document_line (document_id);

-- ─────────────────────────────────────────────────────────────── projection

-- One row per (location, product), created lazily by the posting path with
-- INSERT … ON CONFLICT DO NOTHING, then locked FOR UPDATE. Fully rebuildable from the ledger.
create table stock_balance (
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
    quantity                numeric(19,4) not null default 0,
    -- the only stored cost in the system (spec §5)
    average_cost            numeric(19,4) not null default 0,
    last_movement_at        timestamptz,
    constraint stock_balance_key unique (location_id, product_id),
    constraint stock_balance_location_fk foreign key (organization_id, location_id)
        references location (organization_id, id),
    constraint stock_balance_product_fk foreign key (organization_id, product_id)
        references product (organization_id, id),
    constraint stock_balance_average_cost_range_check check (average_cost >= 0)
);

-- ─────────────────────────────────────────────────────────────── ledger

-- Append-only. Signed quantity: current stock is a plain SUM. Ledger order is (created_at, id).
create table stock_movement (
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
    type                    varchar(32)   not null,
    quantity                numeric(19,4) not null,
    unit_cost               numeric(19,4) not null,
    balance_after           numeric(19,4) not null,
    reference_type          varchar(32)   not null,
    reference_id            uuid          not null,
    reference_number        varchar(40),
    reason                  varchar(32),
    batch_no                varchar(64),
    expiry_date             date,
    moved_at                timestamptz   not null,
    constraint stock_movement_location_fk foreign key (organization_id, location_id)
        references location (organization_id, id),
    constraint stock_movement_product_fk foreign key (organization_id, product_id)
        references product (organization_id, id),
    constraint stock_movement_type_check check (type in
        ('OPENING', 'STOCK_IN', 'STOCK_OUT', 'SALE', 'SALE_RETURN', 'TRANSFER_IN', 'TRANSFER_OUT', 'ADJUSTMENT')),
    constraint stock_movement_reference_type_check check (reference_type in ('SALE', 'RETURN', 'STOCK_DOCUMENT')),
    constraint stock_movement_reason_check check (reason in
        ('DAMAGED', 'EXPIRED', 'INTERNAL_USE', 'SAMPLE', 'THEFT', 'COUNT_CORRECTION', 'VOID_REVERSAL')),
    -- the sign the type implies
    constraint stock_movement_sign_check check (
        (type in ('OPENING', 'STOCK_IN', 'TRANSFER_IN', 'SALE_RETURN') and quantity > 0)
        or (type in ('STOCK_OUT', 'SALE', 'TRANSFER_OUT') and quantity < 0)
        or (type = 'ADJUSTMENT' and quantity <> 0)),
    constraint stock_movement_reason_required_check check
        (type not in ('STOCK_OUT', 'ADJUSTMENT') or reason is not null),
    constraint stock_movement_unit_cost_range_check check (unit_cost >= 0)
);

create index stock_movement_ledger_idx on stock_movement (organization_id, location_id, product_id, created_at, id);
create index stock_movement_reference_idx on stock_movement (reference_type, reference_id);

-- Belt and braces: the app role may only insert and read; a trigger stops the owner too.
revoke update, delete, truncate on stock_movement from ordaro_app;

create function stock_movement_append_only() returns trigger
    language plpgsql as $$
begin
    raise exception 'stock_movement is append-only: % refused; post a correcting movement instead', tg_op;
end;
$$;

create trigger stock_movement_no_update_or_delete
    before update or delete on stock_movement
    for each row execute function stock_movement_append_only();

create trigger stock_movement_no_truncate
    before truncate on stock_movement
    for each statement execute function stock_movement_append_only();
