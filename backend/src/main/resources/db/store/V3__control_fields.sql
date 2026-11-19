-- Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
-- SPDX-License-Identifier: AGPL-3.0-only
-- This file is part of Kumbuka and is licensed under the GNU Affero
-- General Public License v3.0 only. See the LICENSE file in the
-- repository root for the full licence text.

-- ===========================================================================
-- V3 of the store line: control fields.
--
-- Five tables after V2, on the same rules: an identity key, tenant_id and
-- scope_id, UNIQUE (tenant_id, scope_id, id), every foreign key carrying the
-- tenant and the scope and none deleting or updating along, ENABLE and FORCE
-- ROW LEVEL SECURITY with one policy <table>_tenant_isolation, the privileges
-- of `kumbuka_dispatch` written out per table and never TRUNCATE, REFERENCES
-- or TRIGGER, no trigger and no JSON. The checks of the runtime role and of
-- the migrator stand in V1 and are not repeated. Like V1 it writes no row.
--
-- Every table here is insert-only: SELECT and INSERT, no UPDATE, no DELETE.
--
-- THE SCHEMA OF A DEFINITION VERSION declares its control fields, each with a
-- name and a type of the platform's catalogue; a field of the type choice
-- takes its values from a named enumeration of the same version, which
-- several fields may share. The start contract of a version and the result
-- contract of an activity name fields of that schema.
--
-- A FIELD VALUE is one row per act and field, with one typed column per type
-- and exactly the one value_type names set. It is written with the act that
-- anchors it and never changed; a later act writes a new value. The store
-- itself refuses a value that does not match the type or the enumeration of
-- its field: control_field carries its type and its enumeration in unique
-- tuples, and field_value carries both into its keys. Under MATCH SIMPLE a key
-- with a NULL column is not checked, so the two keys over enumeration_id bind
-- a choice and leave every other type alone.
--
-- What the store does not check: that the field of a value belongs to the
-- version the instance of the act is bound to; that a value hangs only on an
-- act of the kind start or deliver; that a field name does not shadow the
-- name of a system field. Without triggers and without the version on the act
-- the store cannot. These three checks are assigned to the service, which has
-- to make them when it is rebuilt against this line (REA-0010 S5).
-- ===========================================================================

-- ===========================================================================
-- THE SCHEMA OF A DEFINITION VERSION
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- enumeration: a named list of values a version declares for its choice
-- fields. UNIQUE (…, definition_version_id, id) is the target of the key by
-- which a control field takes only an enumeration of its own version.
-- ---------------------------------------------------------------------------
CREATE TABLE dispatch.enumeration (
    id                     BIGINT  GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id              UUID    NOT NULL,
    scope_id               UUID    NOT NULL,

    definition_version_id  BIGINT  NOT NULL,
    name                   TEXT    NOT NULL,

    CONSTRAINT uq_enumeration_tenant_scope_id UNIQUE (tenant_id, scope_id, id),
    CONSTRAINT uq_enumeration_version_name
        UNIQUE (tenant_id, scope_id, definition_version_id, name),
    CONSTRAINT uq_enumeration_version_id
        UNIQUE (tenant_id, scope_id, definition_version_id, id),
    CONSTRAINT ck_enumeration_name CHECK (name ~ '^[a-z][a-z0-9_]{0,62}$'),
    CONSTRAINT fk_enumeration_definition_version
        FOREIGN KEY (tenant_id, scope_id, definition_version_id)
        REFERENCES dispatch.definition_version (tenant_id, scope_id, id)
);

ALTER TABLE dispatch.enumeration ENABLE ROW LEVEL SECURITY;
ALTER TABLE dispatch.enumeration FORCE  ROW LEVEL SECURITY;
CREATE POLICY enumeration_tenant_isolation ON dispatch.enumeration
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON dispatch.enumeration TO kumbuka_dispatch;

COMMENT ON TABLE dispatch.enumeration IS
    'A named enumeration a definition version declares, from which its choice fields take their values.';

-- ---------------------------------------------------------------------------
-- enumeration_value: one value of an enumeration, by its text and place.
-- UNIQUE (…, enumeration_id, id) is the target of the key by which a choice
-- names only a value of its field's enumeration.
-- ---------------------------------------------------------------------------
CREATE TABLE dispatch.enumeration_value (
    id              BIGINT   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id       UUID     NOT NULL,
    scope_id        UUID     NOT NULL,

    enumeration_id  BIGINT   NOT NULL,
    value           TEXT     NOT NULL,
    ordinal         INTEGER  NOT NULL,

    CONSTRAINT uq_enumeration_value_tenant_scope_id UNIQUE (tenant_id, scope_id, id),
    CONSTRAINT uq_enumeration_value_value UNIQUE (tenant_id, scope_id, enumeration_id, value),
    CONSTRAINT uq_enumeration_value_ordinal
        UNIQUE (tenant_id, scope_id, enumeration_id, ordinal),
    CONSTRAINT uq_enumeration_value_enumeration_id
        UNIQUE (tenant_id, scope_id, enumeration_id, id),
    CONSTRAINT ck_enumeration_value_value CHECK (char_length(value) BETWEEN 1 AND 200),
    CONSTRAINT ck_enumeration_value_ordinal CHECK (ordinal >= 1),
    CONSTRAINT fk_enumeration_value_enumeration
        FOREIGN KEY (tenant_id, scope_id, enumeration_id)
        REFERENCES dispatch.enumeration (tenant_id, scope_id, id)
);

