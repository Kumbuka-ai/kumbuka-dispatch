-- Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
-- SPDX-License-Identifier: AGPL-3.0-only
-- This file is part of Kumbuka and is licensed under the GNU Affero
-- General Public License v3.0 only. See the LICENSE file in the
-- repository root for the full licence text.

-- ===========================================================================
-- cutover-rename-reverse.sql
--
-- The way back of cutover-rename.sql: it drops dispatch_legacy.cutover and
-- renames dispatch_legacy back to dispatch. Afterwards the old store stands
-- with its stock, privileges, policies, functions and triggers as it stood
-- before the rename, and the old image runs against it.
--
-- Not a migration. The operator runs it with psql, after the new schema
-- dispatch is dropped:
--
--     psql -v ON_ERROR_STOP=1 -U <owner> -d <database> -f cutover-rename-reverse.sql
--
-- One transaction. It refuses loudly, and changes nothing, when the schema
-- does not stand as it expects: no dispatch_legacy, a schema dispatch still
-- there, no table cutover or not exactly one row in it, or a cutover already
-- opened. Until the opening the way back loses nothing; after it, what the
-- new store took is not in dispatch_legacy.
-- ===========================================================================

BEGIN;

DO $way_back$
DECLARE
    marks   BIGINT;
    opened  TIMESTAMPTZ;
BEGIN
    IF to_regnamespace('dispatch_legacy') IS NULL THEN
        RAISE EXCEPTION 'cutover way back refused: there is no schema dispatch_legacy'
            USING ERRCODE = 'KC002';
    END IF;

    IF to_regnamespace('dispatch') IS NOT NULL THEN
        RAISE EXCEPTION 'cutover way back refused: a schema dispatch exists; drop the new '
            'store first'
            USING ERRCODE = 'KC002';
    END IF;

    IF to_regclass('dispatch_legacy.cutover') IS NULL THEN
        RAISE EXCEPTION 'cutover way back refused: dispatch_legacy has no table cutover'
            USING ERRCODE = 'KC002';
    END IF;

    SELECT count(*), max(c.opened_at) INTO marks, opened FROM dispatch_legacy.cutover c;

    IF marks <> 1 THEN
        RAISE EXCEPTION 'cutover way back refused: dispatch_legacy.cutover holds % rows, '
            'expected exactly one', marks
            USING ERRCODE = 'KC002';
    END IF;

    IF opened IS NOT NULL THEN
        RAISE EXCEPTION 'cutover way back refused: the new store was opened at %; the way '
            'back is lossless only until the opening', opened
            USING ERRCODE = 'KC002';
    END IF;
END
$way_back$;

DROP TABLE dispatch_legacy.cutover;

ALTER SCHEMA dispatch_legacy RENAME TO dispatch;

COMMIT;
