# Group 4: commission and return

What a task is commissioned with and what it returns, and what an instance closes with. Model:
`../model/work.linkml.yaml`. Physical level: store line V1 (`commission`, `commission_criterion`,
`delivery`, `delivery_criterion`, `instance_return`). Diagram: `../diagrams/04-commission-and-return.puml`.

## Purpose

A send gives a task its commission: title, activity, the task it concerns, the task whose return it
takes as bound input, and the acceptance criteria. A deliver gives back a return: verdict, outcome and a
status per criterion. An instance closes with a return of the same shape (TAR-0004 2.3, 2.4). Each is
written once, by the act that carries it, and never changed; a rework leads to a new delivery, not to an
edited one.

## Concepts

| Concept | Table | In one sentence |
|---|---|---|
| Commission | `commission` | The control a send gives a task; its text is content (group 9). |
| Commission criterion | `commission_criterion` | An acceptance criterion by its identifier and place; its text is content. |
| Delivery | `delivery` | The verdict and outcome a deliver returns, where the result contract asks for them. |
| Delivery criterion | `delivery_criterion` | The status a delivery reports for one criterion; its evidence is content. |
| Instance return | `instance_return` | The verdict and outcome an instance closes with, written by conclude or withdraw. |

## Invariants and what keeps them

| Invariant | Kept by |
|---|---|
| One commission per send, one delivery per deliver, one instance return per closing act. | `uq_commission_act`, `uq_delivery_act`, `uq_instance_return_act` |
| None of them changes. | no UPDATE grant |
| A criterion identifier is unique in its commission. | `uq_commission_criterion_identifier` |
| A delivery reports a criterion at most once. | `uq_delivery_criterion_criterion` |
| The status of a criterion is one of met, not met, not applicable. | `ck_delivery_criterion_status` |
| The verdict is one of green, red, question. | `ck_delivery_verdict`, `ck_instance_return_verdict` |
| The outcome is one of the closed set of the activity or the version. | the foreign key to `outcome_label`; which owner the label has is checked by the service |
| A criterion reported belongs to the commission of the delivered task. | the service; the foreign key binds only tenant and scope |
| The concerned and the bound task belong to the same instance. | the service for the commission; the store binds it only on `task.concerns_task_id` (group 2) |

## Questions this group answers

- What was this task commissioned with, under which criteria, and what did it return?
- Which criteria were reported met, not met or not applicable, with which evidence?
- What did the last return of a reworked task say, and what did the earlier ones say?
- How did an instance close, with which verdict and outcome?

## Why it is cut this way

- **Commission and delivery as act payloads, not as columns of the task.** A task can be sent again
  after a recall and delivered again after a rework; each carries its own commission or delivery. Held
  on the act, every version stays readable; the current one is projected onto the task (group 2).
- **Criteria as rows with identifiers.** The criteria contract of DEC-0055/C9 refers to a criterion by
  its identifier across commission, delivery and the worklist item; a row with an identifier is what a
  foreign key and a report can name. The text is content so that it is stored and never evaluated.
- **The instance return in the shape of a task return.** A triggering instance reads the return of
  the instance it started the same way it reads the return of a task.

## Rejected alternatives

Reconstructed for this chapter from the corpus and the store; where no node records the choice, the
assessment says so.

- **Criteria as one text list on the commission.** Rejected: a delivery could not report on a
  criterion by identifier, and a worklist item could not carry the same identifiers.
- **One return table for task and instance with an owner column.** Rejected for the same reason the
  outcome label kept one table: here the anchors differ in kind (deliver versus conclude or withdraw)
  and the reads differ; whether one table would be the better cut is left to the assessment.

## What the next wave adds here

Decision bases of routing acts and the derived fields of a return (TAR-0004 2.5, 2.6); they attach to
the delivery as new payload rows.

## Open in the assessment

- Three bindings are kept by the service, not the store: the label owner, the criterion's commission,
  the concerned and bound task's instance. Each is a candidate for a composite key (L4).
- `delivery` and `instance_return` have the same columns; assessed under L2 (no redundancy).
