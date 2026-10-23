-- Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
-- SPDX-License-Identifier: AGPL-3.0-only
-- This file is part of Kumbuka and is licensed under the GNU Affero
-- General Public License v3.0 only. See the LICENSE file in the
-- repository root for the full licence text.

-- ===========================================================================
-- V22: the stamp on an acceptance, whether the identity that delivered the
-- task accepted it itself (TAR-0004 section 3, REQ-0034).
--
-- An identity may hold the commissioning and the executing capacity at once.
-- Every acceptance from this release on stamps `self_accepted`: true where
-- the identity that delivered accepts, false where another does. NULL means
-- unknown: every task not closed as accepted, and every task accepted before
-- this migration. The stock is NOT stamped false. The service before this
-- one could not tell an acceptance of one's own answer from another's, and a
-- false written now would assert what nobody recorded.
--
-- What the database secures:
--
-- * the stamp stands only on a task closed as accepted
--   (`ck_task_self_accepted_only_accepted`);
-- * once stamped, it never changes, to the other value or back to NULL
--   (`task_self_accepted_never_changed`).
--
-- STRICTLY ADDITIVE (constraint migrations-additive-n1-compatible). One
-- nullable column without a default, one check, one function and one
-- trigger. No row of the stock is read or written: every existing row holds
-- NULL, which the check admits whatever the row's state, so the check
-- validates without an assumption about the stock. The image before this one
-- does not know the column: its inserts leave it NULL and its updates never
-- touch it. The table-wide grants of V17 cover the new column.
-- ===========================================================================

ALTER TABLE dispatch.task ADD COLUMN self_accepted BOOLEAN;

ALTER TABLE dispatch.task ADD CONSTRAINT ck_task_self_accepted_only_accepted
    CHECK (self_accepted IS NULL OR (state = 'closed' AND outcome = 'accepted'));

CREATE FUNCTION dispatch.refuse_changing_self_accepted() RETURNS TRIGGER AS $$
BEGIN
    IF OLD.self_accepted IS NOT NULL
       AND NEW.self_accepted IS DISTINCT FROM OLD.self_accepted THEN
        RAISE EXCEPTION
            'task % carries the stamp self_accepted = %, and the stamp never changes.',
            OLD.id, OLD.self_accepted
            USING ERRCODE = 'raise_exception';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER task_self_accepted_never_changed
    BEFORE UPDATE ON dispatch.task
    FOR EACH ROW EXECUTE FUNCTION dispatch.refuse_changing_self_accepted();

COMMENT ON COLUMN dispatch.task.self_accepted IS
    'The stamp of the acceptance: true where the identity that delivered the task accepted '
    'it, false where another did, NULL where unknown. Never changed once stamped.';
