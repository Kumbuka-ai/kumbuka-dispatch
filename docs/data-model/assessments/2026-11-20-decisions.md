# Decisions after the assessment of 2026-11-20: what V4 holds

Decided by the operator on 2026-11-20 (dispatch://kumbuka/sprint/215.0) on the findings of
`2026-11-20-assessment.md`. This page is the list V4 of the store line is written from. The binding
statements stand in REA-0010 and TAR-0004; where this page and they differ, they hold.

The store is not deployed, so V4 changes tables of V1 to V3 freely. After V4 the line is the one the
build of this wave and of the next runs on.

## What V4 does

| # | Change | Tables | From |
|---|---|---|---|
| 1 | `seed` on a definition version | `definition_version` (column) | REA-0010 S4 |
| 2 | The reference kind `item` | `external_reference` (check on the kind and on the owner) | TAR-0004 2.3 |
| 3 | The addressed role: frozen on the commission, free on the draft, a projection on the task | `commission`, `draft`, `task` (one nullable text column each) | REA-0010 D16 |
| 4 | A task may stand under `.0`: `ck_task_sub` admits 0; `ck_instance_next_sub` stays at 1 | `task` (check) | finding 1 |
| 5 | The title of an instance, set by `start` | `instance` (nullable text column) | operator, 2026-11-20 |
| 6 | The input text of an instance: the content kind `instance_input`, written with the start act | `content` (check on the kind) | operator, 2026-11-20 |
| 7 | The projection of the values in force: one row per instance and field, written with `start` and `accept`. The row names the written value and the accept that put it in force, so that a reader sees when a value was written and when it was accepted. Deliveries of one field are ordered by their acceptance (DEC-0056, Consequences, on C10): every accept of a delivery that wrote the field replaces the row | new table `instance_field_value` with indexes for number, date and choice | finding 2; operator, 2026-11-20 |
| 8 | An index for the listing of tasks by state and addressed role | `task` (index) | finding 3, REA-0010 D16 |
| 9 | The version of an instance belongs to its selector | `definition_version` (unique key over selector and id), `instance` (foreign key) | finding 4 |
| 10 | The act, the concerned task and the bound task of a commission lie in one instance | `commission` (column `instance_id`, three foreign keys) | finding 5 |
| 11 | The reason of a hold, the time of a deferral and the free-text mark of a question become columns of the act, each with a check on the kind; the tables `hold`, `deferral` and `question` go; `question_option` and `answer` reference the ask act, and the option an answer chooses stays bound to its question in the store: a unique key on `question_option` over (`ask_act_id`, `id`) in the place of `uq_question_option_question_id`, and the key of `answer` over (`ask_act_id`, `option_id`) in the place of `fk_answer_question_option` | `act` (three nullable columns, three checks), `question_option`, `answer` (column renamed to `ask_act_id`, their keys re-pointed), three tables dropped | finding 7; foreign review of 2026-11-20 |
| 12 | `fk_idempotency_key_task` in the form without a column list | `idempotency_key` (foreign key) | finding 11 |

The store then holds 33 tables: 35, less three, plus one.

## What stays, with its reason

| Finding | Decision | Reason |
|---|---|---|
| 6: the claim act of a holding acts on the holding's task | kept by the service | the key would cost a sixth index on `act`, paid by every act; a test of the holder protocol checks it (worklist://kumbuka/item/1286) |
| 7: the kind of act a payload row may hang on, for `commission`, `delivery`, `instance_return`, `answer`, `free_fields` | kept by the service | binding by key costs a sixth index on `act`; the five tables stay as they are |
| 8: the end of a reference in an updatable column | accepted | an insert-only form would be one more table that hangs 0..1 on a row; the service sets the column only while it is empty, and a test of S5 checks it; the model no longer calls it a projection |
| 9: `selector` stands for the process definition; a tag is held in two forms | unchanged | recorded in the model |
| 10: titles and labels are system fields | unchanged, with a rule | they stand in listings and cannot be content; they are text of the customer and never appear in a log line, a metric or a view of the operator (a realization decision of REA-0010) |
| The counter of a selector | unchanged | measured: 350 to 540 starts per second on one selector |

## What the service gains with it (S5)

- `start` takes a title and an optional input text; both are fixed with the start. The input text is
  read through the read of the instance under the permission to read content.
- `start` and `accept` write the projection of the values in force in their transaction; a test
  rebuilds the projection from the trace and compares.
- The filter and the sort of `query` by a control field read the projection.
- Every join carries tenant and scope (finding 14).

## Open for the next wave

- Which activities receive the input text of an instance: a declaration at the activity, beside its
  input contract. Until then every reader permitted to read content reads it.
- Addenda to the input text of an instance.
- The mark `searchable` on a control field, if the projection is to hold fewer fields than all.
