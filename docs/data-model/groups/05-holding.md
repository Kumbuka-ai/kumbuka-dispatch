# Group 5: holding

Who holds a task and until when, and the payloads of the acts that pause or defer it. Model:
`../model/holding.linkml.yaml`. Physical level: store line V1 (`holding`, `lease`, `receipt`, `hold`,
`deferral`). Diagram: `../diagrams/05-holding.puml`.

## Purpose

A claim gives one delegated identity the right to work on a task for a limited time: a holding with a
lease. The holder writes with a receipt; a renew extends the lease; a release, a defer, a delivery or a
closing act ends the holding. A lease that passes its end is a lapse: nothing is written, and the
holding is read as ended at its lease end (ADR-0002, TAR-0004 2.5, 2.8). A hold pauses a task for a
dependency or an external cause; a defer gives it back until a time.

## Concepts

| Concept | Table | In one sentence |
|---|---|---|
| Holding | `holding` | A hold of a task by one identity, from the claim that opened it to the act that ended it. |
| Lease | `lease` | The time an act grants a holding, until it expires: a claim opens it; a renew, a resume, an answer or a rework starts a new one (TAR-0004 2.8). |
| Receipt | `receipt` | The hash of a receipt handed to the holder; every write of the holder carries the receipt. |
| Hold | `hold` | The reason a hold act puts a task on hold for: dependency or external. |
| Deferral | `deferral` | The time before which a defer act gives a task back. |

## Invariants and what keeps them

| Invariant | Kept by |
|---|---|
| One holding per claim. | `uq_holding_claim_act` |
| Of a holding, only its ending act is ever set. | UPDATE grant on `ended_by_act_id` alone |
| A lapsed holding keeps no ending act. | by construction; hence no unique index on open holdings |
| One lease per granting act (claim, renew, resume, answer, rework); leases and receipts never change. | `uq_lease_act`; no UPDATE grant |
| A receipt is never stored, only its hash. | the column `receipt_hash`; the service hashes before insert |
| A hold names dependency or external; a question is asked with ask (group 6). | `ck_hold_reason` |
| At most one current holding per task. | the service, from the leases and ending acts; a partial unique index cannot see a lapse |
| The claim act acts on the task of its holding. | the service; the foreign key binds only tenant and scope |

## Questions this group answers

- Who holds this task, under which lease, until when?
- How many times has this task lapsed, and is it parked?
- Which receipt is valid for a write right now?
- Why is a task on hold, and until when is a deferred task held back?

## Why it is cut this way

- **The holding as its own object, not as columns of the task.** Lapses are counted over holdings
  (TAR-0004 2.5); a task with its holder in columns would lose every earlier holding.
- **Lease and receipt apart from the holding.** A renew extends the lease without a new holding, and a
  holder may be handed a new receipt; both are histories, not values.
- **No unique index on open holdings.** A lapse writes nothing, so an unended holding may already be
  over; an index on `ended_by_act_id IS NULL` would refuse the next claim after a lapse.

## Rejected alternatives

Reconstructed for this chapter from the corpus and the store; where no node records the choice, the
assessment says so.

- **A scheduler that ends lapsed holdings.** Rejected by ADR-0002 (C2 and its rejected alternatives).
- **Storing the receipt itself.** Rejected: a read of the store would hand out a write right.

## What the next wave adds here

Nothing planned; the parking threshold is a value of the tenant rule set (TAR-0004 2.2) and is read,
not stored here.

## Assessed, and what V4 changes

Assessment: `../assessments/2026-11-20-assessment.md`; decisions: `../assessments/2026-11-20-decisions.md`.
The tables above describe V1 to V3; the model and the diagram carry V4.

- **`hold` and `deferral` go.** The reason of a hold and the time of a deferral are columns of the
  act from V4 (group 3). This group then holds `holding`, `lease` and `receipt`.
- **Kept by the service:** that the claim act of a holding acts on the holding's task. A key would
  need a unique key on `act` over (`task_id`, `id`), a sixth index on the largest table. A test of
  the holder protocol checks it (worklist://kumbuka/item/1286).
- "At most one current holding per task" cannot be a key: a lapse is never written (ADR-0002).
