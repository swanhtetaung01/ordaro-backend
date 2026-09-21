-- V3 — ledger order becomes a per-(location, product) sequence (spec §5, replaces (created_at, id)).
--
-- seq is assigned by the posting path while it holds the stock_balance row lock, from
-- stock_balance.last_seq, so it is gapless and in posting order for that key — whatever any
-- clock says. balance_after, the ledger view and the rebuild job all order by it; created_at
-- is informational only.

alter table stock_balance add column last_seq bigint not null default 0;
alter table stock_movement add column seq bigint;

-- Backfill rows posted before this migration in the only order they had. The append-only
-- trigger is lifted for this one statement, inside this migration's transaction.
alter table stock_movement disable trigger stock_movement_no_update_or_delete;
update stock_movement m
set seq = o.seq
from (select id,
             row_number() over (partition by organization_id, location_id, product_id
                                order by created_at, id) as seq
      from stock_movement) o
where o.id = m.id;
alter table stock_movement enable trigger stock_movement_no_update_or_delete;

update stock_balance b
set last_seq = coalesce((select max(m.seq) from stock_movement m
                         where m.organization_id = b.organization_id
                           and m.location_id = b.location_id
                           and m.product_id = b.product_id), 0);

alter table stock_movement alter column seq set not null;
alter table stock_movement add constraint stock_movement_seq_key
    unique (organization_id, location_id, product_id, seq);
alter table stock_movement add constraint stock_movement_seq_range_check check (seq > 0);
alter table stock_balance add constraint stock_balance_last_seq_range_check check (last_seq >= 0);

-- The unique constraint's index is the ledger index now.
drop index stock_movement_ledger_idx;
