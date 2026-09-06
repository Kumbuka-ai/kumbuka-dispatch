# Importing the steering corpus

This directory carries a one-off import of the steering corpus of the
predecessor service into `dispatch.selector`, `dispatch.number_circle` and
`dispatch.exchange`.

    001-import-steering-corpus.sql   the import, 278 rows, generated
    generate-import.py               the generator that writes it

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
