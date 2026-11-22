-- ---------------------------------------------------------------------------
-- The 24-hour memory of idempotency keys.
--
-- Section 3 of the assistant-surface contract: "A call that takes
-- `idempotency_key` and is repeated by the same caller in the same scope with
-- the same key, within 24 hours of the first, creates nothing and answers with
-- the answer the first call produced, in the object's current state. The same
-- key with different arguments is refused as IDEMPOTENCY_KEY_REUSED."
--
-- Until this migration the argument was declared, accepted and discarded: a
-- retried commission commissioned twice while the caller's tool description
-- promised it would not. That is the defect class this whole sub-sprint exists
-- to remove, sitting inside its own remedy.
--
-- ADDITIVE, AND N-1 COMPATIBLE (DEC-0018)
--
-- A new table and nothing else. No column dropped, none renamed, no constraint
-- narrowed. The release before this one does not read this table and is
-- unaffected by its presence; the release after it treats an absent row as a
-- first call, which is what an empty table gives it.
--
-- WHY A TABLE AND NOT A COLUMN ON `exchange`
--
-- The key belongs to a CALL, not to an exchange. A key presented for a call
-- that was refused records nothing; a key presented twice must be recognised
-- before the second call reaches the exchange it would create; and the key of
-- a commission is remembered before the exchange exists at all. A column would
-- have to hang the memory on the very row whose creation is in question.
--
-- WHY THE ARGUMENTS ARE STORED AS A DIGEST
--
-- The rule needs to tell "the same call again" from "a different call under a
-- key that was already spent". Comparing the arguments needs the arguments,
-- and storing them would put a commission's title and text in a second table
-- — doubling the surface the ops boundary has to withhold, for a comparison
-- that only ever asks "equal or not". A SHA-256 over the canonical form
-- answers exactly that question and carries nothing readable.
--
-- WHY IT CARRIES `tenant_id`
--
-- Because everything in this schema does. The completeness probe reads the
-- catalog for tables carrying the column and fails any that lacks a forced
-- policy, so the column and the policy below are one decision, not two.
-- ---------------------------------------------------------------------------

CREATE TABLE dispatch.idempotency_key (
    id              BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id       UUID         NOT NULL,
    scope_id        UUID         NOT NULL,

    -- Per caller and per scope, as section 3 words it. The subject is the
    -- authenticated caller; two callers may choose the same key without
    -- either seeing the other's answer.
    caller_subject  TEXT         NOT NULL,
    idempotency_key TEXT         NOT NULL,

    -- Which call the key was spent on, and on what. A key reused for a
    -- DIFFERENT call is a reuse even when the arguments happen to match.
    call_name       TEXT         NOT NULL,
    argument_digest TEXT         NOT NULL,

    -- What the first call answered with, by durable identity (ADR-0014). The
    -- repeat re-reads it, so the answer is the object's CURRENT state and not
    -- a replay of a stale projection — which is what section 3 asks for and is
    -- also the only version that cannot go stale.
    exchange_id     BIGINT       NOT NULL,

    first_seen_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT idempotency_key_once
        UNIQUE (scope_id, caller_subject, idempotency_key),

    -- RESTRICT for the reason V13 gives for the curation reference: there is
    -- no delete verb in this service, and the statement that this path stays
    -- closed is worth making explicitly.
    CONSTRAINT idempotency_key_exchange_fk
        FOREIGN KEY (exchange_id) REFERENCES dispatch.exchange (id)
        ON DELETE RESTRICT
);

-- Row-level security in the form V3 established, and required by the
-- completeness probe for every table carrying tenant_id.
ALTER TABLE dispatch.idempotency_key ENABLE ROW LEVEL SECURITY;
ALTER TABLE dispatch.idempotency_key FORCE  ROW LEVEL SECURITY;
CREATE POLICY idempotency_key_tenant_isolation ON dispatch.idempotency_key
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- A NEW GRANT, and it is the point of this block.
--
-- V13 needed none because it added columns to an already-granted table. This
-- adds a RELATION, and a relation nobody granted is a 500 on the first call
-- that touches it — the failure mode F-note-grant is named after. The three
-- privileges are the domain set: SELECT to recognise a repeat, INSERT to
-- remember a first call, UPDATE to replace an entry older than 24 hours.
-- No DELETE: an expired entry is overwritten in place, and no path in this
-- repository issues a DELETE against any table of this schema.
GRANT SELECT, INSERT, UPDATE ON dispatch.idempotency_key TO kumbuka_dispatch;

-- The identity column carries an implicit sequence, and a sequence a role
-- cannot advance makes the INSERT above fail at the default. V8 records that
-- this schema had none and that "a sequence added later needs its USAGE
-- written here with the migration that adds it"; this is that migration.
GRANT USAGE ON SEQUENCE dispatch.idempotency_key_id_seq TO kumbuka_dispatch;

COMMENT ON TABLE dispatch.idempotency_key IS
    'The 24-hour memory behind the idempotency_key argument: which caller '
    'spent which key on which call, and what the first call answered with.';
COMMENT ON COLUMN dispatch.idempotency_key.argument_digest IS
    'SHA-256 over the canonical form of the call''s arguments. Enough to tell '
    'a repeat from a reuse, and carrying nothing readable.';
COMMENT ON COLUMN dispatch.idempotency_key.exchange_id IS
    'The exchange the first call answered with, by durable identity. The '
    'repeat re-reads it, so the answer is the object''s current state.';
