<!--
Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
SPDX-License-Identifier: AGPL-3.0-only
This file is part of Kumbuka and is licensed under the GNU Affero
General Public License v3.0 only. See the LICENSE file in the
repository root for the full licence text.
-->
# Importing the steering corpus

This directory carries a one-off import of the steering corpus of the
predecessor service into `dispatch.selector`, `dispatch.number_circle` and
`dispatch.exchange`.

    001-import-steering-corpus.sql   the import, 278 rows, generated
    002-backfill-steering-corpus.sql the backfill of sprint 173 and 174,
                                     4 exchanges, generated -- see the section
                                     further down
    check-kd003.sql                  a read-only check before deploying V17,
                                     see the section further down
    generate-import.py               the generator that writes both
    cutover-rename.sql               the cut-over to the store line db/store:
    cutover-rename-reverse.sql       rename, its way back, and the copy of the
    cutover-copy.sql                 stock -- see the last section

The SQL file is a projection of the source corpus. Do not hand-edit it: an
edit makes the file and the corpus disagree without saying so. Change the
generator and regenerate.

**The operator runs this script.** Nothing in this repository runs it, no
build step invokes it, and the file as committed ends in `ROLLBACK`.

## The way back

Write the way back before the way in, and read it before running anything.

The import only inserts. It creates nothing, alters nothing, drops nothing,
and it touches neither row-level security nor any trigger. The way back is
therefore a delete over the address ranges it wrote, plus a reset of the two
number circles.

```sql
SET app.tenant_id = 'a7b072e4-89dd-473e-acc0-91a98a8bae7a';

BEGIN;

DELETE FROM dispatch.exchange
 WHERE tenant_id = 'a7b072e4-89dd-473e-acc0-91a98a8bae7a'::uuid
   AND scope_id  = '0845a29c-9b55-4405-a445-7849416731b8'::uuid
   AND ((selector = 'sprint'    AND number <= 172)
     OR (selector = 'satellite' AND number <= 14));

UPDATE dispatch.number_circle SET next_number = 1
 WHERE tenant_id = 'a7b072e4-89dd-473e-acc0-91a98a8bae7a'::uuid
   AND scope_id  = '0845a29c-9b55-4405-a445-7849416731b8'::uuid
   AND selector IN ('sprint', 'satellite');

-- Look at the counts first. COMMIT only then.
ROLLBACK;
```

Two things about it that are easy to get wrong.

**The runtime role cannot run it.** `kumbuka_dispatch` holds `SELECT`,
`INSERT` and `UPDATE` on the three tables and no `DELETE` (V8). The way back
therefore runs as the role that owns the relations, not as the service role.
Finding this out at the moment you need the rollback is the wrong time.

**The window closes.** The delete is scoped by address range, not by a marker
on the rows, so it removes anything in those ranges — including rows the
service itself wrote there afterwards. It is a rollback for the period
between the import and the cutover, and it stops being one once the new
service starts issuing addresses.

## Preconditions

1. Flyway V1 to V9 are applied. The script neither checks the schema version
   nor changes it; it fails on a missing column rather than adapting.
2. You can reach the database as `kumbuka_dispatch` (or as the owning role).
   The import needs `SELECT`, `INSERT` and `UPDATE` and nothing else.
3. Nothing else is writing the two selectors at the same time.

## The order

    1. rehearsal      psql ... -f 001-import-steering-corpus.sql
    2. read the numbers it prints
    3. the real run   change the last line to COMMIT, run again
    4. read the numbers again

**Line one of the body is `SET app.tenant_id`, and it is load-bearing.** All
three tables carry `ENABLE` plus `FORCE ROW LEVEL SECURITY`, so the policy
binds the owner too. Measured: without the setting the script does not write
a silent zero rows — it stops at the first statement with

    ERROR: new row violates row-level security policy for table "selector"

which is the loud failure this case deserves.

**The rehearsal and the real run differ by one word.** The file ends in
`ROLLBACK`. The whole import, including the report and all five
postconditions, runs under that transaction. Change the final `ROLLBACK` to
`COMMIT` for the real run and change nothing else.

