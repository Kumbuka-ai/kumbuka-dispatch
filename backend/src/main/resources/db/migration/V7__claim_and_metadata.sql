-- ===========================================================================
-- V7: the claim, its clock, and the caller's own field.
--
-- Additive throughout. Every column below is nullable, and no existing
-- constraint is narrowed: an image running the previous version keeps working
-- against this schema, which is what makes a rollback a rollback rather than
-- an outage.
--
-- THE RECEIPT IS STORED AS A HASH
--
-- The receipt is a bearer token in the literal sense: whoever presents it is
-- treated as the holder. Storing it in the clear would mean that every copy of
-- the database — a backup, a dump taken for debugging, a replica — carries
-- working credentials for every open claim. The service compares a hash, which
-- is all it ever needs to do, and the only copy of the token itself is the one
-- handed to the winner.
--
-- THE CLOCK SITS ON THE CLAIM, NOT ON THE STATUS
--
-- Expiry does not move the exchange. It makes it claimable again, and the
-- transition is written by the NEXT claimant, in that claimant's transaction,
-- with that claimant as the actor. So there is no reaper, no release verb for
-- the clock, and no expiry event — and the rule that every audit entry has a
-- verb call and an actor holds without an exception carved out for a
-- background job.
--
-- The consequence is that a stored holder is not an effective holder. Every
-- read surface has to project the effective state, and the failure when one
-- does not is silent: a free exchange displayed as taken.
-- ===========================================================================

ALTER TABLE dispatch.exchange
    -- Who holds the claim. Read for the effective projection and for the
    -- refusal message; never used on its own to authorise a write, because a
    -- subject is not a secret and several runs can share a service identity.
    ADD COLUMN holder_subject      TEXT,

    -- SHA-256 of the receipt, hex-encoded. Never the receipt itself.
    ADD COLUMN holder_receipt_hash TEXT,

    -- When the claim stops being effective. Nothing acts on this; it is read.
    ADD COLUMN claim_expires_at    TIMESTAMPTZ,

    -- The caller's own field, one per role, frozen at that role's gate.
    --
    -- The rule that keeps it from becoming a junk drawer: metadata carry an
    -- address or an identifier, never an assertion. A pull-request URL is an
    -- address and a case number is an identifier; "the review found structural
    -- problems" is an assertion and belongs in the body, because assertions
    -- are exactly what the freeze protects.
    ADD COLUMN dispatch_metadata   JSONB,
    ADD COLUMN handover_metadata   JSONB;

-- A claim is either whole or absent. Half a claim — a holder with no clock, a
-- clock with no holder — is a state nothing could act on sensibly, and the
-- effective projection would have to invent a rule for it.
ALTER TABLE dispatch.exchange
    ADD CONSTRAINT ck_claim_whole CHECK (
        (holder_subject IS NULL AND holder_receipt_hash IS NULL AND claim_expires_at IS NULL)
        OR (holder_subject IS NOT NULL AND holder_receipt_hash IS NOT NULL
            AND claim_expires_at IS NOT NULL));

-- ---------------------------------------------------------------------------
-- The freeze, extended to metadata.
--
-- Metadata are write-once and frozen at the same gate as everything else:
-- `send` for the dispatch, ratification for the handover. Write-once rather
-- than mutable for two reasons — it needs no new mechanism, no exception to
-- the freeze and no separate rule for a new field class; and loosening later
-- is additive while tightening later breaks callers.
--
-- Replaces the function of V4 rather than adding a second trigger: two
-- triggers refusing overlapping writes would each be correct and together
-- would make the reason for any given refusal a matter of which fired first.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION dispatch.refuse_frozen_writes() RETURNS TRIGGER AS $$
BEGIN
    IF OLD.sent_at IS NULL THEN
        -- Still a draft: fully mutable, by design.
        RETURN NEW;
    END IF;

    IF NEW.title <> OLD.title
       OR NEW.body <> OLD.body
       OR NEW.apparatus <> OLD.apparatus
       OR NEW.dispatch_date <> OLD.dispatch_date
       OR NEW.sent_at <> OLD.sent_at THEN
        RAISE EXCEPTION
            'exchange %.%.% is frozen: title, body, apparatus, date and sent_at cannot '
            'change after send. Corrections attach as an addendum.',
            OLD.selector, OLD.number, OLD.sub
            USING ERRCODE = 'raise_exception';
    END IF;

    IF NEW.dispatch_metadata IS DISTINCT FROM OLD.dispatch_metadata THEN
        RAISE EXCEPTION
            'the metadata of exchange %.%.% were written with the dispatch and frozen at '
            'send. They are write-once: a pointer that changes is a pointer whose readers '
            'cannot tell which one they followed.',
            OLD.selector, OLD.number, OLD.sub
            USING ERRCODE = 'raise_exception';
    END IF;

    IF NEW.created_at <> OLD.created_at
       OR NEW.created_by IS DISTINCT FROM OLD.created_by THEN
        RAISE EXCEPTION 'created_at and created_by are server-derived and unwritable'
            USING ERRCODE = 'raise_exception';
    END IF;

    -- A ratified handover is frozen at the same gate as the dispatch. Before
    -- ratification the draft is deliberately NOT protected here: overwriting
    -- it wholesale is the normal way rework happens.
    IF OLD.ratified_at IS NOT NULL
       AND (NEW.handover_body IS DISTINCT FROM OLD.handover_body
            OR NEW.ratified_at <> OLD.ratified_at
            OR NEW.handover_metadata IS DISTINCT FROM OLD.handover_metadata) THEN
        RAISE EXCEPTION
            'the handover of exchange %.%.% is ratified and frozen',
            OLD.selector, OLD.number, OLD.sub
            USING ERRCODE = 'raise_exception';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
