-- Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
-- SPDX-License-Identifier: AGPL-3.0-only
-- This file is part of Kumbuka and is licensed under the GNU Affero
-- General Public License v3.0 only. See the LICENSE file in the
-- repository root for the full licence text.

-- ===========================================================================
-- V22: the mark on a task its deliverer accepted itself (TAR-0004 section 3,
-- REQ-0034).
--
-- An identity may hold the commissioning and the executing capacity at once.
-- When it accepts an answer it delivered itself, the closed task carries
-- `self_accepted`, which nothing removes. The application sets it in the
-- transaction of the acceptance and writes false on every insert.
--
-- What the database secures:
--
-- * the mark stands only on a task closed as accepted
--   (`ck_task_self_accepted_only_accepted`);
-- * once set, it is never cleared (`task_self_accepted_never_cleared`).
--
-- STRICTLY ADDITIVE (constraint migrations-additive-n1-compatible). One
-- column with a default, one check, one function and one trigger. No row of
-- the stock is read or corrected: the default fills every existing row with
-- false, which the check admits whatever the row's state, so the check
-- validates without an assumption about the stock. The image before this one
-- does not know the column; its inserts take the default and its updates
-- never touch it, so it runs against this schema unchanged. The table-wide
-- grants of V17 cover the new column.
-- ===========================================================================

ALTER TABLE dispatch.task ADD COLUMN self_accepted BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE dispatch.task ADD CONSTRAINT ck_task_self_accepted_only_accepted
    CHECK (NOT self_accepted OR (state = 'closed' AND outcome = 'accepted'));

CREATE FUNCTION dispatch.refuse_clearing_self_accepted() RETURNS TRIGGER AS $$
BEGIN
    IF OLD.self_accepted AND NOT NEW.self_accepted THEN
        RAISE EXCEPTION
            'task % carries the mark self_accepted, and the mark is never removed.',
            OLD.id
            USING ERRCODE = 'raise_exception';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER task_self_accepted_never_cleared
    BEFORE UPDATE ON dispatch.task
    FOR EACH ROW EXECUTE FUNCTION dispatch.refuse_clearing_self_accepted();

COMMENT ON COLUMN dispatch.task.self_accepted IS
    'True where the identity that delivered the task accepted it itself; set by the '
    'acceptance and never cleared.';
