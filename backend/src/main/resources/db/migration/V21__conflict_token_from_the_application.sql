-- Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
-- SPDX-License-Identifier: AGPL-3.0-only
-- This file is part of Kumbuka and is licensed under the GNU Affero
-- General Public License v3.0 only. See the LICENSE file in the
-- repository root for the full licence text.

-- ===========================================================================
-- V21: the conflict token of a task is the application's.
--
-- `task.updated_at` is the conflict token (C10.1). The application writes it
-- from the service's injected clock as max(now, previous + 1 microsecond),
-- on every write that changes the task and on no other, and compares it
-- with the presented token under the row lock. A time the database writes
-- is the transaction's start, read off a clock no test controls: two writes
-- can carry it in one microsecond, and a write can carry an earlier token
-- than the one it replaces. So nothing in the database sets it any more
-- (C20.1): this migration drops the trigger and the default V17 put on it.
--
-- WHAT IT CORRECTS IN V17
--
-- V17's last comment before the copy says "updated_at is the database's, as
-- on `exchange` (V4)". For `task` that no longer holds after this migration:
-- `updated_at` of `task` is the application's. V17 itself is not edited:
-- its checksum is recorded in every database it ran on, and an edit breaks
-- each of them.
--
-- `exchange` keeps its trigger `exchange_stamp_updated_at` and the function
-- `dispatch.stamp_updated_at()`. `task_text` has no `updated_at` and is not
-- touched.
--
-- ONE STEP, UNDER DEC-0018
--
-- The column stays; what changes is who writes it. An artefact built before
-- this migration inserts a task without `updated_at` and relies on the
-- default dropped here, so it does not run against this schema. The change
-- is made in one step because no deployed artefact has read `task`, and
-- the deployment path refuses, by an excluded range of versions held by a
-- test, every artefact that reads `task` and predates this migration; that
-- range stands in the deployment path before an artefact carrying this
-- migration is deployed. Artefacts older than the first that reads `task`
-- never touch it and stay deployable. Compatibility with them is held by the
-- author, not shown by a gate.
-- ===========================================================================

DROP TRIGGER task_stamp_updated_at ON dispatch.task;

ALTER TABLE dispatch.task ALTER COLUMN updated_at DROP DEFAULT;

COMMENT ON COLUMN dispatch.task.updated_at IS
    'The conflict token: the instant of the last write that changed the task, written by '
    'the application from its clock as max(now, previous + 1 microsecond).';