**A second run adds nothing.** Every insert is guarded by a `WHERE NOT
EXISTS` over the full address. Note that this is deliberately not built on
`ON CONFLICT`: `uq_exchange_address` spans `addendum_suffix`, which is NULL
on all but two rows, and a UNIQUE constraint without `NULLS NOT DISTINCT`
treats those NULLs as distinct — so the constraint does not deduplicate the
ordinary case and `ON CONFLICT` would never fire for it. That is a property
of the schema, reported as a finding; the script works around it and changes
nothing.

## What the script reports

Before the postconditions, so the numbers are on screen even when a
postcondition then stops the run:

    rows per selector and status
    rows per selector, how many have no handover, how many are addenda
    where each number circle stands afterwards

## The five postconditions

Each raises. The migrated stock is defined by address range — `sprint` up to
172 and `satellite` up to 14 — so rows the service creates later are outside
every check by construction.

1. Row count per selector equals the number of source exchanges per root.
2. Every row past `draft` carries a `sent_at`.
3. Each number circle stands past the highest imported number of its selector.
4. No status outside `closed` and `consumed` in the migrated stock.
5. No row carries a handover without a `ratified_at`.

## The number circles are set past the import, not to its maximum plus one

The import stops at sprint 172. The circle is nevertheless set to **175**,
and the satellite circle to **15**. Sprints 173 and 174 are issued addresses
in the predecessor service and are excluded from this import by scope, not
because they do not exist. A circle at 173 would hand out an address that
already stands.

## What was measured

Against a throwaway PostgreSQL 16 with V1 to V9 applied, on 2026-09-06.
Never against production.

    rehearsal                278 rows, all five postconditions hold
    real run                 254 sprint, 24 satellite
    second real run          still 278 rows
    fidelity                 all 278 rows byte-identical in title, body and
                             handover_body against the source files (SHA-256)
    red probe, tenant        SET removed -> stops at the first statement
    red probe, circles       the two UPDATEs removed -> postcondition 3 raises

## What this import does not carry

Findings (`findings/F-*.md`), sprint 173 and 174, and one legacy record —
`sprint-60-chore-68-ee-server-migration.md`, which collides on address 60
with a second record and was excluded by operator decision. All of them stay
in the predecessor service and in git.

---

# The backfill of sprint 173 and 174

Two brackets the first import left out stand in `dispatch.exchange` only since
this file's companion ran:

    002-backfill-steering-corpus.sql   the backfill, 4 exchanges, generated

It is generated by the same `generate-import.py`, under a selection:

    KB_WORKSPACE=/path/to/platform-dev KB_ONLY='sprint:173,174' \
        python3 generate-import.py

Without `KB_ONLY` the generator writes 001 exactly as it always did. That is
not an assertion: run it both ways into two throwaway paths and compare the
digests. Measured 2026-09-28, `f31456d3…` before and after the selection
existed.

## Why they were missing, and why not over the verb surface

Not because they did not exist. The first import excluded them by scope and set
the sprint counter to 175 precisely so neither address would be handed out, so
both have stood unissued since. That is also why the verb surface cannot
supply them: `create` allocates the number transactionally and accepts none,
the counter is long past 175, and there is no delete verb to take a wrong
allocation back. The script path is the only one that reaches these two
addresses.

## Four exchanges out of eight files

Eight source files, four rows. A dispatch and its return are two files and one
exchange — the return is written into the return role of the same address, not
into a second object. Counting the files as rows is the error that stranded a
return on `satellite/17.3` once; the backfill does not repeat it.

    sprint/173.0   closed     concept   return: yes
    sprint/173.1   consumed   code      return: yes
    sprint/174.0   consumed   concept   return: yes
    sprint/174.1   consumed   code      return: yes

## Three deliberate differences from 001

**The tenant is bound with `SET LOCAL`, inside the transaction.** 001 used a
session-level `SET`, which outlives the script in the session that ran it.
`SET LOCAL` reverts at `COMMIT` and at `ROLLBACK`.

**An occupied address stops the run, loudly.** 001 guarded each insert with
`WHERE NOT EXISTS` and skipped silently — right for an import meant to be
re-run to completion, wrong here. `uq_exchange_address` cannot be the backstop:
it spans `addendum_suffix`, NULL on all four rows, and without `NULLS NOT
DISTINCT` those NULLs compare as distinct, so the constraint *admits* a
duplicate address. Measured 2026-09-28 with the guard removed: the four inserts
went through and produced eight rows on four addresses. That is the data loss
of item 459, and it is what the guard is for.

