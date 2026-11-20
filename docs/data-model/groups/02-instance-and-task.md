# Group 2: instance and task

One run of a declared process and the tasks within it. Model: `../model/run.linkml.yaml`. Physical
level: store line V1 (`instance`, `task`). Diagram: `../diagrams/02-instance-and-task.puml`.

## Purpose

An instance is one numbered run of a process a selector declares; a task is one execution of one
activity within it (TAR-0004 2.3, 2.4). Both are the objects the verb surface addresses:
`dispatch://<scope>/<selector>/<number>` names an instance, `.../<number>.<sub>` a task. Everything the
service knows about them is written as acts (group 3); the columns here that change are projections of
those acts, kept so that a read and the gate need not fold the trace on every call.

## Concepts

| Concept | Table | In one sentence |
|---|---|---|
| Instance | `instance` | One numbered run of a process, bound to the definition version in force at its start. |
| Task | `task` | One execution of one activity within an instance, numbered by its sub-number. |

## Invariants and what keeps them

| Invariant | Kept by |
|---|---|
| A number is unique per selector; a sub-number unique per instance. | `uq_instance_selector_number`, `uq_task_instance_sub`; the counters `selector.next_number` and `instance.next_sub` |
| An instance never changes selector or number, a task never changes instance or sub-number. | the UPDATE grants name no identity column: on `instance` the counter `next_sub`, the bound version `definition_version_id` and the projections `state` and `resolution`; on `task` the projections `title`, `activity_id`, `concerns_task_id`, `state`, `hold_reason`, `resolution`, `not_before` and `parked` |
| A closed instance or task carries its resolution, an open one none. The two sets differ: an instance is `concluded` or `withdrawn`, a task `accepted`, `rejected`, `failed` or `withdrawn`. | `ck_instance_closed_resolved`, `ck_task_closed_resolved`; `ck_instance_resolution`, `ck_task_resolution` |
| A task on hold carries its reason, no other task does. | `ck_task_on_hold_reason` |
| `not_before` only on an open task; `parked` only on a task held for an external reason. | `ck_task_not_before_open`, `ck_task_parked` |
| A task concerns another task of the same instance, never itself. | `ck_task_concerns_other`; the foreign key over `(instance_id, concerns_task_id)` |
| The trigger task belongs to the same scope. | the foreign key over tenant and scope |
| A task is deleted only while it has no act (a draft never sent). | DELETE grant on `task`; every act and payload references it without delete along |
| The projection agrees with the trace. | the service writes both in one transaction; the store cannot check a fold |

## Questions this group answers

- What is the state of an instance or a task right now, and how did a closed one end?
- Which tasks of an instance are open, held, on hold, delivered?
- Which version does a running instance run under?
- Which task does a review concern?
- Which open tasks are addressed to a given role (the selection of an executor, from V4)?
- Which instance did a task of this scope start?

## Why it is cut this way

- **State as a projection, not only a fold.** The gate decides on the current state on every act;
  folding the trace each time would put the length of a trace on the hot path. The cost is a second
  copy of the state, held consistent by the service alone. The trace stays the truth: a projection is
  rebuildable from it.
- **One task table for all activities.** Activities are declared data (group 1), not types; a table
  per activity would turn a definition into a migration.
- **`concerns_task` on the task, not only on the commission.** It is the one edge between tasks the
  rules evaluate (the review concerns the code, TAR-0004 2.4); held on the task it is read without a
  join through the commission.
- **The trigger task as a column, other triggers as references.** A task of the same scope can be
  held by a foreign key; anything outside the service is an opaque value (group 7).

## Rejected alternatives

Reconstructed for this chapter from the corpus and the store; where no node records the choice, the
assessment says so.

- **State only as a fold of the trace (pure event sourcing).** Rejected for the hot path as above;
  the trace is kept complete so the choice is reversible.
- **Instance and task in one table with a parent column.** Rejected: an instance and a task have
  different states, different addresses and different acts; one table would carry two state machines
  behind nullable columns.

## What the next wave adds here

Derived fields of an instance and the process selection (TAR-0004 2.2, 2.3) attach by reference; the
tables here change only by addition.

## Assessed, and what V4 changes

Assessment: `../assessments/2026-11-20-assessment.md`; decisions: `../assessments/2026-11-20-decisions.md`.
The tables above describe V1 to V3; the model and the diagram carry V4.

- **A task under `.0`.** `ck_task_sub` admits 0 from V4, for the task a bracket of the bracket store
  is copied from (REA-0010 S4). The counter of the instance still starts at 1: a new instance has no
  task under `.0`, its first task is `.1`, and the instance itself carries no sub-number.
- **The title of an instance.** A nullable column, set by `start` and never changed; for a copied
  bracket the title of its task under `.0`. Without it a new instance had no name in a listing.
- **The input text of an instance** is content on its start act (group 9).
- **The version belongs to the selector.** A foreign key over (`selector_id`,
  `definition_version_id`) binds it; a rebind stays inside the selector.
- **An index for the listing of an executor** over state and addressed role: the open tasks of a
  scope were read without one (17.9 ms against 0.04 ms in the assessment).
- **The addressed role** arrives as a projection column, as decided before the assessment.
- The projections of the task stay updatable; the store cannot check a fold, the service writes act
  and projection in one transaction, and the measurement checks it by rebuild.
