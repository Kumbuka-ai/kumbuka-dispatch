# Importing the steering corpus

This directory carries a one-off import of the steering corpus of the
predecessor service into `dispatch.selector`, `dispatch.number_circle` and
`dispatch.exchange`.

    001-import-steering-corpus.sql   the import, 278 rows, generated
    002-backfill-steering-corpus.sql the backfill of sprint 173 and 174,
                                     4 exchanges, generated -- see the section
                                     at the foot of this file
    generate-import.py               the generator that writes both

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
