<!--
Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
SPDX-License-Identifier: AGPL-3.0-only
This file is part of Kumbuka and is licensed under the GNU Affero
General Public License v3.0 only. See the LICENSE file in the
repository root for the full licence text.
-->

# Changelog

What changes for an operator, a caller or a consumer of the library, per
release. The release workflow puts the section of the tagged version into the
GitHub release. Releases before 0.11.0 are described on the
[releases page](https://github.com/Kumbuka-ai/kumbuka-dispatch/releases) only.

## Unreleased

### Callers

- **`claim_next` left the surface.** `dispatch_claim_next` is no tool of
  `tools/list` any more, and a call of it is an unknown tool (-32602); `POST
  …/{selector}:claim_next` is a verb not carried (`VERB_NOT_CARRIED` 405).
  Take a task up by its address with `claim`. The service no longer answers
  `DRAW_EMPTY`, and the argument message `invalidApparatusUnbounded` is gone
  with the draw. The declaration changes with it, and so does its version.
- **Every call checks one named permission.** `send`, `recall`, `create`,
  `update`, `delete`, `relate` and `unrelate` check `dispatch.task.commission`;
  `claim`, `release`, `defer`, `renew`, `ask`, `hold`, `resume`, `deliver`,
  `fail` and `reject` check `dispatch.task.execute`; `answer`
  `dispatch.task.decide`; `rework` and `accept` `dispatch.task.confirm`;
  `withdraw` `dispatch.task.withdraw`; `tag_attach` and `tag_detach`
  `dispatch.task.tag`; `read` and `query` `dispatch.instance.read`;
  `read_text` `dispatch.instance.read-text`, or
  `dispatch.instance.read-text-as-holder` for the holder; `annotate` the
  permission the supplemented text was written under. The permissions come
  from the realm roles `dispatch-console` and `dispatch-executor` as before,
  so whoever was admitted is admitted and whoever was refused is refused. One
  code changes on the wire: a holder's call (`release`, `defer`, `renew`,
  `ask`, `hold`, `resume`, `deliver`, `fail`) by an identity holding only the
  console's role is refused as `ROLE_INSUFFICIENT` 403, where it was
  `CLAIM_HELD` 409.

- **New call `recall`** (`dispatch_recall`, `POST …/{id}:recall` with
  `If-Match`). The commissioner takes an open task back to draft as long as
  no executor has ever taken it up, changes it with `update` and sends it
  again; the new text replaces the one sent, which is kept nowhere. From the
  first take-up on, a recall is refused (`TRANSITION_REFUSED`, message
  `contentFrozen`), and so is a recall of a bracket root under which any task
  stands, a draft included (`TRANSITION_REFUSED`, message `bracketOccupied`).
  `next` lists `recall` only where it succeeds.
- **An addendum only while the work runs.** `annotate` is accepted in
  `active` and `on_hold` and refused in `draft`, `open`, `delivered` and
  `closed`, for every part (`TRANSITION_REFUSED`). On an open task the
  message names `recall`, on a delivered one `rework`. Until now an addendum
  was accepted in every state after `draft`.
- **`claim_next` is retiring.** It works as before; its description now says
  so and points at `claim` with the task's address.
- The descriptions of `send`, `update`, `annotate` and `claim_next` change,
  and `recall` is new: the declaration changes with them. The router carries
  the declaration of the dispatch version it pins, so its surface takes these
  up when its pin moves to the release that carries them.
- **Tags on tasks.** New calls `tag_attach` and `tag_detach`
  (`dispatch_tag_attach`, `dispatch_tag_detach`, `POST …/{id}:tag_attach` and
  `…:tag_detach`, with `fields.tag`, the token). They attach and detach a tag
  of the task's scope, as the platform declares it in `platform.tag_access`,
  in every state of the task, a sent and a closed one included, for whoever
  holds the write right in the scope. They take no conflict token and change
  neither the task's state, text, conflict token nor change stamp. A tag the
  task carries already, or one it lacks on detach, is a success that changes
  nothing. An undeclared token is refused as `ARGUMENT_INVALID` on
  `fields.tag` (message `invalidTagUnknown`), a withdrawn one on attach as
  `ARGUMENT_INVALID` (message `invalidTagWithdrawn`); a withdrawn tag still
  detaches. The answer is the lean one with `fields.tags`.
