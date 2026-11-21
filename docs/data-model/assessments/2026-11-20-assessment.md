# Assessment of the store line V1 to V3, 2026-11-20

The first assessment of the data model of the dispatch service against the evaluation catalogue. It
records grades and evidence. It decides nothing: every finding below is put to the operator, and what
changes in the store line is decided there and built as V4.

## What was applied

| | |
|---|---|
| Store line | `backend/src/main/resources/db/store/` V1 to V3 at `be20877` (branch `wave-2`), 35 tables |
| Model | `docs/data-model/model/` at `a2fc048`, three definitions added with this assessment (finding 15) |
| Catalogue and method | `method/evaluation-catalogue.md` and `method/measurement.md` at `a2fc048` |
| Corpus read against (F1) | `Kumbuka-ai/platform-specs` at `7a1f4c4`: TAR-0004 sections 2.2 to 2.4 and 2.8 and its building blocks B1 to B7 in full; of DEC-0054, DEC-0055 and DEC-0056 the opening of each clause, not its full text; TAR-0002 B17 to B21. TAR-0006 was not read |
| Database | PostgreSQL 16.15, `shared_buffers` 128 MB, `work_mem` 4 MB, `fsync` and `synchronous_commit` on |
| Machine | 2 cores, 7 GB memory, a container; times are comparable with each other and with nothing else |
| Dataset | `scripts/data-model/seed-volume.sql`, deterministic, no seed value: two tenants, three scopes each, per scope 3,000 instances, 36,000 tasks, about 215,000 acts; in all 1.29 million acts, 1.49 million content rows, 2.9 GB |
| Role | every read and write measured as `kumbuka_dispatch` with the tenant setting of one tenant |

## Result

- No knock-out criterion is graded 0. Tenant isolation (P1), the grants on content (P2), the kinds of
  data (L4) and write-once (L5) hold; L5 carries one gap in group 7 (finding 8).
- One finding blocks a step of REA-0010 as the store stands: a task under `.0` is refused (finding 1).
- One hot path of this wave does not hold as the store stands: a listing that filters or sorts by a
  control field (finding 2).
- Gaps on criteria of weight `high`, each to be resolved or accepted by the operator with a reason:
  F1 (findings 1, 12), F2 (finding 15, resolved here), P3 (findings 4 to 7), P4 (findings 2, 3, 13).

## Grades

Groups: 1 definition, 2 instance and task, 3 trace, 4 commission and return, 5 holding, 6 dialog,
7 references, 8 control fields, 9 free fields and content, 10 draft workspace. A number in brackets
names the finding that explains a grade below 2. `op` is a criterion whose method is a review by the
operator; `w` is waived by the operator.

| # | Weight | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| F1 | high | 1 (12) | 1 (1, 12) | 1 (12) | 1 (12) | 2 | 2 | 1 (12) | 1 (12) | 2 | 1 (12) |
| F2 | high | 1 (15) | 2 | 2 | 2 | 2 | 2 | 2 | 2 | 2 | 2 |
| F3 | medium | 1 (9) | 2 | 2 | 2 | 2 | 2 | 1 (9) | 2 | 2 | 2 |
| F4 | medium | 2 | 2 | 2 | 2 | 2 | 2 | 2 | 2 | 2 | 2 |
| L1 | high | 2 | 2 | 2 | 2 | 2 | 2 | 2 | 2 | 2 | 2 |
| L2 | medium | 2 | 2 | 2 | 2 | 1 (7) | 1 (7) | 2 | 2 | 2 | w |
| L3 | medium | 2 | 2 | 2 | 2 | 2 | 2 | 2 | 2 | 2 | 2 |
| L4 | knock-out | 2 | 2 (10) | 2 | 2 (10) | 2 | 2 (10) | 2 | 2 | 2 | 2 |
| L5 | knock-out | 2 | 2 | 2 | 2 | 2 | 2 | 1 (8) | 2 | 2 | 2 |
| L6 | low | 2 | 2 | 2 | 2 | 2 | 2 | 2 | 2 | 2 | 2 |
| P1 | knock-out | 2 | 2 | 2 | 2 | 2 | 2 | 2 | 2 | 2 | 2 |
| P2 | knock-out | 2 | 2 | 2 | 2 | 2 | 2 | 2 | 2 | 2 | 2 |
| P3 | high | 2 | 1 (4) | 1 (7) | 1 (5, 7) | 1 (6, 7) | 1 (7) | 2 | 2 | 2 | 2 |
| P4 | high | 2 | 1 (3) | 1 (13) | 2 | 2 | 2 | 2 | 1 (2) | 2 | 2 |
| P5 | medium | 2 | 2 | 2 | 2 | 2 | 2 | 2 | 2 | 2 | 2 |
| P6 | medium | 2 | 2 | 2 | 2 | 2 | 2 | 2 | 2 | 2 | 2 |
| R1 | high | 2 | 2 | 2 | 2 | 2 | 2 | 2 | 2 | 2 | 2 |
| R2 | medium | op | op | op | op | op | op | op | op | op | op |

