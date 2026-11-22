-- Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
-- SPDX-License-Identifier: AGPL-3.0-only
-- This file is part of Kumbuka and is licensed under the GNU Affero
-- General Public License v3.0 only. See the LICENSE file in the
-- repository root for the full licence text.

-- ===========================================================================
-- V25: an idempotency key outlives the draft it created (C10.4).
--
-- A repeat under a key whose first result is gone is refused with
-- IDEMPOTENCY_KEY_REUSED and never carried out a second time. Until now the
-- delete of a draft deleted its key with it, because `task_id` could not be
-- left without a task: a repeat after the delete found no key and created the
-- draft again. The key now stays for its 24 hours and lets go of the task:
-- the delete sets `task_id` to NULL, and NULL means the draft the first call
-- created was deleted.
--
-- The composite foreign key of V16/V17 checks nothing for a row whose
-- `task_id` is NULL (MATCH SIMPLE), so a kept key refers to no task and needs
-- no other change. The grants of V17 cover the UPDATE and the DELETE the
-- service writes: the delete of a draft lets go of its keys, and a caller's
-- keys older than 24 hours are removed when that caller next spends one.
--
-- ADDITIVE, N-1 COMPATIBLE AS HELD BY THE AUTHOR (constraint
-- migrations-additive-n1-compatible, DEC-0018). One NOT NULL dropped, which
-- narrows nothing: every row the image before this one writes still carries
-- a task. That image still deletes the keys of a draft it deletes. It meets a
-- NULL only on a key this image kept after a delete; there it refuses the
-- repeat as an unexpected failure and writes nothing, which is no duplicate.
-- ===========================================================================

ALTER TABLE dispatch.task_idempotency_key ALTER COLUMN task_id DROP NOT NULL;

COMMENT ON COLUMN dispatch.task_idempotency_key.task_id IS
    'The task the first call answered with; NULL once that task, a draft, was deleted. '
    'The key stays for its 24 hours, and a repeat under it is refused.';