- `read` and every row of `query` carry `fields.tags`: the tokens the task
  carries, ascending, withdrawn ones included, an empty list where it has
  none. `query` takes the filter `tag`, one token, combined with the other
  filters by AND; an undeclared token lists an empty page, a value that is no
  single token is refused (`ARGUMENT_INVALID`, message `invalidTagFilter`).
  The declaration names the fields of each answer under `answer.fields`; the
  description of `query` changes with the new filter.

### Upgrade and rollback

- The calls on tags and the filter `tag` read the platform's view
  `platform.tag_access`, which the service role needs `SELECT` on (the
  parallel commission in `kumbuka-server`). `read` and `query` ask it only for
  tasks that carry a tag, so before the platform publishes it everything but
  tagging works as before.
- Migration `V24` adds the table `dispatch.task_tag` (task, tag, who attached
  and when), its index, its tenant policy under FORCE row-level security and
  its grant. It changes no existing table, column, policy or grant. An image
  before it does not know the table; a draft it deletes takes its tags along
  through the cascade.

- Migration `V23` adds the nullable column `task.first_taken_at` without a
  default, and a trigger that refuses changing it once set. The first claim
  sets it; release, defer, a lapsed lease, rework and later claims leave it.
  It writes no row of the stock. For a task without it, "taken up" is derived
  from what the stock records: a counted lapse, a return, question, answer or
  remark, a standing deferral, or a stored state only a take-up reaches. A
  task taken up and released without a remark, with no lapse, by an image
  that does not set the column -- 0.12.0 and earlier, also after a rollback
  -- leaves no such trace and can be recalled. 0.12.0 does not know the
  column: its inserts leave it NULL and its updates leave it alone.

## 0.12.0

### Callers

- **One identity may hold both capacities.** A token carrying both
  `dispatch-console` and `dispatch-executor` is accepted; until now it was
  refused with `ROLE_INSUFFICIENT`. The identity takes whichever part a call
  asks for: it commissions, takes up, delivers and accepts. A token carrying
  neither role is refused as before.
- **Acceptance of one's own answer is stamped, not refused.** An identity
  holding both capacities may accept a task it delivered itself. Every
  acceptance stamps `self_accepted`: `true` where the identity that delivered
  accepts, `false` where another does. The stamp is in the answer of the
  acceptance, of `read` and of `query`, and never changes. Where it is unknown
  -- a task not closed as `accepted`, or one accepted before this release --
  the field is absent, as every member without a value is. There is no filter
  on it in `query`: a fifth filter would change the description of
  `dispatch_query`, which the router carries.
- **An identity holding the executing capacity alone never accepts**, its own
  answer or another's, and is refused alike on both surfaces
  (`ROLE_INSUFFICIENT`). Granting an agent that capacity alone is how
  four-eyes is configured.
- The declaration is unchanged, byte for byte: tool descriptions and message
  patterns stay as the router serves them. Two of them now say less than
  holds: the description of `dispatch_accept` names acceptance as the act of
  an identity other than the one that delivered, and the message
  `roleCapacity` still says "exactly one of them". The message
  `roleOtherThanTheDeliverer` is no longer reached. They change with the next
  release that the router takes up together with this one.

### Upgrade and rollback

