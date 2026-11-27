-- Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
-- SPDX-License-Identifier: AGPL-3.0-only
-- This file is part of Kumbuka and is licensed under the GNU Affero
-- General Public License v3.0 only. See the LICENSE file in the
-- repository root for the full licence text.

-- ===========================================================================
-- V6 of the store line: the conflict token of a draft, and what an
-- idempotency key compares and how it expires.
--
-- The first migration of the line that takes changes additively only: two
-- columns, each nullable or defaulted, and two grants. No drop, no rename,
-- no narrowing constraint. N-1 compatible as held by the author: the image
-- before this one names neither column in a statement, so it writes the
-- rows it wrote before, and the store fills `changed_at` by its default and
-- leaves `argument_digest` empty.
--
-- 1. draft.changed_at is the conflict token of a task in draft (REA-0010
--    D29, as revised on 2026-11-24): a draft has no trace, so its token is a
--    column of its row. The service writes it at every create and update as
--    the later of its clock and the previous value plus one microsecond,
--    under the lock of the draft's row, and reads back only what it wrote;
--    the default serves the rows the image before this one inserts.
--
-- 2. idempotency_key.argument_digest is a digest of the canonical arguments
--    of the call a key was first spent on (REA-0010 D30): a repeat under a
--    live key with another digest is refused as IDEMPOTENCY_KEY_REUSED
--    (C10.4). Nullable, because the line holds no row yet; the service
--    writes it at every insert.
--
-- 3. DELETE on idempotency_key: a key older than 24 hours counts as absent,
--    and the expired keys of a caller are deleted when that caller next
--    writes a key, in the same transaction before the insert (D30, C10.4).
-- ===========================================================================

ALTER TABLE dispatch.draft
    ADD COLUMN changed_at TIMESTAMPTZ NOT NULL DEFAULT now();

GRANT UPDATE (changed_at) ON dispatch.draft TO kumbuka_dispatch;

ALTER TABLE dispatch.idempotency_key
    ADD COLUMN argument_digest TEXT;

GRANT DELETE ON dispatch.idempotency_key TO kumbuka_dispatch;
