-- Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
-- SPDX-License-Identifier: AGPL-3.0-only
-- This file is part of Kumbuka and is licensed under the GNU Affero
-- General Public License v3.0 only. See the LICENSE file in the
-- repository root for the full licence text.

-- ===========================================================================
-- V16_1: an answer written on an addendum becomes an addendum of its own.
--
-- Runs after V16 and before V17. A database that stands at V16 takes it as
-- the next migration; V17 is not changed.
--
-- WHY IT IS NEEDED
--
-- V17 copies `exchange` into `task` and `task_text`, and an addendum becomes
-- a dispatch text with its letter on the task of its address. An addendum
-- has room for its title and its text and for nothing else, so V17 stops
-- with KD003 on every addendum row that carries a text besides its own:
--
--     addendum_suffix IS NOT NULL
--     AND (return_body IS NOT NULL OR executor_question IS NOT NULL
--          OR commissioner_message IS NOT NULL OR termination_reason IS NOT NULL)
--
-- The stock this was written against holds two such rows: an answer that was
-- written into the return of an addendum. V17 stops on them, and rolls back.
--
-- THE RULE IT APPLIES
--
-- An answer to an addendum is an addendum beside it, under the same root,
-- titled "Antwort auf <address of the addendum it answers>" (transition rule
-- of 2026-10-16). For each row matching the condition above, word for word:
--
--   1. A new addendum row at the same address with the next free letter.
--      Title: "Antwort auf <selector>/<number>.<sub><letter>". Text: the
--      texts the row carries, in a fixed order -- return, question, message,
--      reason -- each under a heading of its own. Status, dispatch date, send
--      time and creation and change stamps are the answered addendum's, so
--      the new row is sent as that one was. Apparatus: the one the answer is
--      addressed to, the apparatus that commissioned the answered addendum.
--      No column of `exchange` records that, so it is `concept`.
--   2. The four columns of the answered addendum are emptied.
--
-- The address in the title carries no scope: the database holds the scope's
-- id and not the name a caller uses, and the answer stands in the same scope
-- as the addendum it answers.
--
-- AN ANSWER THAT ALREADY STANDS
--
-- A row whose answer already stands -- an addendum under the same root with
-- that title -- gets no second one. If the text of every such addendum is
-- byte for byte the text step 1 would write for the row, the four columns
-- are emptied as in step 2 and the row is counted as skipped. If one differs,
-- the run stops with KD003 and names the answered addendum by selector,
-- number, sub and letter: which of the two texts is the answer is not this
-- migration's to decide.
--
-- Every row matching the condition is therefore emptied, beside an answer
-- the run wrote or beside one that already stood, or the run stops. After a run no row matches the condition, so a second run
-- finds nothing to do.
--
-- READING ACROSS EVERY TENANT, AND PAST THE FREEZE
--
-- TenantMigrationCallback binds one tenant, and `exchange` and `selector` are
-- under FORCE ROW LEVEL SECURITY, so FORCE is lifted from both for the run and
-- put back before it ends, as V17 does for its sources and for the same
-- reason: through the policy the run would see the bound tenant's rows and
-- silently none of any other. `selector` is read for the name in the title.
-- Every answered addendum carries a ratified return, and `exchange_freeze`
-- refuses a change to a ratified return; the trigger is disabled for the run
-- and enabled again right after it. Both happen inside this
-- migration's transaction, under the ACCESS EXCLUSIVE lock the first ALTER
-- takes, so no other session sees the table without them.
--
-- A stop rolls the whole migration back, these ALTERs with it: the catalogue
-- is then as it was before the run.
--
-- The run ends with a NOTICE naming how many rows it moved and skipped.
-- ===========================================================================

ALTER TABLE dispatch.exchange NO FORCE ROW LEVEL SECURITY;
ALTER TABLE dispatch.selector NO FORCE ROW LEVEL SECURITY;
ALTER TABLE dispatch.exchange DISABLE TRIGGER exchange_freeze;

DO $do$
DECLARE
    answered     RECORD;
    answer_title TEXT;
    answer_text  TEXT;
    letter       TEXT;
    code         INTEGER;
    moved        INTEGER := 0;
    skipped      INTEGER := 0;