**No counter is read or written.** The sprint counter stands at 175, past both
brackets. 001's way back set the counters to 1; with sprints 175 to 195
standing today that would point the next `create` at an address in use, so it
is expressly not a template here. The counter itself moved in V11 from
`dispatch.number_circle` (dropped) onto `dispatch.selector.next_number`; the
backfill reads `selector.id` for V12's foreign key and never `next_number`.

## It targets today's schema, not 001's

001 was generated against V9. Since then V10 renamed `body`, `handover_body`
and `handover_metadata` to `dispatch_body`, `return_body` and
`return_metadata`; V11 dropped `dispatch.number_circle`; V12 replaced
`exchange.selector` (TEXT) with `exchange.selector_id` (BIGINT) behind a
foreign key. 001 is left exactly as it was run on 2026-09-06 and is not
re-runnable against the current schema.

## The three guards

Each raises, and each was observed raising.

1. **A tenant is bound, and it is the right one.** Without it the run would
   still fail — the first insert violates the RLS policy — but it would fail
   about the schema rather than about the binding, and a run bound to a
   *different* tenant is the case only this guard names.
2. **Every selector already stands.** The backfill declares none: a missing
   selector means this is not the stock the file was generated against.
3. **Every target address is free.** See above.

## The order

    1. rehearsal      the file as committed ends in ROLLBACK
    2. read the numbers it prints
    3. the real run   change the last line to COMMIT, run again
    4. read them again, and check the counter did not move

## The way back

Write it before the way in. The backfill only inserts, so the way back is a
delete — and it differs from 001's in the two ways that matter.

**By equality, never by range.** 001's rollback deleted over address ranges
and therefore also removed rows the service wrote there later. These four
addresses are named individually, so the window does not close.

**It touches no counter.** 001's reset of the circles is not carried over.

```sql
SET app.tenant_id = 'a7b072e4-89dd-473e-acc0-91a98a8bae7a';

BEGIN;

DELETE FROM dispatch.exchange e
 USING dispatch.selector s
 WHERE s.id = e.selector_id
   AND e.tenant_id = 'a7b072e4-89dd-473e-acc0-91a98a8bae7a'::uuid
   AND e.scope_id  = '0845a29c-9b55-4405-a445-7849416731b8'::uuid
   AND e.addendum_suffix IS NULL
   AND (s.name, e.number, e.sub) IN
       (('sprint',173,0), ('sprint',173,1), ('sprint',174,0), ('sprint',174,1));

-- Look at the count first. It must be 4. COMMIT only then.
ROLLBACK;
```

**The runtime role cannot run it.** `kumbuka_dispatch` holds `SELECT`, `INSERT`
and `UPDATE` and no `DELETE` (V8) — measured: `permission denied for table
exchange`. The way back runs as the role that owns the relations, which V8
leaves as the migrating role (`DISPATCH_MIGRATOR_USERNAME`). Read the name off
the database rather than assuming it:

```sql
SELECT DISTINCT tableowner FROM pg_tables WHERE schemaname = 'dispatch';
```

## What was measured

Against a throwaway PostgreSQL 16 with V1 to V14 applied, on 2026-09-28, with
a row on `sprint/175.0` standing and the counter at 196 — the shape production
has. Never against production.

    rehearsal            4 rows, all four postconditions hold, nothing left
    real run             1 row -> 5, counter 196 before and after
    second real run      psql exit 3, 0 inserts, stock unchanged, no duplicates
    unbound tenant       psql exit 3 at guard 1
    fidelity             all 4 rows identical in title, dispatch body and
                         return body against the source files (SHA-256)
    red probe, guard 3   guard removed -> 4 inserts, 8 rows on 4 addresses,
                         caught by postcondition 1 ("expected 4 … found 8")
    red probe, guard 1   guard removed -> guard 2 catches it, naming the
                         selector instead of the binding
    way back             DELETE 4, `sprint/175.0` untouched, counter still 196

## The host runs it inside the postgres container