## Findings

### 1. A task under `.0` is refused (F1, group 2)

TAR-0004 2.4: a task that existed before instances were separated keeps its address, `.0` included.
REA-0010 S4 copies the root task of every bracket as the first task of its instance under
`<number>.0`. `ck_task_sub` reads `CHECK (sub >= 1)`.

Measured: an insert into `dispatch.task` with `sub = 0` fails with
`new row for relation "task" violates check constraint "ck_task_sub"`. The copy of step 4 would fail
on its first bracket. `ck_instance_next_sub` (`next_sub >= 1`) is right as it stands: no new task
receives 0.

### 2. A listing by a control field reads every value of the field in the scope (P4, group 8)

REA-0010 puts the filter and the sort by control fields into the `query` of this wave
(DEC-0056/C22, C23). The value in force (C10) is the last value from the start or from an accepted
delivery, so the store cannot answer "instances whose field equals a value" from an index on
`field_value`: every candidate needs the acts after it.

Measured (`hot-paths/11-instances-by-value-in-force.sql`), one field of a selector with 1,800 instances and 22,680
values: 692 ms, 591,871 buffers, for a first page of 50. The cost grows with the scope.

Probe, not part of the store: a table with one row per instance and field holding the value in force,
72,000 rows and 23 MB for the whole dataset, with one index per typed column. The same listing:
0.06 ms and 13 buffers; a sort by a number field: 0.14 ms and 64 buffers; the values in force of one
instance: 0.04 ms and 4 buffers (against 1.6 ms and 3,176 buffers, finding 13).

The projection of the values in force is listed in `next-wave.md` as an addition of the next wave,
together with the mark `searchable`. This measurement says that the filter of this wave needs the
projection, or the filter moves to the next wave with it.

### 3. The tasks of a scope by state are read without an index on the state (P4, group 2)

`hot-paths/03-query-tasks-by-state.sql`, the open tasks of one scope, first page: 17.9 ms and 3,873
buffers, because every task of the scope is read (36,000) and the 300 open ones are sorted out. The
cost grows with the scope. With an index over tenant, scope, state, instance and sub-number, built
and rolled back for the probe: 0.04 ms and 8 buffers.

V4 already brings an index for the listing of an executor by addressed role (REA-0010 D16). The two
belong together: an executor asks for the open tasks addressed to its role.

### 4. That the version of an instance belongs to its selector is kept by the service (P3, group 2)

`instance.definition_version_id` references `definition_version (id)` under tenant and scope, and
`instance.selector_id` references `selector (id)`. Nothing binds the two. A composite foreign key
over (`selector_id`, `definition_version_id`) into a unique key on `definition_version` over
(`selector_id`, `id`) would; the table referenced holds a few rows per selector. The column stays
updatable for `rebind`, and the key would hold a rebind inside the selector.

### 5. That the concerned and the bound task of a commission lie in its instance is kept by the service (P3, group 4)

`commission` carries its act and no instance. `task.concerns_task_id` is bound by a key over
(`instance_id`, `concerns_task_id`); the commission, which is the record the projection on the task is
taken from, is bound by tenant and scope alone. One column `instance_id` on `commission`, with keys
over (`instance_id`, `act_id`) into `uq_act_instance_id` and over (`instance_id`, task) into
`uq_task_instance_id`, would bind it in the store. Both unique keys exist.

### 6. That the claim act of a holding acts on the holding's task is kept by the service (P3, group 5)

The key needs a unique key on `act` over (`task_id`, `id`), a sixth index on the largest table, paid
by every act. "At most one current holding per task" cannot be a key at all: a lapse is never written
(ADR-0002), so a lapsed holding and its successor are both without an ending act.

### 7. Which payload an act may carry is kept by the service; three payload tables hold one column (L2, P3; groups 3 to 6; item 1282)

