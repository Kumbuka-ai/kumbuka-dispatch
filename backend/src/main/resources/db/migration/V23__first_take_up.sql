-- Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
-- SPDX-License-Identifier: AGPL-3.0-only
-- This file is part of Kumbuka and is licensed under the GNU Affero
-- General Public License v3.0 only. See the LICENSE file in the
-- repository root for the full licence text.

-- ===========================================================================
-- V23: the instant a task was first taken up (TAR-0004 section 3, DEC-0014,
-- REQ-0035).
--
-- The commissioner may recall a sent task to draft until it is taken up for
-- the first time, and never afterwards. Nothing the store held so far tells
-- that apart: release and defer drop every holder column, and lapse_count
-- counts only leases that ran out. `first_taken_at` is the instant of the
-- first take-up. The kernel sets it at that take-up and at no other; release,
-- defer, a lapsed lease, rework and every later claim leave it as it stands.
--
-- What the database secures: once set, the instant never changes, to another
-- value or back to NULL (`task_first_taken_at_never_changed`).
--
-- NULL means the task was not taken up under a kernel that records it. The
-- stock is NOT written: the kernel derives "taken up" for a task without the
-- instant from what the stock does record, and a value written here would
-- assert an instant nobody recorded.
--
-- STRICTLY ADDITIVE (constraint migrations-additive-n1-compatible). One
-- nullable column without a default, one function and one trigger. The image
-- before this one does not know the column: its inserts leave it NULL and its
-- updates never touch it, so it never meets the trigger's refusal. A task
-- that image takes up meanwhile stays NULL. The table-wide grants of V17 cover
-- the new column.
-- ===========================================================================

ALTER TABLE dispatch.task ADD COLUMN first_taken_at TIMESTAMPTZ;

CREATE FUNCTION dispatch.refuse_changing_first_taken_at() RETURNS TRIGGER AS $$
BEGIN
    IF OLD.first_taken_at IS NOT NULL
       AND NEW.first_taken_at IS DISTINCT FROM OLD.first_taken_at THEN
        RAISE EXCEPTION
            'task % was first taken up at %, and that instant never changes.',
            OLD.id, OLD.first_taken_at
            USING ERRCODE = 'raise_exception';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER task_first_taken_at_never_changed
    BEFORE UPDATE ON dispatch.task
    FOR EACH ROW EXECUTE FUNCTION dispatch.refuse_changing_first_taken_at();

COMMENT ON COLUMN dispatch.task.first_taken_at IS
    'The instant the task was first taken up; NULL where it was not taken up under a kernel '
    'that records it. Never changed once set.';