The production host carries no `psql` client and the database runs as a
container there (`infra/scripts/release.sh`: "psql runs INSIDE the postgres
container: the host carries no psql"). Both scripts in this directory are
therefore fed in over `docker exec -i`, the same discipline `release.sh`,
`deploy.sh` and `restore.sh` already use.

---

# Before deploying V17: the addendum check

V17 copies `exchange` into `task` and `task_text` and stops with KD003 on an
addendum row that carries a text besides its own: a return, a question, a
message or a reason. The stock imported by 001 holds two such rows,
`sprint/10.0a` and `sprint/155.2a`, each with a return; a release carrying V17
stops on them and rolls back.

V16_1 runs before V17 and moves each such text into an addendum of its own
beside the one it answers, titled `Antwort auf <selector>/<number>.<sub><letter>`,
and empties the four columns. `check-kd003.sql` lists the rows that are left
for it to move, so the number is known before the deploy.

    docker exec -i <postgres container> psql -U <superuser> -d <database> \
        < check-kd003.sql

Run it as the superuser: it reads past row-level security. The owning role is
bound by `FORCE ROW LEVEL SECURITY` too and, without a tenant set, answers 0
for a stock that is not.

## What was measured

Against a throwaway PostgreSQL 16 holding the stock of 001 and 002 at V16,
on 2026-10-17. Never against production.

    as superuser, at V16           2 rows: sprint/10.0a, sprint/155.2a
    as superuser, after V16_1..V21 0 rows
    as owner, no tenant bound      0 rows -- the wrong answer named above

---

# The cut-over to the store line db/store

Three scripts move the stock of the old store (line `db/migration`, V1 to V25)
into the new store (line `db/store`, V1 to V5) once, at the cut-over:

    cutover-rename.sql           schema dispatch -> dispatch_legacy, plus the
                                 table dispatch_legacy.cutover with one row
    cutover-rename-reverse.sql   the way back of the rename
    cutover-copy.sql             reads dispatch_legacy, writes the new dispatch

None of them is a migration. No build and no service runs them; the operator
runs each with psql, with `-v ON_ERROR_STOP=1`. Each runs in one transaction of
its own and writes nothing when it refuses. Every table is named with its
schema; none relies on `search_path`. The probes are `CutoverRenameIT`,
`CutoverCopyIT` and `CutoverRefusalIT`.

## The order of the cut-over

    1. stop the service
    2. take the backup
    3. rename         psql -v ON_ERROR_STOP=1 -U <owner> -d <db> -f cutover-rename.sql
    4. start the new image: it builds the line db/store in an empty schema dispatch
    5. copy           psql -v ON_ERROR_STOP=1 -U <role reading every tenant> -d <db> \
                          -c "SET kumbuka.cutover_actor = '<subject>'" -f cutover-copy.sql
    6. reconcile
    7. open           UPDATE dispatch_legacy.cutover SET opened_at = now();

The rename runs as the role that owns the schema `dispatch` (the migrating
role). The copy runs as a role that reads every tenant: the superuser, or a role
with `BYPASSRLS`. Both stores are under `FORCE ROW LEVEL SECURITY`, and any other
role would copy the bound tenant and nothing of the others; the copy refuses it.

The history of the old line, `dispatch.flyway_schema_history`, moves with the
rename into `dispatch_legacy`; the new line writes its own into the new
`dispatch`. The service role keeps `search_path = dispatch` and so lands in the
new store after the rename.

## The way back

Until the opening the way back loses nothing:

    1. drop the new schema    DROP SCHEMA dispatch CASCADE;
    2. reverse the rename     psql -v ON_ERROR_STOP=1 -U <owner> -d <db> \
                                  -f cutover-rename-reverse.sql
    3. start the old image

`cutover-rename-reverse.sql` drops `dispatch_legacy.cutover` and renames
`dispatch_legacy` back to `dispatch`. The old store then stands with its stock,
privileges, policies, functions and triggers as before the rename: a rename of
a schema carries all of them along, and `CutoverRenameIT` compares the catalog
and every row before the rename and after the way back.

**Nothing writes into `dispatch_legacy` after the rename except `cutover`.** Two
trigger functions of V17 (`refuse_text_writes_after_send`,
`refuse_task_delete_after_send`) name `dispatch.task` in their bodies; after the
rename they read the new table `task`. An update or delete of a text in
`dispatch_legacy` would therefore be judged against the wrong table. The copy
only reads the old store, and `CutoverCopyIT` compares every table of it before
and after.

## The setting kumbuka.cutover_actor

The subject the copy names as the actor of every import act, set in the session
before the run: `SET kumbuka.cutover_actor = '<subject>'`. With psql, `-c` and
`-f` run in one session, as in step 5. The subject is opaque, like every actor
of the new store: an identifier, never a name or an address.

## The refusals

The rename refuses when `dispatch_legacy` exists already, when `dispatch` is not
the old store (no history, no `task`, or the store line in it), when the history
records a failed migration, or when the last version is not 25.

The way back refuses when there is no `dispatch_legacy`, when a schema
`dispatch` still stands, when `cutover` is missing or does not hold exactly one
row, or when the cut-over was opened.

The copy refuses, writing nothing, when

- `kumbuka.cutover_actor` is not set or empty;
- `dispatch_legacy.cutover.opened_at` is set, or the table does not hold
  exactly one row: after the opening the copy would empty what the new store
  took since;
- `dispatch_legacy` or the store line V5 is not there, or the role does not read
  every tenant;
- an act the copy derives has no recorded actor;
- `return_metadata` stands on a task without a return text;
- a bracket has no root task `<number>.0`;
- an `apparatus` would be refused by `ck_task_addressed_role`;
- an addendum to a text other than a question or an answer has no base text of
  its type;
- an answer of a sent task has no ask act of its task that is not after it: no
  question text of the task not younger than the answer;
- a question text of a sent task has more than 2000 characters, which
  `ck_elicitation_content_question_body` would refuse;
- an addendum to a question or an answer has no base text to supplement: no
  question or answer text of its type on its task.

A refusal names its reason and, where there is one, the address of each task,
written as `<scope_id>/<selector>/<number>.<sub>` (an addendum with its
suffix). It names no title, no text and no value.

## What the copy does

It empties every table of the new store first (`TRUNCATE … RESTART IDENTITY`),
then copies. A second run gives the same stock; only the time of the import acts
differs, which is the time of the run. The principle: no text is rewritten, and
no event the old store did not record is made up. Every act of the copy names
the recorded actor as `actor_subject` and `actor_owner_subject` and leaves
`permission`, `path` and `authentication_level` empty.

- **Selector.** One per old selector, same name; `next_number` behind the larger
  of the old counter and the highest copied number, so a number once issued is
  never issued again. One `definition_version` 1 with `seed = true`,
  `state = active`, `verdict_required = false`, `outcome_required = false`; one
  activity `default` (`input_kind = text`, `may_ask = true`, the rest false or
  empty); no outcome label, control field, rule or `definition_act`.
- **Instance.** One per bracket (root task, sub 0), same number, bound to version
  1, titled as its root task, without an input text; `next_sub` behind the
  highest sub. A closed root task closes the instance: `concluded` where it was
  accepted, otherwise `withdrawn`. The closing act (`conclude` or `withdraw`, on
  the instance) carries time and actor of the root task's change of state and the
  `instance_return`, without verdict or outcome; a `conclude` refers
  (`refers_to_act_id`) to the deliver act of the valid, youngest return of the
  root task.
- **Trace.** Each instance begins with one act `import` (actor from the setting,
  time of the run). The derived acts follow by time, then sub-number, then in
  this order: send, claim of a holder, deliver, ask, answer, annotate, the act of
  the present state, the closing act of the instance. They are older than the
  import act before them; the sequence orders, not the time.
- **Task.** Same address, state, `hold_reason`, `resolution` (from `outcome`),
  `not_before`; no activity, verdict, outcome or field value; `addressed_role`
  on the task, the commission and the draft is the `apparatus`. `parked` is true
  exactly for a task on hold `external` without a holder and with
  `lapse_count >= 3`. `self_accepted`, `first_taken_at` and `lapse_count` are not
  carried.
- **Derived acts**, only from what the old store records:
  - `send` for every task but a draft, at the time and by the actor of its
    dispatch text; the commission (title, the activity `default`,
    `addressed_role`) and the text as content of kind `commission`;
  - `deliver` for every return text, the text as content of kind `return`;
  - `ask` for every question text, with `human_required = false`: the old store
    knows no such mark, and a copied question does not raise it. The ask act
    carries a bundle of exactly one question in `elicitation_question`:
    identifier `q1`, ordinal 1, `required = true`; the text unchanged as a row
    of kind `question` in `elicitation_content` (see the next section for the
    selection, the options and free text);
  - `answer` for every answer text, with `answer_outcome = answered`, and one
    row in `elicitation_answer` for `q1` of the youngest ask act of its task
    that is not after it; the text unchanged as a row of kind `answer` in
    `elicitation_content` on that answer. No `elicitation_answer_option` is
    written: the old store records no option chosen, and reading one out of
    the text would be made up;
  - `annotate` for every text with a suffix, as an addendum supplementing the
    text of its type it belongs to: the youngest of that type not younger than
    the addendum, or else the oldest. An addendum to a question or an answer is
    a row of kind `addendum` in `elicitation_content` supplementing the row of
    that text; every other addendum is content of kind `addendum`;
  - the act of the present state, at `state_changed_at` by `state_changed_by`:
    `claim` for active without a holder, `hold` for on hold `dependency` or
    `external`, `defer` for open with `not_before`, and `accept`, `reject`,
    `fail` or `withdraw` for closed; none where an act above makes the state
    already (delivered after deliver, on hold for a question after ask);
  - `claim` of a holder for every sent task with a holder, active, on hold or
    delivered: by the holder, at `first_taken_at` or else at the send, ordered
    before the deliver, ask or hold act that makes the present state; a
    `holding`, unended, and a `receipt` with the existing hash; a `lease` with
    the existing end for an active task only;
  - a remark becomes content of kind `remark` on the last derived act of its
    task whose time is not after the remark's (on the first act where none is,
    counted in the report).