The eight tables that hang 0..1 on an act (`commission`, `delivery`, `instance_return`, `hold`,
`deferral`, `question`, `answer`, `free_fields`) are class table inheritance (Fowler, *Patterns of
Enterprise Application Architecture*): one table per subtype of the act. The store binds each row to
an act; it does not bind it to the kind of the act. A `delivery` on a `claim` is refused by the
service alone.

Three of them carry a single column besides their act: `hold.reason`, `deferral.not_before`,
`question.free_text_admitted`. As nullable columns on `act` with one check each, of the form the store
already uses (`ck_task_on_hold_reason`), the binding to the kind would be the store's, three tables
and nine indexes would go, and L3 would gain three columns filled for one kind of act each, with the
condition stated in a check. `question_option` and `answer` would then reference the act.

For the five others the alternatives were weighed and none is better:

- `commission`, `delivery`, `instance_return`, `answer` have children or several columns; folded into
  `act` they would make it a single table of unrelated nullable columns.
- `delivery` and `instance_return` have the same columns today and part ways with the next wave: the
  instance return gains the status of the item criteria (DEC-0055/C9), the outcome of one belongs to
  an activity and of the other to a version.
- A generic payload column, the form event stores use (one serialized body per event), would put
  verdicts, outcomes and criteria statuses out of reach of foreign keys and of L4.
- Binding the kind by key (a unique key on `act` over id and kind, and a constant kind column on each
  payload table) is possible without a trigger and costs a sixth index on `act`.

### 8. The end of a reference stands only in a column that can be overwritten (L5, group 7)

`external_reference.ended_by_act_id` is updatable by grant. The model called it a projection at the
commit assessed; it is not one, and the model was corrected with the decisions on this assessment. The act that ends a reference (`unrefer`, `untag`) carries no payload, so which reference it
ended is recorded in this column and nowhere else. The service sets it once; the store would accept a
second update, and the trace could not correct it.

`holding.ended_by_act_id` has the same grant and is a projection in fact: the release, deferral,
delivery or closing act on the task names the holding it ended by its task.

### 9. Two terms differ from their neighbours (F3, groups 1 and 7)

- `selector` stands for the process definition: TAR-0004 B1 declares a definition "at a selector of a
  scope", and the table `selector` is the row every version hangs on. The worklist uses the same word
  for its views (item, iteration, milestone). There is no table named after the definition.
- A tag is held in two forms: `definition_tag.tag_id` is an identifier of the platform catalogue
  (`uuid`), a tag on an instance or task is an `external_reference` of kind `tag` with a text value.
  The two lifecycles differ with reason (a version never changes, a reference is set and ended by
  acts); the two forms of the value have none on record.

The comparison covered TAR-0004 and the clauses of TAR-0002 B17 to B21. TAR-0006 was not read again
for this assessment.

### 10. Text written by people is classified as system fields (L4, P2; groups 2, 4, 6)

