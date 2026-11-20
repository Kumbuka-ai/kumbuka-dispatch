# Group 6: dialog

The questions an executor asks during a task and their answers. Model: `../model/dialog.linkml.yaml`.
Physical level: store line V1 (`question`, `question_option`, `answer`). Diagram:
`../diagrams/06-dialog.puml`.

## Purpose

Where the interaction contract of an activity admits dialog, the executor of a task may ask: the task
goes on hold with reason question until it is answered (TAR-0004 2.4). A question may offer options and
may admit free text; an answer chooses an option, gives free text, or both. Texts are content (group 9).

## Concepts

| Concept | Table | In one sentence |
|---|---|---|
| Question | `question` | A question an ask act puts, and whether free text is admitted. |
| Question option | `question_option` | An option a question offers, by its label and place. |
| Answer | `answer` | The answer an answer act gives, at most one per question, with the option chosen. |

## Invariants and what keeps them

| Invariant | Kept by |
|---|---|
| One question per ask act, one answer per answer act. | `uq_question_act`, `uq_answer_act` |
| A question is answered at most once. | `uq_answer_question` |
| A label and a place are unique in their question. | `uq_question_option_label`, `uq_question_option_ordinal` |
| The option chosen is one of that question's own. | the foreign key over `(question_id, option_id)` |
| Nothing changes once written. | no UPDATE grant |
| An answer without option carries free text, and only where admitted. | the service; content of kind answer |
| Ask only where the activity's contract admits it. | the service, from `activity.may_ask` |

## Questions this group answers

- Which questions were asked on this task, with which options, and how were they answered?
- Is a question still open (and the task on hold for it)?
- Which addenda supplement an answer given by an option alone (group 9)?

## Why it is cut this way

- **The answer bound to the question, not only to the act.** One answer per question is a key; the
  question it answers is read without walking the trace.
- **Options as rows.** The composite key makes "an option of this question" a store guarantee.

## Rejected alternatives

Reconstructed for this chapter from the corpus and the store; where no node records the choice, the
assessment says so.

- **Questions as content of kind question alone.** Rejected: options and the one-answer rule could
  not be held by keys.

## What the next wave adds here

Nothing planned.

## Open in the assessment

- `question` carries one boolean besides its act; assessed with the other payload tables.
