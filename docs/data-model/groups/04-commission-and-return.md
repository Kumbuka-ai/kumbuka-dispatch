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
- **Criteria as rows with identifiers.** A delivery reports on a criterion of its commission by the
  criterion's identifier; a row with an identifier is what a foreign key and a report can name. The text
  is content so that it is stored and never evaluated. These are the criteria of a task and the dispatch
  service's own. The criteria an instance takes over from a worklist item are another thing and are
  kept apart from them (DEC-0055/C9); see "What the next wave adds here".
- **The addressed role on the commission (V4).** A commission may name the role it is addressed to: an
  opaque role name the service stores and never resolves. It is the field an executor selects its work
  by, and it replaces the field `apparatus` of the bracket store, whose name stays on the verb surface
  for this wave (REA-0010). It is frozen with the commission like the title; `task.addressed_role` is
  its projection, and `draft.addressed_role` the value a draft will carry. A role, never a kind of
  actor. The process rule of an activity says which roles may execute it at all; the addressed role says
  for which of them this one commission is meant, and serves the default process, which has one activity
  and no rule. That a take-up by a caller without the role is refused is built with the rules.
- **The instance return in the shape of a task return.** A triggering instance reads the return of
  the instance it started the same way it reads the return of a task.

## Rejected alternatives

Reconstructed for this chapter from the corpus and the store; where no node records the choice, the
assessment says so.

- **Criteria as one text list on the commission.** Rejected: a delivery could not report on a
  criterion by identifier.
- **The addressed role as a free field, a tag, a payload table of its own, or an activity per role.**
  A free field would put the executor's selection on a search in JSON; a tag exists only from the first
  send on; a table of its own would be one more row per send for one value; an activity per role was
  rejected for the seed definitions (dispatch://kumbuka/sprint/207.0a, E4.4).
- **One return table for task and instance with an owner column.** Rejected for the same reason the
  outcome label kept one table: here the anchors differ in kind (deliver versus conclude or withdraw)
  and the reads differ; whether one table would be the better cut is left to the assessment.

## What the next wave adds here

- **The criteria of a worklist item, taken over at the start (DEC-0055/C9).** An instance takes over
  the identifiers and the text of the item criteria it is to fulfil, as a copy that freezes with the
  start; its return states a status and an evidence reference for each identifier, and a conclude that
  lacks one is refused. Neither the copy nor the statuses of an instance return have a table in V1 to V3.
  TAR-0004 2.3 still states the earlier rule and is to be brought in line (worklist://kumbuka/item/1276).
- Decision bases of routing acts (TAR-0004 2.5); they attach to the act as a payload of their own.
- Whether a sent task can be addressed to another role by an act of its own, and until when, is open
  (`../next-wave.md`). In this wave the addressed role changes only in a draft.

## Assessed, and what V4 changes

Assessment: `../assessments/2026-11-20-assessment.md`; decisions: `../assessments/2026-11-20-decisions.md`.
The tables above describe V1 to V3; the model and the diagram carry V4.

- **The commission carries its instance.** With `instance_id`, foreign keys bind the act, the
  concerned task and the bound task of a commission to one instance; before, the service did.
- **The addressed role** arrives on the commission, frozen with it.
- **Kept by the service:** that an outcome belongs to the activity of the task or to the version of
  the instance, and that a criterion reported belongs to the commission of the delivered task. A key
  for the second would need the commission on the delivery and on each of its criteria.
- `delivery` and `instance_return` have the same columns today and stay two tables: the instance
  return gains the status of the item criteria with the next wave (DEC-0055/C9), and the outcome of
  one belongs to an activity, of the other to a version.
- The title of a commission is text of the customer: a system field that never appears in a log
  line, a metric or a view of the operator.