- Migration `V22` adds the nullable column `task.self_accepted` without a
  default, a check that it stands only on a task closed as `accepted`, and a
  trigger that refuses changing it once stamped. It is additive and writes
  no row: every existing task holds NULL, unknown, and none is stamped
  `false`, because the earlier service did not record whose acceptance it
  was. 0.11.0 does not know the column: its inserts leave it NULL and its
  updates leave it alone, so the schema stays usable for it. Whether a 0.11.0 image starts on a database `V22` has
  migrated is Flyway's validation, and is not established by this release.

## 0.11.0

### Upgrade and rollback

- **No rollback to 0.9.0 – 0.10.0 once 0.11.0 has migrated a database.**
  Migration `V21` drops the trigger `task_stamp_updated_at` and the default
  of `task.updated_at`; the service now writes that column itself. Releases
  0.9.0 to 0.10.0 insert a task without `updated_at` and rely on that
  default, so they do not run against a schema migrated by 0.11.0. Upgrading
  from 0.9.x or 0.10.0 to 0.11.0 is a forward step only; a rollback after
  `V21` has run needs a database restored from before the migration.
- `V19` and `V20` add an index for the task listing sorted by creation
  (`V20` replaces the one `V19` added). Both are additive.
- **New migration `V16_1`, between `V16` and `V17`.** `V17` stops with
  `KD003` on an addendum that carries a return, a question, a message or a
  reason besides its own text. `V16_1` moves each such text into an addendum
  of its own beside it, titled `Antwort auf <selector>/<number>.<sub><letter>`,
  and empties those columns on the answered addendum. Where that answer
  already stands with the same text, only the columns are emptied; where it
  stands with a different text, `V16_1` stops with `KD003` and names the
  addendum. A database at `V16`
  takes it as the next migration. `migration/check-kd003.sql` lists the rows
  it will move, read only.
- **A database that already ran `V17`** (0.9.0 – 0.10.0, or a pre-release
  build) refuses to start with `Detected resolved migration not applied to
  database: 16.1`. Start 0.11.0 once with `QUARKUS_FLYWAY_OUT_OF_ORDER=true`:
  `V16_1` then runs once, against `exchange` only, and every later start
  validates without the setting. This is only for a database without stock
  to keep. A database with stock on which `V17` has already run is restored
  from the backup to schema 16 and then runs `V16_1` to `V21`.

### Callers: refusals and status codes

Refusals now come from one catalogue in which every code carries its HTTP
status, its message and its remedy. Several codes and statuses changed:

| Before | Now |
|---|---|
| missing conflict token: `ARGUMENT_MISSING` 400 | `CONFLICT_TOKEN_MISSING` 428 |
| `SELECTOR_UNKNOWN` | `SELECTOR_UNDECLARED` 422, and `SELECTOR_WITHDRAWN` 422 |
| `NOTHING_TO_TAKE` | `DRAW_EMPTY` 409 |
| `CALL_NOT_AT_THIS_ADDRESS`, unknown REST verb | `VERB_NOT_CARRIED` 405, with `Allow` and `data.accepts` |
| `RECEIPT_WRONG` 403 | `RECEIPT_WRONG` 409 |
| `NOT_THE_HOLDER` | `CLAIM_HELD`, a state refusal, or `LEASE_LAPSED`, each 409 |
| `CLAIM_DURATION_INVALID` | `ARGUMENT_INVALID` 400 |
| body not an object, address out of grammar | `PAYLOAD_MALFORMED` 400, `ADDRESS_MALFORMED` 400 |

- `ROLE_DOES_NOT_ALLOW` and `SCOPE_READ_ONLY` stay 403. A token without a
  capacity is refused as `ROLE_INSUFFICIENT`, with the same bytes whether or
  not the scope is visible to the caller.
- Every refusal past the route carries `attempted`. `PAYLOAD_MALFORMED`
  carries no `offenders`. A refusal of a listing names the argument
  (`page_size`, `after`, `sort`, `direction`, a filter by name and position),
  never the value that was sent.
- A stale conflict token is answered with the task as the caller's read
  would show it (address, fields, token, service).
