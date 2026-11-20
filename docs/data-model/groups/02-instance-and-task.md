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
| An instance never changes selector or number, a task never changes instance or sub-number. | the UPDATE grants name only the projection columns |
| A closed instance or task carries its resolution, an open one none. | `ck_instance_closed_resolved`, `ck_task_closed_resolved` |
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

## Open in the assessment

- `title`, `activity_id` and `concerns_task_id` of `task` are updatable as projections of the
  commission and the draft; whether this meets L5 (insert-only where the method demands it) is judged
  there.
- No store check binds a projection to its trace; the measurement checks it by rebuild.
