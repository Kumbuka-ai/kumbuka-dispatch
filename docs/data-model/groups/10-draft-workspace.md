# Group 10: draft workspace

The only changeable place of the store. Model: `../model/draft.linkml.yaml`. Physical level: store line
V2 (`draft`, `draft_criterion`, `draft_free_fields`, `draft_text`). Diagram:
`../diagrams/10-draft-workspace.puml`.

## Purpose

A task is written before it is sent: as a draft after create, or again after a recall. Until it is sent
its commission is work in progress and may change. The workspace holds that work in progress; a send
writes it out to the insert-only tables (commission, criteria, content, free fields) under the send
act, and empties the workspace (TAR-0004 2.4).

## Concepts

| Concept | Table | In one sentence |
|---|---|---|
| Draft | `draft` | The control a task in draft will carry when sent, one per task. |
| Draft criterion | `draft_criterion` | A criterion of a draft by identifier and place. |
| Draft free fields | `draft_free_fields` | The free-field document the draft will carry. |
| Draft text | `draft_text` | The commission text of the draft or the text of one of its criteria. |

## Invariants and what keeps them

| Invariant | Kept by |
|---|---|
| One draft per task, one free-field document per draft. | `uq_draft_task`, `uq_draft_free_fields_draft` |
| A criterion identifier is unique in its draft. | `uq_draft_criterion_identifier` |
| One commission text per draft, one text per criterion; a criterion text hangs on a criterion of the same draft. | partial unique indexes; the foreign key over `(draft_id, draft_criterion_id)` |
| Only the work-in-progress columns change. | UPDATE grants on the columns named in the model; DELETE granted on every table |
| A draft row hangs only on a task in draft or recalled. | the service; without triggers the store cannot |
| Sending empties the workspace, children first. | the service; no foreign key deletes along |

## Questions this group answers

- What will this task carry when it is sent?
- Which criteria and texts has the author written so far?

## Why it is cut this way

- **A workspace apart from the record.** Everything the trace holds is insert-only; a draft is not
  yet part of the record and must change freely. Mixing both in one table would mean UPDATE grants on
  the record.
- **The same shapes as the record.** Draft and commission, draft criterion and commission criterion,
  draft text and content of kind commission or criterion: sending is a copy, not a transformation.
- **Deleted, not ended.** A draft never sent leaves no trace; the trace starts with the send.

## Rejected alternatives

Reconstructed for this chapter from the corpus and the store; where no node records the choice, the
assessment says so.

- **The draft as one JSON document.** Rejected: criteria and texts would be unchecked until the send,
  and the send would parse.
- **Writing the commission at create and correcting it by addenda.** Rejected: a commission is frozen
  at send (TAR-0004 2.4); before that it is not a record.

## What the next wave adds here

Draft field values for the start and result contracts, if the console edits control fields of a draft
(TAR-0004 2.7); they would follow the same pattern.

## Open in the assessment

- The workspace duplicates four shapes of the record; assessed under L2 against a state column on the
  record tables.
- `draft_free_fields` is also where the free metadata of a copied bracket-store draft lands (REA-0010
  S4).