BEGIN
    FOR answered IN
        SELECT e.*, s.name AS selector_name
          FROM dispatch.exchange e
          JOIN dispatch.selector s ON s.tenant_id = e.tenant_id AND s.id = e.selector_id
         WHERE e.addendum_suffix IS NOT NULL
           AND (e.return_body IS NOT NULL OR e.executor_question IS NOT NULL
                OR e.commissioner_message IS NOT NULL OR e.termination_reason IS NOT NULL)
         ORDER BY e.tenant_id, e.scope_id, s.name, e.number, e.sub, e.addendum_suffix
    LOOP
        answer_title := 'Antwort auf ' || answered.selector_name || '/' || answered.number || '.'
                 || answered.sub || answered.addendum_suffix;

        answer_text := concat_ws(E'\n\n',
            E'## Rückgabe\n\n' || answered.return_body,
            E'## Frage\n\n'    || answered.executor_question,
            E'## Nachricht\n\n' || answered.commissioner_message,
            E'## Grund\n\n'    || answered.termination_reason);

        IF EXISTS (SELECT 1 FROM dispatch.exchange a
                    WHERE a.tenant_id = answered.tenant_id AND a.scope_id = answered.scope_id
                      AND a.selector_id = answered.selector_id AND a.number = answered.number
                      AND a.sub = answered.sub AND a.addendum_suffix IS NOT NULL
                      AND a.title = answer_title) THEN
            IF EXISTS (SELECT 1 FROM dispatch.exchange a
                        WHERE a.tenant_id = answered.tenant_id AND a.scope_id = answered.scope_id
                          AND a.selector_id = answered.selector_id AND a.number = answered.number
                          AND a.sub = answered.sub AND a.addendum_suffix IS NOT NULL
                          AND a.title = answer_title
                          AND a.dispatch_body IS DISTINCT FROM answer_text) THEN
                RAISE EXCEPTION 'V16_1 stops: %/%.% already has an answer whose text differs from the one this run would write',
                    answered.selector_name, answered.number, answered.sub || answered.addendum_suffix
                    USING ERRCODE = 'KD003';
            END IF;
            skipped := skipped + 1;
        ELSE
            -- The next free letter at the address, read per row: an earlier row
            -- of this run may have taken one under the same root. Compared by
            -- code, not as text: under a linguistic collation the character
            -- after z sorts before it.
            SELECT max(ascii(a.addendum_suffix)) + 1 INTO code
              FROM dispatch.exchange a
             WHERE a.tenant_id = answered.tenant_id AND a.scope_id = answered.scope_id
               AND a.selector_id = answered.selector_id AND a.number = answered.number
               AND a.sub = answered.sub AND a.addendum_suffix IS NOT NULL;
            letter := chr(code);
            IF code > ascii('z') THEN
                RAISE EXCEPTION 'V16_1 stops: %/%.% has no free addendum letter after z',
                    answered.selector_name, answered.number, answered.sub
                    USING ERRCODE = 'KD003';
            END IF;

            INSERT INTO dispatch.exchange (
                tenant_id, scope_id, selector_id, number, sub, addendum_suffix, status,
                title, dispatch_body, apparatus, dispatch_date, sent_at,
                created_at, created_by, updated_at, updated_by)
            VALUES (
                answered.tenant_id, answered.scope_id, answered.selector_id,
                answered.number, answered.sub, letter, answered.status,
                answer_title, answer_text,
                'concept', answered.dispatch_date, answered.sent_at,
                answered.created_at, answered.created_by, answered.updated_at, answered.updated_by);
            moved := moved + 1;
        END IF;

        UPDATE dispatch.exchange
           SET return_body = NULL, executor_question = NULL,
               commissioner_message = NULL, termination_reason = NULL
         WHERE id = answered.id;
    END LOOP;

    RAISE NOTICE 'V16_1 moved % addendum answer(s) into sibling addenda, skipped % already moved',
        moved, skipped;
END
$do$;

ALTER TABLE dispatch.exchange ENABLE TRIGGER exchange_freeze;
ALTER TABLE dispatch.selector FORCE ROW LEVEL SECURITY;
ALTER TABLE dispatch.exchange FORCE ROW LEVEL SECURITY;
