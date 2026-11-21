-- Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
-- SPDX-License-Identifier: AGPL-3.0-only
-- This file is part of Kumbuka and is licensed under the GNU Affero
-- General Public License v3.0 only. See the LICENSE file in the
-- repository root for the full licence text.

-- ===========================================================================
-- cutover-rename.sql
--
-- The rename of the cut-over: the schema dispatch of the old store (line
-- db/migration, V1 to V25) becomes dispatch_legacy, and gets the table
-- dispatch_legacy.cutover with exactly one row whose opened_at is empty. That
-- row is the mark of the cut-over: the copy refuses once opened_at is set.
--
-- Not a migration. No build and no service runs it; the operator runs it with
-- psql, after the service is stopped and the backup is taken, and before the
-- new image starts and builds the line db/store in an empty schema dispatch.
-- Its way back is cutover-rename-reverse.sql.
--
-- Run it as the role that owns the schema dispatch (the migrating role):
--
--     psql -v ON_ERROR_STOP=1 -U <owner> -d <database> -f cutover-rename.sql
--
-- One transaction. It refuses loudly, and changes nothing, when the schema
-- does not stand as it expects: dispatch_legacy already there, dispatch not
-- the old store, a failed migration in its history, or a last version other
-- than 25.
--
-- The rename carries everything of the schema along: rows, privileges,
-- policies, functions and triggers. Two trigger functions of V17 name
-- dispatch.task in their bodies; after the rename they read the new table
-- task. Nothing writes to a table of dispatch_legacy after the rename except
-- cutover.
-- ===========================================================================

BEGIN;

DO $rename$
DECLARE
    latest TEXT;
BEGIN
    IF to_regnamespace('dispatch_legacy') IS NOT NULL THEN
        RAISE EXCEPTION 'cutover rename refused: the schema dispatch_legacy exists already'
            USING ERRCODE = 'KC002';
    END IF;

    IF to_regclass('dispatch.flyway_schema_history') IS NULL
       OR to_regclass('dispatch.task') IS NULL
       OR to_regclass('dispatch.task_text') IS NULL THEN
        RAISE EXCEPTION 'cutover rename refused: the schema dispatch is not the old store '
            '(line db/migration)'
            USING ERRCODE = 'KC002';
    END IF;

    IF to_regclass('dispatch.act') IS NOT NULL THEN
        RAISE EXCEPTION 'cutover rename refused: the schema dispatch holds the store line '
            'db/store, not the old store'
            USING ERRCODE = 'KC002';
    END IF;

    IF EXISTS (SELECT 1 FROM dispatch.flyway_schema_history h WHERE NOT h.success) THEN
        RAISE EXCEPTION 'cutover rename refused: the history of the old store records a '
            'failed migration'
            USING ERRCODE = 'KC002';
    END IF;

    SELECT h.version INTO latest
      FROM dispatch.flyway_schema_history h
     WHERE h.version IS NOT NULL
     ORDER BY h.installed_rank DESC
     LIMIT 1;

    IF latest IS DISTINCT FROM '25' THEN
        RAISE EXCEPTION 'cutover rename refused: the old store stands at version %, '
            'expected 25', coalesce(latest, 'none')
            USING ERRCODE = 'KC002';
    END IF;
END
$rename$;

ALTER SCHEMA dispatch RENAME TO dispatch_legacy;

CREATE TABLE dispatch_legacy.cutover (
    opened_at  TIMESTAMPTZ
);

COMMENT ON TABLE dispatch_legacy.cutover IS
    'The mark of the cut-over, one row: opened_at is set when the new store opens, and '
    'the copy of the stock refuses from then on.';

INSERT INTO dispatch_legacy.cutover (opened_at) VALUES (NULL);

COMMIT;
