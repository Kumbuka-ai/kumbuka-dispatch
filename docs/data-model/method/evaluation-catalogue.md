# Evaluation catalogue for the data model

The criteria the data model of the dispatch service is assessed against. An assessment applies every
criterion to every group of tables (see `../groups/`) and records each grade with its evidence in a
dated file under `../assessments/`.

## Sources

The structure follows the quality framework of Moody and Shanks: every criterion is a quality, measured
by a stated method, with a weight that says how much it counts here. The content draws on two
established catalogues and adds the guarantees this platform makes:

- D. L. Moody and G. G. Shanks, *What makes a good data model? A framework for evaluating and improving
  the quality of entity relationship models*, Australian Computer Journal, 1998; and *Improving the
  quality of data models: empirical validation of a quality management framework*, Information Systems
  28 (2003). Qualities: completeness, integrity, understandability, simplicity, integration,
  implementability.
- S. Hoberman, *Data Model Scorecard: Applying the Industry Standard on Data Model Quality*,
  Technics Publications, 2015. Ten categories: correctness against the requirements, completeness,
  structure, abstraction, standards, readability, definitions, real-world (physical) context,
  consistency with the enterprise, metadata matching the data.
- The north-star invariants of the platform (private memory, tenant isolation, no content for the
  operator), ADR-0042 and DEC-0056.

## Scale

Each criterion is graded per group of tables:

| Grade | Meaning |
|---|---|
| 2 | met, with evidence |
| 1 | partly met; the gap is named |
| 0 | not met; the violation is named |

A grade without evidence is not a grade. Evidence is a location (file and line), a measurement (file
under `../assessments/`), or the output of a script named in `measurement.md`.

## Knock-out rule

A criterion marked **knock-out** that is graded 0 for any group blocks the model, whatever the other
grades are. A knock-out criterion protects a guarantee the platform makes to its customers; no sum of
other qualities compensates for breaking it.

## Weights

`high`, `medium`, `low`. The weight orders the findings of an assessment and decides which gaps are
resolved before the model is accepted: every gap on a `high` criterion is resolved or accepted by the
operator with a written reason; gaps on `medium` and `low` are reported.

## The criteria

### Conceptual level

| # | Criterion | Source | Method | Weight |
|---|---|---|---|---|
| F1 | Every requirement on stored data in TAR-0004, TAR-0002 B17 to B21, DEC-0054, DEC-0055 and DEC-0056 has a carrier in the model | Hoberman 1 (correctness), Moody/Shanks completeness | a trace table from clause to concept, built from the clauses | high |
| F2 | Every concept has a definition a reader understands without the SQL | Hoberman 7 (definitions), Moody/Shanks understandability | review; the model format makes the definition a mandatory field | high |
| F3 | Terms agree across the services: item, instance, task, act, reference mean the same in TAR-0002, TAR-0004 and TAR-0006 | Hoberman 9 (consistency), Moody/Shanks integration | comparison against the three targets | medium |
| F4 | No concept without a requirement: nothing is stored for a use no clause names | Moody/Shanks simplicity | a trace table from concept to clause | medium |

### Logical level

| # | Criterion | Source | Method | Weight |
|---|---|---|---|---|
| L1 | Third normal form or Boyce-Codd normal form; every deviation carries its reason | Hoberman 3 (structure) | per table, the functional dependencies written out | high |
| L2 | The level of abstraction fits: no schema per customer, no entity-attribute-value table standing in for declared fields | Hoberman 4 (abstraction) | review against DEC-0056/C2 to C7 | medium |
| L3 | No column that is filled only some of the time without a stated reason | review criterion 16 of the code conventions | per nullable column, the condition under which it is null | medium |
| L4 | **knock-out.** The kinds of data are kept apart: every column is a system field, a control field, a free field or content, and no column is two | DEC-0056/C1 | every column assigned to exactly one kind | knock-out |
| L5 | **knock-out.** What is written once is never changed: commissions, returns, addenda, field values, start values and the trace | TAR-0004 B5, DEC-0056/C9 | per table, the constraint, trigger or grant that refuses the update | knock-out |
| L6 | Names follow one convention | Hoberman 5 (standards) | a lint over the physical names | low |

### Physical level

| # | Criterion | Source | Method | Weight |
|---|---|---|---|---|
| P1 | **knock-out.** Tenant isolation: every table carries tenant and scope in its key, and row-level security is on for every table | ADR-0042, north star | a query over the system catalogue: no table without, no policy missing | knock-out |
| P2 | **knock-out.** No content where the operator reads: the text of the work stands only in the tables that hold content, and no role of the operator reads them | north star (ops-no-content, private memory) | the grants of every role against the tables that hold content | knock-out |
| P3 | Integrity is kept by the database where it can be: every invariant of the logical level that a foreign key, a check or a unique constraint can express is expressed by one | Moody/Shanks integrity | per invariant, the constraint that keeps it, or the reason none can | high |
| P4 | The hot paths are fast enough: `query`, the next acts of a task, `read_trace`, writing one act, and the check of the gate | Hoberman 8 (physical), Moody/Shanks implementability | `EXPLAIN (ANALYZE, BUFFERS)` on a store of ten times the current stock (`measurement.md`) | high |
| P5 | The cost of writing one act: rows and index entries per kind of act | Moody/Shanks implementability | a count per kind of act | medium |
| P6 | The wave that builds the process rules adds to the store without rebuilding what is there | REA-0010 | the delta of that wave against the store line | medium |

### Across the levels

| # | Criterion | Source | Method | Weight |
|---|---|---|---|---|
| R1 | Every table maps to a concept and every concept to at least one table | Hoberman 10 (metadata matches the data) | the reconciliation script (`measurement.md`) | high |
| R2 | The model is readable: grouped, with its core named, one diagram per group | Hoberman 6 (readability) | review by the operator | medium |

## What the catalogue does not assess

The maturity of data management in an organisation, the quality of the data in operation (ISO/IEC
25012), and the volume of the stock. Each is legitimate and none is a property of the schema.

## Changing the catalogue

The catalogue is changed by a pull request like any other file of this repository. An assessment names
the commit of the catalogue it applied, so that two assessments are comparable only when they name the
same one.
