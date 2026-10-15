-- Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
-- SPDX-License-Identifier: AGPL-3.0-only
-- This file is part of Kumbuka and is licensed under the GNU Affero
-- General Public License v3.0 only. See the LICENSE file in the
-- repository root for the full licence text.

-- ===========================================================================
-- V20: the index of the creation order ends on the address.
--
-- A listing sorted by creation orders by (created_at, number, sub): the
-- creation time, then the address, so that tasks created in the same instant
-- still stand in one total order. V19 indexed (created_at, id), which agrees
-- with that order only up to the creation time. Past it the planner had to
-- sort the tasks of each instant again, and a key it cannot read in order is
-- a key it does not walk.
--
-- This index carries the order the statement asks for, column for column, so
-- a page is the first rows of an index range and nothing is sorted.
--
-- V19's index is dropped: the creation order was its only reader, and no
-- other statement of this service sorts or filters on created_at. An index
-- answers no query on its own, so its absence changes no result for this
-- image or the one before it, which never read it.
--
-- ADDITIVE (DEC-0018): one index replaces another, no column or constraint
-- changes, and the image before this one runs unchanged against it.
-- ===========================================================================

CREATE INDEX idx_task_created_address
    ON dispatch.task (tenant_id, scope_id, selector_id, created_at, number, sub);

DROP INDEX dispatch.idx_task_created;
