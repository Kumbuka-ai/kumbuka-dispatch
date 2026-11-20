# How the data model is measured

The criteria of the physical level (P1 to P6) and the reconciliation (R1) are measured, not judged. This
file says how, so that a measurement can be repeated by anyone and compared with an earlier one.

## Database under measurement

- PostgreSQL in the major version the service runs on in production (16 as of 2026-11).
- The store line migrated from an empty database: `backend/src/main/resources/db/store/` in version
  order, under the migration role, with the placeholders the service sets.
- The service role and the row-level security of the store line in force; queries run as the service
  role with the tenant setting of one tenant, never as a superuser, because a superuser bypasses
  row-level security and would measure a path the service never takes.

## Dataset

A generated stock, never a copy of production:

- two tenants, three scopes each;
- per scope ten times the current stock of the steering corpus, rounded up: instances, tasks per
  instance, acts per task, addenda, references and tags in the proportions the current stock shows;
- the proportions are taken from counts of the production store, which return numbers only and no text.

The generator is `scripts/data-model/seed-volume.sql`. It is deterministic for a given seed, so a
repeated measurement runs on the same data.

## Hot paths

| Path | What is measured |
|---|---|
| `query` | the listing of one scope by state and by reference of a given kind, first page and a deep page |
| next acts | the acts the gate would admit on one task: the projection of its state from the trace |
| `read_trace` | one instance's trace, first page and a page behind its cursor |
| write one act | one `claim`, one `deliver` with a return and field values, one `start` with references |
| gate | the check of one act against the rules of its version (from the wave that builds the rules) |

Each path is a file under `scripts/data-model/hot-paths/`, run with `EXPLAIN (ANALYZE, BUFFERS,
FORMAT JSON)` five times after one warm-up run; the median is recorded.

## Budget

TAR-0004 (quality section, "The gate is deterministic") leaves the latency budget to the realization
that builds the rules. Until that realization sets it, a measurement records times and plans and states
no verdict on them; it does name every sequential scan over a table of the stock and every path whose
cost grows with the size of the scope rather than of the instance.

## Write cost

Per kind of act: the number of rows written and the number of index entries maintained, counted from
the plans of the write paths.

## Catalogue checks

- **P1** -- queries over the system catalogue (`pg_class`, `pg_attribute`, `pg_constraint`,
  `pg_policy`): every table of the schema has the columns `tenant_id` and `scope_id`; every table has a
  unique constraint whose columns are exactly (`tenant_id`, `scope_id`, `id`); every foreign key's
  columns begin with `tenant_id`, `scope_id` and reference a unique key that begins with them; row-level
  security is enabled and forced; and at least one policy exists on the table.
- **P2** -- a query over `information_schema.role_table_grants`: no role other than the service role
  has a grant on a table that holds content.
- **P3** -- per invariant named in `../model/`, the constraint that keeps it, looked up in
  `pg_constraint`.

## Reconciliation (R1)

`scripts/data-model/reconcile.py` reads the model of the conceptual and logical level from `../model/`
and the physical level generated from the migrated database, and reports every table without a concept
and every concept without a table. It exits non-zero on any such report.

## Recording

An assessment writes its results to `../assessments/<date>-assessment.md`, naming the commit of the
store line, of this file and of the catalogue it applied, the PostgreSQL version and the seed of the
dataset.

## State

2026-11-20: `catalogue-checks.sql` (P1, P2, L5), `reconcile.py` (R1), `seed-volume.sql`,
`run-hot-paths.sh` with the files under `hot-paths/`, and `generate-dbml.sh` exist. The first
assessment is `../assessments/2026-11-20-assessment.md`. The model carries V4 as decided on 2026-11-20
(`../assessments/2026-11-20-decisions.md`); `reconcile.py` reports 18 differences against the store
of V1 to V3, each a change V4 makes. The dataset
is sized by an estimate of the steering corpus; a count from the production store is still to be
taken. The write paths are measured with `psql` timing and `pgbench`, not with `EXPLAIN`.