- **Metadata**, split by key, never by what a value says. On a sent root task,
  `items` and `intake-items` become references of kind `item`, and `origin`,
  `predecessor`, `follow-up`, `merged`, `handover-pr` and `handover-state`
  references of kind `reference` of the instance, each value of a list one
  reference, set by the import act; a value on both sides is one reference.
  Every other key, unknown ones included, and on every other task the listed
  ones too, stays with its value in the free fields of the send
  (`dispatch_metadata`) or of the valid deliver (`return_metadata`).
- **Curation.** `curated_in_id` becomes a reference of kind `curation` of the
  task, set by the import act, with the address `<scope_id>/<selector>/<number>.<sub>`
  of the task it was curated into.
- **Tags.** Each tag of a sent task becomes a reference of kind `tag` of the
  task, set by the import act. Tags of a never-sent task are left behind and
  counted.
- **Drafts.** A never-sent task is a task in draft with a draft (title,
  `addressed_role`), its dispatch text as `draft_text` of kind `commission`, its
  `dispatch_metadata` unchanged as `draft_free_fields`, and no act.
- **Idempotency keys** are counted, not copied.
- Every text keeps its body; its `media_type` is `text/plain`, since the old
  store records no format. `content` holds no question or answer text and no
  addendum to one: those stand in `elicitation_content`.

