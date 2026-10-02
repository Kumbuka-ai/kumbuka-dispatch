-- ---------------------------------------------------------------------------
-- EVERY FOREIGN KEY CARRIES THE TENANT -- ADR-0042 tenant pairing
--
-- V12, V13 and V14 declared three keys, each on one bigint column:
--
--     exchange.selector_id        -> selector.id
--     exchange.curated_into_id    -> exchange.id
--     idempotency_key.exchange_id -> exchange.id
--
-- A foreign key is checked with row-level security bypassed. A key on the
-- id alone therefore binds a row to its target whatever tenant the target
-- belongs to, and the pairing of the two rows rests on the policies and on
-- every writer getting the id right. ADR-0042 puts the tenant into every key
-- so the store refuses a pairing across tenants itself.
--
-- WHAT THIS DOES
--
--   1. UNIQUE (tenant_id, id) on `selector` and `exchange`, the target a
--      composite key needs. `id` is already the primary key, so the pair is
--      unique by construction and the constraint can only be added.
--   2. The three keys again, on (tenant_id, <ref>) -> (tenant_id, id), with
--      the same ON DELETE / ON UPDATE behaviour as the ones they replace.
--      Added NOT VALID so the ALTER does not hold the table while it scans,
--      then validated, which scans under a weaker lock and fails loudly on a
--      row whose target lives in another tenant.
--   3. The single-column keys are dropped. Each is implied by its composite
--      successor, so no row that was refused before is admitted now.
--
-- No column is added, changed or dropped, and no code reads a constraint
-- name, so the image before this one runs unchanged against this schema.
--
-- WHAT THIS DOES NOT SETTLE
--
-- `curated_into_id` may name an exchange in ANOTHER SCOPE of the same tenant
-- (the curate verb admits any scope the caller can see). ADR-0042 holds a
-- reference across scopes as the target's durable identity and never as a
-- declared key. This migration makes that key tenant-bound; whether it may
-- remain a declared key at all is open and is not decided here.
-- ---------------------------------------------------------------------------

ALTER TABLE dispatch.selector ADD CONSTRAINT uq_selector_tenant_id UNIQUE (tenant_id, id);
ALTER TABLE dispatch.exchange ADD CONSTRAINT uq_exchange_tenant_id UNIQUE (tenant_id, id);

ALTER TABLE dispatch.exchange
    ADD CONSTRAINT fk_exchange_selector_tenant
        FOREIGN KEY (tenant_id, selector_id) REFERENCES dispatch.selector (tenant_id, id)
        ON DELETE RESTRICT ON UPDATE RESTRICT
        NOT VALID;
ALTER TABLE dispatch.exchange
    ADD CONSTRAINT fk_exchange_curated_into_tenant
        FOREIGN KEY (tenant_id, curated_into_id) REFERENCES dispatch.exchange (tenant_id, id)
        ON DELETE RESTRICT
        NOT VALID;
ALTER TABLE dispatch.idempotency_key
    ADD CONSTRAINT fk_idempotency_key_exchange_tenant
        FOREIGN KEY (tenant_id, exchange_id) REFERENCES dispatch.exchange (tenant_id, id)
        ON DELETE RESTRICT
        NOT VALID;

ALTER TABLE dispatch.exchange        VALIDATE CONSTRAINT fk_exchange_selector_tenant;
ALTER TABLE dispatch.exchange        VALIDATE CONSTRAINT fk_exchange_curated_into_tenant;
ALTER TABLE dispatch.idempotency_key VALIDATE CONSTRAINT fk_idempotency_key_exchange_tenant;

ALTER TABLE dispatch.exchange        DROP CONSTRAINT fk_exchange_selector;
ALTER TABLE dispatch.exchange        DROP CONSTRAINT exchange_curated_into_fk;
ALTER TABLE dispatch.idempotency_key DROP CONSTRAINT idempotency_key_exchange_fk;
