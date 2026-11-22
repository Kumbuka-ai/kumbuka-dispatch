-- Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
-- SPDX-License-Identifier: AGPL-3.0-only
-- This file is part of Kumbuka and is licensed under the GNU Affero
-- General Public License v3.0 only. See the LICENSE file in the
-- repository root for the full licence text.

-- ===========================================================================
-- V24: the tags a task carries (sprint/210, tag system v1).
--
-- The vocabulary is the platform's: a tag is declared in a scope and read
-- through `platform.tag_access`, never written here. What this service holds
-- is the relation, one row per task and tag. The relation is no field of the
-- task: attaching or detaching a tag leaves the task row, its conflict token
-- and its change stamps as they stand, in every state of the task.
--
-- `tag_id` carries no foreign key: the tag lives across the schema boundary,
-- and the platform withdraws a tag rather than deleting it. The task is
-- referenced by its surrogate together with the tenant, in the form of the
-- other references of this schema (ADR-0042), and its relations go along when
-- a draft is deleted.
--
-- `attached_by` says who attached the tag, as the subject of the caller. It is
-- a record of who did what, never a count (constraint
-- audit-no-activity-monitoring): nothing here aggregates over it.
--
-- STRICTLY ADDITIVE (constraint migrations-additive-n1-compatible). One new
-- table, its index, its policy and its grant. No existing column, table,
-- trigger, policy or grant is touched. The image before this one does not
-- know the table and runs unchanged against this schema; the delete of a
-- draft it issues takes the draft's relations along through the cascade.
-- ===========================================================================

CREATE TABLE dispatch.task_tag (
    tenant_id    UUID         NOT NULL,

    task_id      BIGINT       NOT NULL,
    tag_id       UUID         NOT NULL,

    attached_by  TEXT,
    attached_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    PRIMARY KEY (task_id, tag_id),

    CONSTRAINT fk_task_tag_task_tenant
        FOREIGN KEY (tenant_id, task_id) REFERENCES dispatch.task (tenant_id, id)
        ON DELETE CASCADE ON UPDATE RESTRICT
);

-- A listing narrowed by a tag reads the relation by the tag.
CREATE INDEX idx_task_tag_tag ON dispatch.task_tag (tag_id);

-- Row-level security, in the form V3 established.
ALTER TABLE dispatch.task_tag ENABLE ROW LEVEL SECURITY;
ALTER TABLE dispatch.task_tag FORCE  ROW LEVEL SECURITY;
CREATE POLICY task_tag_tenant_isolation ON dispatch.task_tag
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- The entitlement, written out, as V17 grants the tables of the task store.
-- DELETE is what detaching a tag is. No TRUNCATE, no REFERENCES, no TRIGGER.
GRANT SELECT, INSERT, UPDATE, DELETE ON dispatch.task_tag TO kumbuka_dispatch;

COMMENT ON TABLE dispatch.task_tag IS
    'The tags a task carries: one row per task and tag of the platform''s vocabulary. '
    'A relation, never a field of the task.';
