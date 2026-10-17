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
  and empties those columns on the answered addendum. A database at `V16`
  takes it as the next migration. `migration/check-kd003.sql` lists the rows
  it will move, read only.
- **A database that already ran `V17`** (0.9.0 – 0.10.0, or a pre-release
  build) refuses to start with `Detected resolved migration not applied to
  database: 16.1`. Start 0.11.0 once with `QUARKUS_FLYWAY_OUT_OF_ORDER=true`:
  `V16_1` then runs once, against `exchange` only, and every later start
  validates without the setting.

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
