# Group 3: trace

The acts on an instance and its tasks, and the idempotency keys. Model: `../model/trace.linkml.yaml`.
Physical level: store line V1 (`act`, `idempotency_key`). Diagram: `../diagrams/03-trace.puml`.

## Purpose

Every change to an instance or a task is an act: who acted, under which permission, by which path,
when, and into which state (TAR-0004 2.5). The ordered acts of an instance are its trace. The trace is
the record the platform owes an auditor and the source every projection is folded from. An idempotency
key lets a caller repeat a call after a lost answer and receive the same object instead of a second one.

## Concepts

| Concept | Table | In one sentence |
|---|---|---|
| Act | `act` | One act of an identity on an instance or one of its tasks, at its place in the trace. |
| Idempotency key | `idempotency_key` | The first answer to a caller's key: the task a create made or the act a call wrote. |

## Invariants and what keeps them

| Invariant | Kept by |
|---|---|
| An act is never changed or deleted. | no UPDATE and no DELETE grant on `act` |
| The acts of an instance are totally ordered. | `uq_act_instance_sequence`; the sequence, not the time, orders |
| The object of an act follows its kind: some act on the instance alone, some on a task, some on either. | `ck_act_object` |
| An act on a task acts on a task of its own instance. | the foreign key over `(instance_id, task_id)` |
| An act refers only to an earlier act of the same instance, never to itself. | `ck_act_refers_other`; the foreign key over the instance |
| An authentication level is recorded only for the console path. | `ck_act_authentication_level` |
| A lapse is never written. | by construction (ADR-0002): a holding without an ending act whose lease has passed ended then |
| A key answers at most one object per caller. | `uq_idempotency_key_caller_key`, `ck_idempotency_key_one_target` |

## Questions this group answers

- What happened to this instance, in which order, by whom and under which permission?
- Which persons stood behind the delegated identities that acted (by owner)?
- Which act led a task into its current state, and which delivery does a rework answer?
- Did this call already happen, and what did it answer?

## Why it is cut this way

- **One act table for every kind.** The trace is read as one ordered list; one table makes the order
  a key and the read a range scan. The payloads that differ by kind live in tables of their own
  (groups 4 to 9), each anchored on its act.
- **Actor and owner as two columns.** Delegated identities act; persons are compared by owner (four
  eyes). Holding both makes the comparison a column comparison.
- **`to_state` on the act.** The state a task was in after any act is read without folding; the
  projection on the task is the last of these.
- **The idempotency key apart from the act.** A create that makes a task in draft writes no act; the
  key must answer it anyway.

## Rejected alternatives

Reconstructed for this chapter from the corpus and the store; where no node records the choice, the
assessment says so.

- **Payloads as JSON columns on the act.** Rejected: no foreign key could hold an option, a criterion
  or an outcome named by a payload, and the gate would parse on every read (format chapter, DEC-0056).
- **Time as the order.** Rejected: two acts in the same transaction or on clocks out of step would
  have no defined order.
- **Writing lapses as acts.** Rejected by ADR-0002/C2: a writer at the moment of expiry would make
  correctness depend on a scheduler.

## What the next wave adds here

Interventions (rebind, repair, recall by a supervisor) and decision bases of routing acts add act kinds
and payload tables anchored on the act (TAR-0004 2.6, 2.10); the act table itself changes only by its
kind check.

## Open in the assessment

- The act payload tables (groups 4 to 6) are 0..1 per act and kind; whether a payload per kind or a
  generic payload is the better cut is assessed against prior art (EventStoreDB, Axon, Temporal,
  Camunda).
- `ck_act_kind` lists 26 kinds; each new kind is a migration of a check.
