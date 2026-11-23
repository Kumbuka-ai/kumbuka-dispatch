-- ---------------------------------------------------------------------------
-- The four values the process verbs store, and nothing else.
--
-- Additive, and compatible with the release before it. Every column is
-- nullable with no default and no back-fill: an exchange commissioned before
-- this migration was never cancelled, never questioned and never curated, and
-- writing a value that says so would be inventing history. NULL is the honest
-- statement that the act did not happen.
--
-- WHY FOUR COLUMNS AND NOT ONE JSONB
--
-- The tempting shape is one `process_values jsonb` holding all four. It is
-- refused for the reason the metadata column is NOT refused: metadata is the
-- caller's own field and its keys are the caller's business, whereas these
-- four are the service's own record of what happened. A service's own facts
-- in a free-form document cannot be constrained, cannot be joined on, and
-- cannot be found by a reader who does not already know the key. The one that
-- matters most is `curated_into_id`: it is a reference, and a reference in a
-- jsonb document is a reference nothing enforces.
--
-- NO NEW GRANT
--
-- The runtime role holds SELECT, INSERT, UPDATE on dispatch.exchange as a
-- whole (V8, line 299) rather than column by column, so a new column on an
-- already-granted table needs no new privilege. That is asserted rather than
-- assumed: the replay harness reads has_table_privilege, and a column-level
-- grant model would have needed one statement per column here.
-- ---------------------------------------------------------------------------

ALTER TABLE dispatch.exchange
    -- ADR-0014: a stored reference holds the durable identity, never the
    -- address. The address of the target can change -- a bracket renumbered,
    -- a selector renamed -- and a stored address would then point at whatever
    -- has since moved into that position, which is worse than pointing at
    -- nothing. The internal key cannot be re-issued, so it cannot go stale.
    ADD COLUMN curated_into_id BIGINT,

    -- What the commissioner said when it sent the exchange back, or answered
    -- the question the executor asked. One column for both, because they are
    -- one act from the exchange's side: dispatch_reply_to_executor.
    ADD COLUMN commissioner_message TEXT,

    -- What the executor could not decide. Its presence is ALSO what tells the
    -- two meanings of needs_input apart from the question side; the answer
    -- side is told by return_body. The contract (section 8) records that a
    -- dedicated state would be cleaner and does not build one here.
    ADD COLUMN executor_question TEXT,

    -- Why a commission was withdrawn, or why the work was declined. One
    -- column, because an exchange has at most one ending and the state says
    -- which of the two it was: closed without an accepted answer, rejected,
    -- or failed.
    ADD COLUMN termination_reason TEXT;

-- The reference is enforced, which is the whole reason it is a column.
-- RESTRICT rather than CASCADE or SET NULL: an exchange that carries somebody
-- else's answer forward is a fact about both, and deleting the target out from
-- under it would either destroy the carrier (CASCADE) or silently forget what
-- it was carried into (SET NULL). Neither is a thing this service may decide
-- on its own -- and there is no delete verb here anyway, so RESTRICT is the
-- statement that this path stays closed.
ALTER TABLE dispatch.exchange
    ADD CONSTRAINT exchange_curated_into_fk
    FOREIGN KEY (curated_into_id) REFERENCES dispatch.exchange (id)
    ON DELETE RESTRICT;

-- An exchange cannot be curated into itself. Not hypothetical tidiness: the
-- target of a curation is usually the bracket root of the exchange being
-- curated, and a caller that passes the exchange's own address instead is
-- making the one mistake the shape invites.
ALTER TABLE dispatch.exchange
    ADD CONSTRAINT exchange_curated_into_not_self
    CHECK (curated_into_id IS NULL OR curated_into_id <> id);

-- Read by the projection on every consumed exchange, and by nothing else.
-- Partial, because the column is null on all but the consumed ones and an
-- index over the nulls would be almost entirely dead weight.
CREATE INDEX exchange_curated_into_idx
    ON dispatch.exchange (curated_into_id)
    WHERE curated_into_id IS NOT NULL;

COMMENT ON COLUMN dispatch.exchange.curated_into_id IS
    'The object this exchange''s answer was carried forward into, by durable '
    'identity (ADR-0014). Null unless the exchange is consumed.';
COMMENT ON COLUMN dispatch.exchange.commissioner_message IS
    'What the commissioner said at dispatch_reply_to_executor: an answer to a '
    'question, or the rework a delivered answer needs.';
COMMENT ON COLUMN dispatch.exchange.executor_question IS
    'What the executor asked at dispatch_ask_commissioner. Its presence tells '
    'the question meaning of needs_input from the delivered-answer meaning.';
COMMENT ON COLUMN dispatch.exchange.termination_reason IS
    'Why the exchange ended without an accepted answer: the reason given at '
    'dispatch_cancel or at dispatch_decline.';