`task.title`, `commission.title`, `draft.title`, `question_option.label` and `outcome_label.label` are
written by a commissioner, an asker or an author of a definition and are assigned to the kind
`system`. L4 holds as worded: each column has exactly one kind. Whether a title may appear where
system fields appear (a log line, a metric label, an operator's view) was not said in the model at the
commit assessed; the decisions on this assessment added the rule to the model.
P2 as measured concerns grants: no role but the service role and the owner of the tables holds any
privilege on any table, and the owner is subject to the forced policies.

### 11. One clause uses a form PostgreSQL has had since version 15 (portability)

`fk_idempotency_key_task` reads `ON DELETE SET NULL (task_id)`, the column list form introduced with
PostgreSQL 15. (Decided on 2026-11-21: it stays. The form without a column list would empty tenant and
scope of the key on a delete, which the columns refuse.) It is the only delete rule in the store line, and the only construct that is not as
old as partial unique indexes, check constraints, `jsonb` and row-level security. The store line uses
no trigger, no deferrable constraint, no exclusion constraint and no generated column.

### 12. Requirements on stored data without a carrier (F1)

| Requirement | Source | State |
|---|---|---|
| The addressed role of a commission | TAR-0004 2.4, REA-0010 D16 | V4, decided |
| The reference kind `item` | TAR-0004 2.3 | V4, decided; `ck_external_reference_kind` lists trigger, tag, reference, curation |
| A version marked as a seed | REA-0010 S4 | V4, decided |
| A task under `.0` | TAR-0004 2.4, REA-0010 S4 | refused by the store, finding 1 |
| The item criteria an instance takes over, their status and evidence in its return | DEC-0055/C9, TAR-0004 2.3 | no carrier; `next-wave.md` |
| Process rules, restrictions, obligations; the tenant rule set; the version of it that admitted an act | TAR-0004 2.2, 2.6 | next wave by REA-0010 |
| Derived control fields | DEC-0056/C11 | next wave by REA-0010 D14 |
| `rebind`, `repair` and their payloads | TAR-0004 2.7 | next wave |

The table is the result of reading the sections of the corpus named under "What was applied" against
the store and noting each requirement on stored data that found no table or column. It is not a trace
matrix: no list of every clause with its concept was written, the clauses of DEC-0055 and DEC-0056
were read by their opening words, and the foreign review of 2026-11-20 found the claim of a complete
trace unbacked. What holds is the other direction (F4): every class of the model names the clause it
comes from. A trace matrix from clause to concept is still to be written and is what level 2 of the
maturity waits for.

### 13. The values in force of one instance, read from the trace (P4, group 3)

`hot-paths/10-values-in-force-of-instance.sql`: 1.6 ms and 3,176 buffers for an instance of 74 acts,
because each act is checked against the accepting acts of the instance and `refers_to_act_id` has no
index. The same read written as a join: 359 buffers. Either way it grows with the instance, and the
gate of the next wave asks for these values on every act it checks. The projection of finding 2
answers it in 4 buffers.

### 14. A join that leaves out tenant and scope leaves the index (note for the realization)

Every index but the primary key leads with `tenant_id`, `scope_id`. A join written over the id alone
is correct under row-level security and cannot use those indexes. Measured in the write path of
`deliver`: looking up the criteria of the task's commission took 17 ms without the two columns in the
join and under 1 ms with them. The store is right; the queries of S5 carry tenant and scope in every
join.

### 15. Three attributes had no definition (F2, group 1; resolved)

`Activity.name`, `Activity.verdict_required` and `Activity.outcome_required` carried no description.
The definitions are added with this assessment. No column of the store line carries a comment; every
table does. The definitions live in the model, which is where F2 looks.

## Measurements

### Hot paths

Median of five runs after one warm-up, `scripts/data-model/run-hot-paths.sh`. Buffers are shared
buffers touched by the median run. No path uses a sequential scan.

| Path | Time, ms | Buffers | Grows with |
|---|---|---|---|
| 01 running instances of a selector, first page | 1.01 | 326 | the selector (1,800 instances read, 180 kept) |
| 02 instances of a selector behind a cursor | 0.55 | 104 | the page |
| 03 open tasks of a scope | 17.86 | 3,873 | the scope (finding 3) |
| 04 instances by a reference of a kind | 0.14 | 30 | the result |
| 05 next acts: task, current holding, lease in force | 0.21 | 15 | constant |
| 06 state of a task from its trace | 0.09 | 7 | constant |
| 07 trace of an instance, first page | 0.16 | 31 | the page |
| 08 trace of an instance behind a cursor | 0.12 | 21 | the page |
| 09 a task with commission, criteria and text | 0.26 | 25 | the task |
| 10 values in force of an instance | 1.60 | 3,176 | the instance, squared (finding 13) |
| 11 instances by a value in force | 692.29 | 591,871 | the scope (finding 2) |

The check of the gate against the rules of a version is not measured: the rules are built by the next
wave. Paths 10 and 11 are the reads it will make.

### Writing one act

`hot-paths/writes/`, as the service role. `claim` with holding, lease, receipt and idempotency key:
4.9 ms, planning included. `deliver` with two criteria, three texts, three field values and free fields: 6.0 ms, of which
the 37 foreign key checks take about 2 ms. `start` with two field values, free fields and three
references, committed with `fsync` on: 1.9 ms.

Rows written, index entries maintained and foreign keys checked per kind of act (P5), for the shape
named:

| Act | Rows | Index entries | Foreign key checks | Tables |
|---|---|---|---|---|
| `deliver` (2 criteria, 3 texts, 3 values, free fields) | 11 | 44 | 37 | 6 |
| `start` (2 values, free fields, 3 references) | 8 | 34 | 27 | 5 |
| `send` (2 criteria, 3 texts, free fields) | 8 | 32 | 25 | 5 |
| `ask` (3 options) | 6 | 28 | 12 | 4 |
| `claim` (with idempotency key) | 5 | 19 | 11 | 5 |
| `answer` | 4 | 18 | 13 | 4 |
| `renew` | 2 | 9 | 5 | 2 |
| `accept` | 1 | 5 | 3 | 1 |

An act row with its five indexes takes 545 bytes in the dataset. Every table carries two indexes that
identify a row: the primary key on the id and the unique key over tenant, scope and id that the
foreign keys reference (ADR-0042). That is the price of P1 and is paid on every insert.

### The counter of a selector under parallel starts

`pgbench`, 15 seconds each, the `start` above as one transaction that locks the row of the selector.

| Clients | Selectors | Starts per second | Mean latency, ms |
|---|---|---|---|
| 1 | 1 | 538 | 1.9 |
| 8 | 1 | 490 | 16.3 |
| 32 | 1 | 350 | 91.4 |
| 8 | 5 | 1,035 | 7.7 |
| 32 | 5 | 915 | 35.0 |

Starts on one selector run one after another, as the counter demands, at 350 to 540 per second on two
cores. No failed transaction. The counter is a bound on one selector and not a defect.

### Catalogue checks

`scripts/data-model/catalogue-checks.sql`: P1a to P1d return no row (35 tables, 71 foreign keys, 35
forced policies). L5: the service role holds UPDATE on 25 columns of 10 tables and DELETE on `task` and
the four tables of the draft workspace, and no other privilege beyond SELECT and INSERT. P2: no role
besides the service role and the owner holds a privilege on a table or a column. The checks for DELETE
and for P2 are added to the script with this assessment.

Names (L6): every primary key is `<table>_pkey`, every other constraint and index carries `fk_`, `uq_`, `ck_`
or `idx_` and the table name;
every column is lower snake case; no exception found by a query over `pg_constraint`, `pg_indexes`
and `information_schema.columns`.

Kinds of data (L4): 161 attributes of the model, each in exactly one of system (122), control (22),
content (13) and free (4).

Reconciliation (R1): 35 concepts with a table, four reports, all four the columns V4 adds
(`definition_version.seed`, `addressed_role` on commission, draft and task).

### Normal form (L1)

Every table has its id as key and its unique constraints as further candidate keys; no non-key column
depends on a part of a candidate key or on another non-key column, with these deliberate exceptions,
each stated in the model:

- `task.title`, `activity_id`, `concerns_task_id`, `state`, `hold_reason`, `resolution`, `not_before`,
  `parked` and `instance.state`, `resolution`: projections of the trace, written with the act in one
  transaction.
- `selector.next_number`, `instance.next_sub`: counters.
- `field_value.value_type`, `enumeration_id`: copies of the field's type and enumeration, held equal
  by composite foreign keys, so that a check on the row can bind the typed column.
- `holding.ended_by_act_id`: projection (finding 8 for its sibling on the reference).

The functional dependencies were written out for these tables only; for the others the statement
rests on the unique constraints of the catalogue.

### Abstraction (L2)

`field_value` holds one typed column per type of the platform's catalogue and binds each value to a
declared field by key. It is not an entity-attribute-value table standing in for declared fields: the
fields are declared (`control_field`), the types are a closed set, and the store checks type and
enumeration. The owner of an `external_reference`, of an `outcome_label` and of a `contract_field` is
a pair of nullable columns with a check that exactly one is set, the exclusive arc that Karwin (*SQL
Antipatterns*) names as the remedy for polymorphic associations. The draft workspace is waived by the
operator.

### The next wave (P6)

`next-wave.md` lists every addition of the next wave against the table it touches: new tables,
nullable columns on `act`, `activity`, `content`, `definition_version` and `instance`, and widened
checks. Nothing rebuilds a table of this line. Widening `ck_act_kind` validates the check against
every act once.

## What this assessment did not do

- The proportions of the dataset are estimated from the size of the steering corpus (about 300
  instances and 3,500 tasks), not counted in the production store as `measurement.md` asks.
- The parameters of the hot paths are constants in the SQL; plans for bound parameters may differ.
- 128 MB of shared buffers against 2.9 GB of data: buffer counts are exact, times include reads from
  the page cache of the operating system.
- R2 is a review by the operator. The walkthrough of groups 2 to 10 was held on 2026-11-20; its
  verdict is the operator's and is not entered here.
- No latency budget exists (TAR-0004 leaves it to the realization that builds the rules). The grades
  under P4 name paths whose cost grows with the scope or with the square of the instance, and state
  no verdict on a time.
