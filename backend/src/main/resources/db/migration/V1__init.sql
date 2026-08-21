-- ===========================================================================
-- V1: the dispatch schema.
--
-- One PostgreSQL instance, one database, one NAMED schema and one database
-- role per service. `dispatch` is that schema. Nothing here reaches into
-- another service's schema: no foreign key, no join, no view. A reference to
-- an object owned elsewhere is stored as an address and never resolved.
--
-- WHAT THIS MIGRATION DELIBERATELY DOES NOT CONTAIN
--
-- The object model of the exchange — dispatch and handover as two roles of
-- one identity, the single status field and its transitions, the freeze at
-- send, the bracket derived over `.0`, addenda, number circles, the audit
-- log — is the domain half and is built separately. This is the substrate:
-- the schema, the tenancy axis, and the seam that makes both provable.
--
-- WHY THERE IS A TABLE HERE AT ALL
--
-- Row-level security cannot be asserted against an empty schema, and a
-- guarantee that was never observed holding is not a guarantee. `scope` is
-- the smallest table that is substrate rather than domain: the scope is the
-- unit of tenancy, so this is the tenancy axis itself given a place to live,
-- and it carries no exchange semantics the domain half would have to unpick.
--
-- The scope's identity in the platform directory is STORED, never resolved.
-- The platform publishes a read contract for scope access; consuming it is a
-- runtime read, not a schema-level reference, so `platform_scope_id` here is
-- a plain uuid column with no foreign key. That is the point of the
-- no-cross-schema-reference rule and not an omission.
--
-- ON THE SCHEMA ITSELF
--
-- Flyway is configured with this schema as its default (see
-- application.properties), so it creates the schema before running this
-- migration and places its history table inside it. The statement below is
-- therefore normally a no-op, and it is written anyway: the schema is the
-- first thing this service owns, and a reader should find that fact in the
-- migration rather than only in a property file.
-- ===========================================================================

CREATE SCHEMA IF NOT EXISTS dispatch;

-- Never reachable via PUBLIC. The service role reaches its own objects as
-- their owner (see V2); no other role acquires anything by default, and the
-- operator boundary of this service is exactly that absence.
REVOKE ALL ON SCHEMA dispatch FROM PUBLIC;

-- ---------------------------------------------------------------------------
-- scope — the unit of tenancy, as this service holds it.
--
--   tenant_id           the row-level-security axis. Every tenant-scoped
--                       table in this schema carries it under this exact
--                       name; the completeness probe reads the catalog and
--                       fails on any that does not.
--   platform_scope_id   the scope's identity in the platform directory.
--                       Stored, never resolved, no foreign key.
--   slug                the addressable name: dispatch://<slug>/...
--
-- gen_random_uuid() is a core function since PostgreSQL 13 and needs no
-- extension — which is one superuser-only operation the migrator does not
-- have to carry.
-- ---------------------------------------------------------------------------
CREATE TABLE dispatch.scope (
    id                 UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID         NOT NULL,
    platform_scope_id  UUID         NOT NULL,
    slug               TEXT         NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_scope_slug     UNIQUE (tenant_id, slug),
    CONSTRAINT uq_scope_platform UNIQUE (tenant_id, platform_scope_id)
);

CREATE INDEX idx_scope_tenant ON dispatch.scope (tenant_id);
