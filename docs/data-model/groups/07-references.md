# Group 7: references outside the service

Values that name objects outside the service. Model: `../model/reference.linkml.yaml`. Physical level:
store line V1 (`external_reference`); the kind `item` arrives with V4. Diagram:
`../diagrams/07-references.puml`.

## Purpose

An instance serves worklist items, was started by something, carries tags; a closed task was curated
into a memory entry or a document. None of these objects belongs to the dispatch service. The service
holds each as an opaque value with a kind, set by an act and ended by an act, hands it out and never
resolves it (TAR-0004 2.3, DEC-0055/C5, P1 of the platform: no service reads into another's store). The
relation between an instance and a worklist item is many-to-many: an instance may serve several items,
an item may be served by several instances; each service holds its own half.

## Concepts

| Concept | Table | In one sentence |
|---|---|---|
| External reference | `external_reference` | A value of a kind naming something outside the service, held on one instance or one task. |

The kinds:

| Kind | Held on | Meaning |
|---|---|---|
| `trigger` | instance | What started the instance, if not a task of the scope (that one is `instance.trigger_task`); never ended. |
| `tag` | instance or task | A tag of the platform catalogue. |
| `item` | instance | A worklist item the instance serves; the anchor of the criteria contract (DEC-0055/C9). From V4. |
| `reference` | instance | Any other object outside the service the instance serves or was handed over to. |
| `curation` | task | The object a closed task was curated into. |

## Invariants and what keeps them

| Invariant | Kept by |
|---|---|
| A reference is held on exactly one instance or one task. | `ck_external_reference_owner` |
| `trigger` and `reference` on an instance, `curation` on a task. | `ck_external_reference_instance_kinds`, `ck_external_reference_task_kinds`; V4 adds `item` to both the kind list and the instance kinds |
| At most one current reference of a kind and value per owner. | partial unique indexes `uq_external_reference_instance_current`, `uq_external_reference_task_current` |
| A trigger is never ended. | `ck_external_reference_trigger_not_ended` |
| Only the ending act is ever set. | UPDATE grant on `ended_by_act_id` alone |
| The value is never parsed or resolved; the kind is named by the caller, never derived from the value. | the service (REA-0010 D12) |

## Questions this group answers

- Which items does this instance serve right now, and which did it serve before?
- Which instances serve a given item (query by reference of a kind, REA-0010 S5)?
- What started this instance, which tags does it carry, where was this task curated into?

## Why it is cut this way

- **One table for every kind.** All kinds share shape, lifecycle and the query by kind and value; a
  table per kind would repeat the shape five times.
- **Kinds named, not inferred.** A value such as `worklist://kumbuka/item/1207` could be parsed, but
  parsing would make this service depend on the address form of another (P1, REA-0010 D12).
- **Set and ended by acts.** The history of a reference is part of the trace; a delete would lose it.

## Rejected alternatives

Reconstructed for this chapter from the corpus and the store; where no node records the choice, the
assessment says so.

- **Items in the free-field document.** Rejected in REA-0010 S4 after DEC-0056: free fields are never
  evaluated, but the criteria contract and the query by item evaluate the item.
- **A foreign key into the worklist store.** Rejected by tenant and service isolation: no service
  references another's tables.

## What the next wave adds here

The kind `item` (V4). The copy of the bracket store writes the item keys of its metadata as `item`
and the origin, predecessor, follow-up, merge and handover keys as `reference`, on the root task's
instance only (REA-0010 S4).

## Assessed, and what V4 changes

Assessment: `../assessments/2026-11-20-assessment.md`; decisions: `../assessments/2026-11-20-decisions.md`.

- **The kind `item`** arrives with V4.
- **The end of a reference is not a projection.** The act that ends a reference carries no payload,
  so `ended_by_act_id` is the only record of which reference it ended. The column stays updatable by
  grant; the service sets it only while it is empty, and a test of S5 checks that. Accepted by the
  operator: an insert-only form would be one more table hanging 0..1 on a row.
- The owner as a pair of nullable columns with a check is the exclusive arc, the usual remedy for a
  polymorphic association; it stays.
- A tag is held in two forms, an identifier at a definition version and a text value here; recorded,
  unchanged (finding 9).
