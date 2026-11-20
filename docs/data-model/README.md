# The data model of the dispatch service

This area explains the store of the dispatch service: what each table is for, which rule it keeps,
why it is cut the way it is, and how the model is assessed. A reader who wants to understand the
service starts here; a reader who wants to change the store finds here what a change has to keep.

## What binds and what explains

The binding statements live in the architecture corpus (`Kumbuka-ai/platform-specs`), never here:

| Node | What it binds |
|---|---|
| TAR-0004 | the target of the service: instances, tasks, acts, the gate, reads, references |
| REA-0010 | wave 2: the store line, the copy of the bracket store, the cut-over |
| DEC-0054 | four eyes and every other separation are process rules, not role properties |
| DEC-0055 | a process definition belongs to the dispatch service alone; the criteria contract with the worklist |
| DEC-0056 | the gate evaluates only what a definition declares; the kinds of data |
| DEC-0057 | the one cut-over to the new store |
| ADR-0042 | tenant and scope on every row, in a unique key of every table and in every foreign key |

This area explains and measures. Where it states a rule, it names the node the rule comes from. Where
it finds that the store and a node disagree, that is a finding, reported and resolved in the node or in
the store, never by rewording this area.

## The three levels

The model is described on three levels, and the maturity of the model is how far the three exist and
agree (see `method/maturity.md`):

- **Conceptual** -- the concepts of the domain and their definitions, each traced to the clause it
  serves: what an instance is, what an act is, why a reference has a kind.
- **Logical** -- the entities, attributes, keys and invariants, each attribute assigned to one kind of
  data under DEC-0056/C1.
- **Physical** -- the tables, columns, constraints, indexes and row-level security of the store line.

The physical level is the SQL of the store line (`backend/src/main/resources/db/store/`). The
conceptual and logical levels, and the check that the three agree, are built up in `model/`.

## Layout

| Path | Content |
|---|---|
| `method/evaluation-catalogue.md` | the criteria the model is assessed against, the scale, the knock-out rule, the weights |
| `method/maturity.md` | the maturity levels 0 to 3 and what each requires |
| `method/measurement.md` | how the physical criteria are measured: dataset, volume, queries, recording |
| `method/format.md` | which format carries which level, and why |
| `model/` | the model on the conceptual and logical level, and the physical level derived from the SQL |
| `groups/` | one chapter per group of tables: purpose, invariants, questions answered, cut, rejected alternative |
| `diagrams/` | one diagram per group and an overview |
| `assessments/` | dated results of an assessment, each grade with its evidence |
| `next-wave.md` | what the wave that builds the process rules adds to the store, whether it touches what is built now, and the open topics |

## Groups

| Group | Chapter | Model | Tables |
|---|---|---|---|
| 1 definition | `groups/01-definition.md` | `model/definition.linkml.yaml` | selector, definition_version, definition_act, definition_tag, activity, outcome_label |
| 2 instance and task | `groups/02-instance-and-task.md` | `model/run.linkml.yaml` | instance, task |
| 3 trace | `groups/03-trace.md` | `model/trace.linkml.yaml` | act, idempotency_key |
| 4 commission and return | `groups/04-commission-and-return.md` | `model/work.linkml.yaml` | commission, commission_criterion, delivery, delivery_criterion, instance_return |
| 5 holding | `groups/05-holding.md` | `model/holding.linkml.yaml` | holding, lease, receipt, hold, deferral |
| 6 dialog | `groups/06-dialog.md` | `model/dialog.linkml.yaml` | question, question_option, answer |
| 7 references | `groups/07-references.md` | `model/reference.linkml.yaml` | external_reference |
| 8 control fields | `groups/08-control-fields.md` | `model/control.linkml.yaml` | enumeration, enumeration_value, control_field, contract_field, field_value |
| 9 free fields and content | `groups/09-free-fields-and-content.md` | `model/payload.linkml.yaml` | free_fields, content |
| 10 draft workspace | `groups/10-draft-workspace.md` | `model/draft.linkml.yaml` | draft, draft_criterion, draft_free_fields, draft_text |

The diagram of each group is `diagrams/<chapter name>.puml`. Diagrams 02 to 10 are generated from the
model; a change to the model regenerates them, never an edit by hand.
| `../../scripts/data-model/` | catalogue checks, reconciliation, the volume dataset, the hot paths, the DBML view, the group diagrams |

## State

As of 2026-11-20 the store line holds V1 to V3 on the integration branch `wave-2` (35 tables). The
store is not deployed; it is changed freely until the cut-over (REA-0010 step 7), which waits until the
wave that builds the process rules is built on the same branch.

The first assessment against the catalogue is `assessments/2026-11-20-assessment.md`; what the
operator decided on it, and therefore what V4 holds, is `assessments/2026-11-20-decisions.md`. The
model and the diagrams carry V4; the chapters describe V1 to V3 and close with what V4 changes. Until
V4 is built, `reconcile.py` reports the difference: 18 reports, each a row of the decisions page. The
target model of the next wave is still to be written.
