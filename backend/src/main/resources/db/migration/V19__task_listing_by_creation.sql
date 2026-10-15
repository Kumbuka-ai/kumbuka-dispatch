-- Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
-- SPDX-License-Identifier: AGPL-3.0-only
-- This file is part of Kumbuka and is licensed under the GNU Affero
-- General Public License v3.0 only. See the LICENSE file in the
-- repository root for the full licence text.

-- ===========================================================================
-- V19: an index for the listing of tasks sorted by creation.
--
-- A listing pages by keyset in the database: the statement names the sort,
-- the position after the cursor and the bound of the page, and reads no row
-- beyond it. Sorted by address it walks `uq_task_address` (V17), whose key
-- (tenant_id, scope_id, selector_id, number, sub) is that order. Sorted by
-- creation it had no index to walk and would sort every task of a selector
-- for each page. This index is the second order.
--
-- ADDITIVE (DEC-0018): one index, nothing else. The image before this one
-- does not read it and is not slowed by more than the index's upkeep.
-- ===========================================================================

CREATE INDEX idx_task_created ON dispatch.task (tenant_id, scope_id, selector_id, created_at, id);
