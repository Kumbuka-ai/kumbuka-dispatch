# Formats of the model

Decided by the operator on 2026-11-20 after a probe on the group
`definition`.

## The levels and their carriers

| Level | Carrier | Truth or view |
|---|---|---|
| conceptual and logical | LinkML (`../model/*.linkml.yaml`) | truth for definitions, kinds of data, invariants and traces to the corpus |
| physical | the migrated database: the store line under `backend/src/main/resources/db/store/`, read through the PostgreSQL system catalogue | truth |
| physical, for reading | DBML generated from a schema dump (`scripts/data-model/generate-dbml.sh`) | view, lossy, never edited and never used as evidence |

## Why LinkML for the conceptual and logical level

LinkML (linkml.io) is a schema language in YAML with a linter and generators. The probe showed that it
carries, natively and checkably:

- a definition for every class, attribute and enumeration value; the linter reports each one missing
  (criterion F2);
- closed value sets as enumerations, each value with its meaning;
- unique keys over several attributes, the tenant and scope included;
- the kind of data under DEC-0056/C1 as a subset per attribute (`system`, `control`, `free`,
  `content`), which makes criterion L4 a check;
- a shared abstract class for the tenant and scope every row carries.

What LinkML does not express natively is recorded as annotations on the class and checked against the
catalogue instead: row-level security, grants, insert-only, the tenant-carrying foreign keys, partial
unique indexes and multi-column check constraints. These are physical properties; the catalogue holds
them without loss.

No DDL is generated from the model. The migrations stay written by hand; LinkML's own SQL generator
neither knows the identifier type of the store nor its key shape.

## Why the catalogue, not DBML, for the physical level

The probe converted a schema dump of the store line with `sql2dbml` (`@dbml/cli`). It carried all 35
tables and 71 references with their comments, and it lost, measurably:

- `timestamp with time zone`, written as `timestamp`;
- the predicate of partial unique indexes, so that `uq_definition_version_active` (at most one active
  version per selector) read as one version per selector, which is false;
- row-level security, policies and grants;
- and its parser refused the PostgreSQL 15 form `ON DELETE SET NULL (column)` until it was removed.

A view that states a false rule cannot be evidence. The catalogue is the only source the checks of
`measurement.md` and the reconciliation read; the DBML file is generated for reading and names its
losses in its header.

## Reconciliation

`scripts/data-model/reconcile.py` reads the LinkML model and the catalogue of a migrated database and
reports, in both directions, every table without a concept, every concept without a table, every
column without an attribute and every attribute without a column (criterion R1). In the probe its first
run reported exactly one difference, `definition_version.seed`, which the model carries and the store
gains only with V4: a true report.

## If this does not hold

If the catalogue proves too hard to read for a reviewer, the operator decided to revisit DBML as a
generated view with the losses above made good by a generator of its own.
