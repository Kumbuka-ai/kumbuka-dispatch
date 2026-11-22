-- ===========================================================================
-- V11: fold the number circle into the selector row.
--
-- `dispatch.number_circle` carried one row per declared selector and one field
-- of interest — `next_number`. Its identity was the selector's identity
-- (`(tenant_id, scope_id, selector)` mirrors selector's `(tenant_id, scope_id,
-- name)`), it was inserted from V5 as a second statement next to the selector
-- declaration, and it referred to the selector by name across a boundary
-- neither a foreign key nor a check constraint stood on. Two rows carrying the
-- same identity, joined only by the selector's name, is the classical shape
-- for the two to drift apart — and this schema otherwise defends against
-- exactly that (see the exchange freeze in V4).
--
-- The circle's presence at bracket level does not change. Only its location:
-- the counter lives on the selector row it belongs to, where its identity is
-- the row's identity and no join is needed to reach it. `SELECT ... FOR
-- UPDATE` on the selector row is the same serialisation as before, and a
-- rolled-back creation still gives its number back — the transactional
-- guarantee moves with the column.
--
-- WHY A DROP HERE
--
-- Migrations in this service are additive by default. V6 named the one
-- exception and its sequencing rule: a drop is deliberate, the probes that
-- used to run against the table are rewritten onto its replacement FIRST, and
-- only then does the table go. The same rule holds here. The Java layer
-- (`NumberCircle` entity, `ExchangeRepository.lockNumberCircle`, the
-- `takeNextNumberOnCircle` helper in the metadata probe, `DomainFixture`'s
-- second INSERT) is rewritten onto `dispatch.selector.next_number` in the
-- same change that carries this migration. Nothing reads or writes the table
-- after this file runs.
--
-- The order below is fixed by the same principle that made V6 hard-sequence
-- its predecessor: add the column, copy the values, drop the old table. Each
-- step is a legal state on its own — a database left after step one is a
-- database with both counters that agree, and step two is a no-op there — so
-- an aborted migration cannot leave the schema unable to answer where the
-- counter lives.
-- ===========================================================================

-- 1. The counter, added to the selector row. NOT NULL with a default so the
--    existing rows adopt a legal value the same instant the column exists,
--    and a CHECK that matches the one number_circle carried — an invariant
--    stated once is not moved by moving the column it belongs to.
ALTER TABLE dispatch.selector
    ADD COLUMN next_number INTEGER NOT NULL DEFAULT 1,
    ADD CONSTRAINT ck_selector_next_number CHECK (next_number >= 1);

-- 2. The values, copied from the old table to the new column. Matched on the
--    identity the two tables have carried in parallel, tenant included so
--    that a scope-shared name across tenants does not cross the axis.
--    `number_circle.selector` is the selector's name; the join is on it and
--    on the scope.
UPDATE dispatch.selector s
   SET next_number = c.next_number
  FROM dispatch.number_circle c
 WHERE s.tenant_id = c.tenant_id
   AND s.scope_id  = c.scope_id
   AND s.name      = c.selector;

-- 3. The old table, dropped. Its grants (V8), its RLS policy (V4) and its
--    row-level-security enablement go with it — there is no separate REVOKE
--    or DROP POLICY needed. Nothing in this schema references it any more.
DROP TABLE dispatch.number_circle;
