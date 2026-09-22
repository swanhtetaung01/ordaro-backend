-- V7 — build step 5c: returns (sales). A return is its own document against a completed sale,
-- never a negative sale (spec §6): revenue queries need no sign filter. Restocked lines come back
-- through the ledger as SALE_RETURN at the line's original unit cost; damaged goods refund the
-- money and leave stock and COGS alone (spec §9.3).
--
-- Same conventions as V1–V6. "return" is a reserved word, so the table is sale_return.

-- A CREDIT-method settlement is how a return on a credit sale reduces the debt (spec §7); V5
-- forbade CREDIT because no return existed yet.
alter table receivable_settlement drop constraint receivable_settlement_no_credit_check;

-- Composite references into sale_line need this key (V4 did not add it).
alter table sale_line add constraint sale_line_organization_id_id_key unique (organization_id, id);

create table sale_return (
    id                      uuid          primary key,
    version                 bigint        not null,
    created_at              timestamptz   not null,
    updated_at              timestamptz   not null,
    created_by              uuid,
    updated_by              uuid,
    organization_id         uuid          not null references organization (id),
    archived_at             timestamptz,
    original_sale_id        uuid          not null,
    -- the STORE handling the return: numbers it and, when restocking, receives the goods
    location_id             uuid          not null,
    -- set when cash left a drawer
    cashier_shift_id        uuid,
    return_number           varchar(40)   not null,
    refund_method           varchar(32)   not null,
    -- Σ line refunds; tax mirrored the same way
    refund_amount           numeric(19,4) not null,
    tax_amount              numeric(19,4) not null,
    reference_no            varchar(100),
    reason                  varchar(500),
    -- business time
    returned_at             timestamptz   not null,
    -- client-supplied: a retried return is recorded once
    idempotency_key         varchar(100),
    constraint sale_return_organization_id_id_key unique (organization_id, id),
    constraint sale_return_original_sale_fk foreign key (organization_id, original_sale_id)
        references sale (organization_id, id),
    constraint sale_return_location_fk foreign key (organization_id, location_id)
        references location (organization_id, id),
    constraint sale_return_cashier_shift_fk foreign key (organization_id, cashier_shift_id)
        references cashier_shift (organization_id, id),
    constraint sale_return_refund_method_check check (refund_method in
        ('CASH', 'KBZ_PAY', 'WAVE_PAY', 'AYA_PAY', 'CB_PAY', 'BANK_TRANSFER', 'CREDIT', 'OTHER')),
    constraint sale_return_amounts_range_check check (refund_amount > 0 and tax_amount >= 0
        and tax_amount <= refund_amount)
);

create unique index sale_return_number_key on sale_return (organization_id, return_number);
create unique index sale_return_idempotency_key on sale_return (original_sale_id, idempotency_key)
    where idempotency_key is not null;
create index sale_return_original_sale_idx on sale_return (original_sale_id);
create index sale_return_returned_at_idx on sale_return (organization_id, location_id, returned_at);
create index sale_return_shift_idx on sale_return (cashier_shift_id) where cashier_shift_id is not null;

create table sale_return_line (
    id                      uuid          primary key,
    version                 bigint        not null,
    created_at              timestamptz   not null,
    updated_at              timestamptz   not null,
    created_by              uuid,
    updated_by              uuid,
    organization_id         uuid          not null references organization (id),
    archived_at             timestamptz,
    return_id               uuid          not null,
    -- the exact line, so partial returns work and Σ returned ≤ sold per line
    sale_line_id            uuid          not null,
    product_id              uuid          not null,
    quantity                numeric(19,4) not null,
    -- tax-inclusive, pro-rata of the original line_total; tax mirrored the same way
    refund_amount           numeric(19,4) not null,
    tax_amount              numeric(19,4) not null,
    -- copied from the sale line: what the goods cost when they went out
    unit_cost               numeric(19,4) not null,
    -- false for damaged goods: the refund happens, the movement does not, COGS stays
    restock                 boolean       not null,
    constraint sale_return_line_return_fk foreign key (organization_id, return_id)
        references sale_return (organization_id, id),
    constraint sale_return_line_sale_line_fk foreign key (organization_id, sale_line_id)
        references sale_line (organization_id, id),
    constraint sale_return_line_product_fk foreign key (organization_id, product_id)
        references product (organization_id, id),
    constraint sale_return_line_quantity_range_check check (quantity > 0),
    constraint sale_return_line_amounts_range_check check (refund_amount >= 0 and tax_amount >= 0
        and tax_amount <= refund_amount and unit_cost >= 0)
);

create index sale_return_line_return_idx on sale_return_line (return_id);
create index sale_return_line_sale_line_idx on sale_return_line (sale_line_id);
