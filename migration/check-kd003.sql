-- Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
-- SPDX-License-Identifier: AGPL-3.0-only
-- This file is part of Kumbuka and is licensed under the GNU Affero
-- General Public License v3.0 only. See the LICENSE file in the
-- repository root for the full licence text.

-- ===========================================================================
-- check-kd003.sql
--
-- Lists the addendum rows of dispatch.exchange that carry a text besides
-- their own: the rows V17 stops on with KD003, and the rows V16_1 moves into
-- addenda of their own before V17 runs. The condition is V17's, word for word.
--
-- Read only. The transaction is opened READ ONLY and ends in ROLLBACK, so
-- nothing this file says can write.
--
-- Run it as the database superuser, inside the postgres container:
--
--     docker exec -i <postgres container> psql -U <superuser> -d <database> \
--         < check-kd003.sql
--
-- The superuser reads past row-level security and sees every tenant. Run as
-- the owning role, it would see the bound tenant's rows only (FORCE ROW LEVEL
-- SECURITY binds the owner) and could answer zero for a stock that is not.
--
-- Expected on the stock imported by 001 and 002: 2 rows, sprint/10.0a and
-- sprint/155.2a, each with a return. After V16_1: 0.
-- ===========================================================================

BEGIN READ ONLY;

SELECT e.tenant_id,
       s.name || '/' || e.number || '.' || e.sub || e.addendum_suffix AS address,
       e.status,
       e.return_body          IS NOT NULL AS has_return,
       e.executor_question    IS NOT NULL AS has_question,
       e.commissioner_message IS NOT NULL AS has_message,
       e.termination_reason   IS NOT NULL AS has_reason
  FROM dispatch.exchange e
  JOIN dispatch.selector s ON s.tenant_id = e.tenant_id AND s.id = e.selector_id
 WHERE e.addendum_suffix IS NOT NULL
   AND (e.return_body IS NOT NULL OR e.executor_question IS NOT NULL
        OR e.commissioner_message IS NOT NULL OR e.termination_reason IS NOT NULL)
 ORDER BY e.tenant_id, s.name, e.number, e.sub, e.addendum_suffix;

SELECT count(*) AS rows_v17_stops_on
  FROM dispatch.exchange
 WHERE addendum_suffix IS NOT NULL
   AND (return_body IS NOT NULL OR executor_question IS NOT NULL
        OR commissioner_message IS NOT NULL OR termination_reason IS NOT NULL);

ROLLBACK;