ALTER TABLE dispatch.enumeration_value ENABLE ROW LEVEL SECURITY;
ALTER TABLE dispatch.enumeration_value FORCE  ROW LEVEL SECURITY;
CREATE POLICY enumeration_value_tenant_isolation ON dispatch.enumeration_value
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON dispatch.enumeration_value TO kumbuka_dispatch;

COMMENT ON TABLE dispatch.enumeration_value IS
    'A value of an enumeration, by its text and its place in the list.';

-- ---------------------------------------------------------------------------
-- control_field: a field the schema of a version declares, with its name and
-- type. Its type and its enumeration stand in unique tuples with its id, so
-- that a field value can carry both into its keys.
-- ---------------------------------------------------------------------------
CREATE TABLE dispatch.control_field (
    id                     BIGINT  GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id              UUID    NOT NULL,
    scope_id               UUID    NOT NULL,

    definition_version_id  BIGINT  NOT NULL,
    name                   TEXT    NOT NULL,
    type                   TEXT    NOT NULL,
    enumeration_id         BIGINT,

    CONSTRAINT uq_control_field_tenant_scope_id UNIQUE (tenant_id, scope_id, id),
    CONSTRAINT uq_control_field_version_name
        UNIQUE (tenant_id, scope_id, definition_version_id, name),
    CONSTRAINT uq_control_field_version_id
        UNIQUE (tenant_id, scope_id, definition_version_id, id),
    CONSTRAINT uq_control_field_id_type UNIQUE (tenant_id, scope_id, id, type),
    CONSTRAINT uq_control_field_id_enumeration
        UNIQUE (tenant_id, scope_id, id, enumeration_id),
    -- The name is later an identifier in expressions.
    CONSTRAINT ck_control_field_name CHECK (name ~ '^[a-z][a-z0-9_]{0,62}$'),
    CONSTRAINT ck_control_field_type
        CHECK (type IN ('boolean', 'number', 'date', 'text', 'choice')),
    CONSTRAINT ck_control_field_enumeration
        CHECK ((type = 'choice') = (enumeration_id IS NOT NULL)),
    CONSTRAINT fk_control_field_definition_version
        FOREIGN KEY (tenant_id, scope_id, definition_version_id)
        REFERENCES dispatch.definition_version (tenant_id, scope_id, id),
    -- The enumeration belongs to the same version.
    CONSTRAINT fk_control_field_enumeration
        FOREIGN KEY (tenant_id, scope_id, definition_version_id, enumeration_id)
        REFERENCES dispatch.enumeration (tenant_id, scope_id, definition_version_id, id)
);

ALTER TABLE dispatch.control_field ENABLE ROW LEVEL SECURITY;
ALTER TABLE dispatch.control_field FORCE  ROW LEVEL SECURITY;
CREATE POLICY control_field_tenant_isolation ON dispatch.control_field
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON dispatch.control_field TO kumbuka_dispatch;

COMMENT ON TABLE dispatch.control_field IS
    'A control field the schema of a definition version declares, with its name, type and enumeration.';

-- ---------------------------------------------------------------------------
-- contract_field: a field a contract names. With activity_id, a field of the
-- result contract of that activity; without, a field of the start contract
-- of the version. The activity and the field belong to the same version.
-- ---------------------------------------------------------------------------
CREATE TABLE dispatch.contract_field (
    id                     BIGINT   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id              UUID     NOT NULL,
    scope_id               UUID     NOT NULL,

    definition_version_id  BIGINT   NOT NULL,
    activity_id            BIGINT,
    control_field_id       BIGINT   NOT NULL,
    required               BOOLEAN  NOT NULL,

    CONSTRAINT uq_contract_field_tenant_scope_id UNIQUE (tenant_id, scope_id, id),
    CONSTRAINT fk_contract_field_definition_version
        FOREIGN KEY (tenant_id, scope_id, definition_version_id)
        REFERENCES dispatch.definition_version (tenant_id, scope_id, id),
    CONSTRAINT fk_contract_field_activity
        FOREIGN KEY (tenant_id, scope_id, definition_version_id, activity_id)
        REFERENCES dispatch.activity (tenant_id, scope_id, definition_version_id, id),
    CONSTRAINT fk_contract_field_control_field
        FOREIGN KEY (tenant_id, scope_id, definition_version_id, control_field_id)
        REFERENCES dispatch.control_field (tenant_id, scope_id, definition_version_id, id)
);