## The options of a question: a value set, not recorded

The old store holds `question_options` only for the open question: every change
of state other than a hold for a question clears it (`Task.enter`). What an
answered question offered, and whether it admitted free text, is recorded
nowhere. The new store requires `selection` and `free_text_admitted` on every
question of a bundle.

So, by decision of the operator of 2026-11-21, carried into the bundle by the
order of V5: the question of the open question's ask act (the youngest question
of a task on hold for a question with `question_options`) has `selection = one`
with exactly its options, in their order, as `elicitation_option` with the
identifiers `o1`, `o2`, ... by that order, and `free_text_admitted` from its
`free_text`. **Every other question has `selection = none` and
`free_text_admitted = true`. That value is set by the copy, not recorded**, and
the report counts these bundles apart.

An option label of more than 200 characters, or two equal labels, is refused by
the checks of `elicitation_option` with the error of the store and without an
address; the old store bounds neither.

## What the report contains

The last statement prints `pg_temp.cutover_copy_report`: numbers, the keys of the
metadata and the names of tables, never a title, a text or a value.

    metadata          per key: how many values went where -- a reference of
                      kind item or reference, the free fields of the send or
                      the deliver, the free fields of a draft
    tags              left behind on never-sent tasks
    idempotency keys  not copied, from task_idempotency_key and idempotency_key
    elicitation       bundles with selection none, free text admitted set by
                      the copy; bundles with selection one, from
                      question_options; answers; addenda to a question or an
                      answer text
    holders           claim acts of a holder
    instances         concluded without a return to refer to
    remarks           placed on the first act, with no act before them
    texts             of never-sent tasks besides the commission, not carried
    rows              per table of the new store, its row count