- The framework's 404 is `NOT_FOUND` and its 405 is `VERB_NOT_CARRIED`, in
  the service's own refusal shape. `/api/declaration/` and `/api/whoami/`
  (trailing slash) are `ADDRESS_MALFORMED`.
- A form fault in a call on a scope the caller cannot see is
  `ARGUMENT_UNKNOWN`.

### Callers: conflict token and time

- The conflict token (`task.updated_at`) is written by the service from its
  own clock as `max(now, previous + 1 µs)`, only on a write that changes the
  task. A write that changes nothing keeps the token; a changed draft text
  now moves it, where before it stood still. Two writes no longer share a
  token, and a write no longer carries an earlier token than the one it
  replaces.
- Instants in answers are ISO-8601 in UTC to the microsecond. A member
  without a value is absent from an answer rather than written as `null`.

### Callers: lifecycle rules

- `create` under a root that has not been sent is refused with `ROOT_UNSENT`
  409; under a closed root with `ROOT_CLOSED` 409.
- `delete` of a draft root that still has tasks under it is refused with
  `CHILDREN_PRESENT` 409, naming the `children`.
- `annotate` on the commission of a delivered task is refused with
  `TRANSITION_REFUSED`, with `rework` in `next`.

### Callers: listing

- The task listing (`query` on REST, `dispatch_query` on MCP) pages by
  keyset: `page_size` (default 50, 1 to 200), `after`, `sort` (`id` or
  `created_at`), `direction` (`asc`/`desc`). The answer is
  `{ "tasks": [...], "next_cursor": "..." }`, with no `next_cursor` on the
  last page. `limit` and `cut` are gone; `limit` is refused as an unknown
  argument. New refusals: `PAGE_SIZE_REJECTED`, `SORT_UNDECLARED`,
  `CURSOR_FOREIGN`, `CURSOR_MALFORMED`.

### Callers: transport

- On REST the idempotency key travels only as the `Idempotency-Key` header;
  `idempotency_key` in the body or the query is refused as
  `ARGUMENT_UNKNOWN`. MCP keeps the argument. A router that forwards MCP
  calls over REST has to carry it as the header, as it carries
  `conflict_token` as `If-Match`.
- Every request carries a request id: `Kumbuka-Request-Id` is taken when it
  is a UUID, minted otherwise, returned on the answer and written to the log
  as `request_id`.

### MCP and declaration

- MCP answers `ping`. An unknown tool is JSON-RPC `-32602` with the declared
  tool names under `error.data.accepts`; an unknown method is `-32601`.
  `serverInfo.version` is the version of the build.
- The declaration is served at `GET /api/declaration` only, with argument
  placements `TOP`/`FIELDS`, defaults in their own JSON type, and a role only
  on restricted calls. Its version is the SHA-256 of its canonical text and
  travels as the `ETag`.
- **The declaration of each release is published beside the library jar**, as
  `ai.kumbuka:kumbuka-dispatch:<version>:json:declaration`
  (`kumbuka-dispatch-<version>-declaration.json` in GitHub Packages). It is
  byte for byte the body `GET /api/declaration` serves for that version, so a
  consumer can build against the declaration of the version it pins without
  starting the service.

### Operators

- Log lines follow one format and carry `request_id`. A refused start (an
  identity or tenancy configuration the service cannot run with) is logged
  at `WARN` in a line of its own before the service stops.
- An unexpected failure is logged with a reference and the class names of
  its cause chain; the caller receives the reference.

### Library consumers

- The public classes of the jar changed: the call router is gone, both
  adapters call the verb surface directly, refusals are two exception types
  (`DispatchException`, `SurfaceException`) and answers are records. Code
  built against 0.10.0 classes needs to be adapted.
- `null` is checked by the compiler on every main source: a value that may
  be absent is `Optional` or carries `org.jspecify.annotations.Nullable`.