-- A field at most once in the result contract of an activity, and at most
-- once in the start contract of a version.
CREATE UNIQUE INDEX uq_contract_field_activity_control_field
    ON dispatch.contract_field (tenant_id, scope_id, activity_id, control_field_id)
    WHERE activity_id IS NOT NULL;
CREATE UNIQUE INDEX uq_contract_field_version_control_field
    ON dispatch.contract_field (tenant_id, scope_id, definition_version_id, control_field_id)
    WHERE activity_id IS NULL;

ALTER TABLE dispatch.contract_field ENABLE ROW LEVEL SECURITY;
ALTER TABLE dispatch.contract_field FORCE  ROW LEVEL SECURITY;
CREATE POLICY contract_field_tenant_isolation ON dispatch.contract_field
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON dispatch.contract_field TO kumbuka_dispatch;

COMMENT ON TABLE dispatch.contract_field IS
    'A control field the result contract of an activity or the start contract of a version names.';

-- ===========================================================================
-- THE FIELD VALUE
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- field_value: the value an act writes for one control field. The key over
-- (control_field_id, value_type) makes the type of the value the type of its
-- field; the key over (control_field_id, enumeration_id) makes the
-- enumeration of a choice the one of its field; the key over
-- (enumeration_id, choice_value_id) makes the choice a value of that
-- enumeration. Both keys over enumeration_id are unchecked for every other
-- type, where it is NULL.
-- ---------------------------------------------------------------------------
CREATE TABLE dispatch.field_value (
    id                BIGINT   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id         UUID     NOT NULL,
    scope_id          UUID     NOT NULL,

    act_id            BIGINT   NOT NULL,
    control_field_id  BIGINT   NOT NULL,
    value_type        TEXT     NOT NULL,
    enumeration_id    BIGINT,
    boolean_value     BOOLEAN,
    number_value      NUMERIC,
    date_value        DATE,
    text_value        TEXT,
    choice_value_id   BIGINT,

    CONSTRAINT uq_field_value_tenant_scope_id UNIQUE (tenant_id, scope_id, id),
    -- An act writes a field at most once.
    CONSTRAINT uq_field_value_act_control_field
        UNIQUE (tenant_id, scope_id, act_id, control_field_id),
    CONSTRAINT ck_field_value_value_type
        CHECK (value_type IN ('boolean', 'number', 'date', 'text', 'choice')),
    CONSTRAINT ck_field_value_one_value
        CHECK (num_nonnulls(boolean_value, number_value, date_value, text_value,
                            choice_value_id) = 1),
    CONSTRAINT ck_field_value_typed_value CHECK (CASE value_type
        WHEN 'boolean' THEN boolean_value IS NOT NULL
        WHEN 'number'  THEN number_value IS NOT NULL
        WHEN 'date'    THEN date_value IS NOT NULL
        WHEN 'text'    THEN text_value IS NOT NULL
        WHEN 'choice'  THEN choice_value_id IS NOT NULL
    END),
    CONSTRAINT ck_field_value_text_value CHECK (char_length(text_value) <= 4096),
    CONSTRAINT ck_field_value_enumeration
        CHECK ((value_type = 'choice') = (enumeration_id IS NOT NULL)),
    CONSTRAINT fk_field_value_act
        FOREIGN KEY (tenant_id, scope_id, act_id)
        REFERENCES dispatch.act (tenant_id, scope_id, id),
    CONSTRAINT fk_field_value_control_field_type
        FOREIGN KEY (tenant_id, scope_id, control_field_id, value_type)
        REFERENCES dispatch.control_field (tenant_id, scope_id, id, type),
    CONSTRAINT fk_field_value_control_field_enumeration
        FOREIGN KEY (tenant_id, scope_id, control_field_id, enumeration_id)
        REFERENCES dispatch.control_field (tenant_id, scope_id, id, enumeration_id),
    CONSTRAINT fk_field_value_enumeration_value
        FOREIGN KEY (tenant_id, scope_id, enumeration_id, choice_value_id)
        REFERENCES dispatch.enumeration_value (tenant_id, scope_id, enumeration_id, id)
);

CREATE INDEX idx_field_value_control_field
    ON dispatch.field_value (tenant_id, scope_id, control_field_id);

ALTER TABLE dispatch.field_value ENABLE ROW LEVEL SECURITY;
ALTER TABLE dispatch.field_value FORCE  ROW LEVEL SECURITY;
CREATE POLICY field_value_tenant_isolation ON dispatch.field_value
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON dispatch.field_value TO kumbuka_dispatch;

COMMENT ON TABLE dispatch.field_value IS
    'The value an act writes for one control field, in the one column its type names, never changed.';
