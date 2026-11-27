<!--
Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
SPDX-License-Identifier: AGPL-3.0-only
This file is part of Kumbuka and is licensed under the GNU Affero
General Public License v3.0 only. See the LICENSE file in the
repository root for the full licence text.
-->
# kumbuka-dispatch

The dispatch service.

One party states a task. Another takes it up, performs it, and answers. A
person ratifies the answer. This service holds that exchange as durable,
addressable objects, so that months later it is still readable who asked what,
who answered, and what was accepted.

It is a **prospective** record — a commission and its answer — and not an
account of what a system did. That distinction is its whole boundary.

## What is here

The service is being rebuilt on its store line: the migrations
`backend/src/main/resources/db/store`, V1 to V6. An **instance** is one run of
a declared process, numbered under a selector of a scope; its tasks are
addressed as `dispatch://<scope>/<selector>/<number>.<sub>`; every change of
its control is an **act**, appended and never changed, with who made it, by
which permission and by which path (`interface` or `console`).

**The service offers fourteen calls: `read_definition`, the five acts on an
instance, the four calls of the draft and the commission of a task, and the
four calls that hold a task.** The
calls are rebuilt one family at a time in the changes that
follow, each declared, bound and offered together. `read_definition` reads a
seeded version of the process a selector declares: the head of the version,
its schema of control fields with the enumerations of its choice fields, its
activities with their result contracts, its start contract, and the
enumerations of the platform's system fields in the same form. Without
`version` it reads the active version; a selector or a version the scope does
not hold, and a version that is not seeded, are one `NOT_FOUND`. It checks the
permission `dispatch.instance.read` and writes nothing. Versions enter the
store only as seeds, never by a call.

The acts on an instance, each checking one permission:

- `start` (`dispatch.instance.start`) starts an instance of a selector, bound
  to its active seeded version, numbered from the selector's counter, with its
  title, an input text, a trigger reference, references to worklist items and
  other objects, and the field values of the start contract, each checked
  against the contract and the schema; it answers the instance
  `dispatch://<scope>/<selector>/<number>`.
- `conclude` (`dispatch.instance.conclude`) and `withdraw`
  (`dispatch.instance.withdraw`) close a running instance with its return: a
  text, and the verdict and outcome label the version requires, a label with
  its translation basis. Both take the instance's conflict token, the time of
  its last act. With unfinished tasks the first call is refused with a
  confirmation computed from them; the call with it withdraws them and closes
  the instance in one transaction. `withdraw` takes the address of an instance.
- `reference_attach` and `reference_detach` (`dispatch.instance.refer`) set
  and end a reference of a running instance, of the kind `item` or
  `reference`; an ended reference stays with the act that ended it.

The draft and the commission of a task, each checking `dispatch.task.commission`:

- `create` writes a task in draft: with `parent`, the address of a running
  instance of the selector, at the instance's next sub, from 1; without it, it
  starts an instance of the selector as `start` does, titled as the task, with
  an optional trigger and references and no field values, and the task is its
  first. The draft holds the title, the role it is addressed to (`apparatus`),
  an activity of the instance's version (the only one where the call names
  none), another task of the instance it concerns, criteria (the service gives
  their identifiers), free fields and a text. It writes no act. An optional
  idempotency key, 1 to 255 printable ASCII characters, belongs to its caller
  for 24 hours: a repeat with the same arguments answers the task, also after
  its send; with other arguments, or once the draft is deleted, it is refused
  as `IDEMPOTENCY_KEY_REUSED`. It answers the task
  `dispatch://<scope>/<selector>/<number>.<sub>`.
- `update` changes the draft: each field given takes its value, each path in
  `clear` is emptied (the title stays), every other stays.
- `delete` deletes the task and its draft outright; a key on it stays, without
  its task.
- `send` writes the act `send` and the commission from the draft -- its title,
  activity, role, task concerned, criteria, free fields and texts -- and opens
  the task; a draft without an activity is refused.

Each of the three takes the task in draft alone and its conflict token: the
draft's `changed_at`, moved on at every change as the later of the clock and
the previous value plus one microsecond; from the send on, the time of the
task's last act.

The holding of a task, each checking `dispatch.task.execute`:

- `claim` takes an open task up and makes the caller its holder: the act
  `claim`, a holding, a receipt and a lease, and the task `active`. It takes
  an optional `duration`, a positive ISO-8601 duration, 30 minutes where none
  is named, and no conflict token. It answers the task with the receipt --
  32 random bytes in base64url, of which the store keeps the SHA-256 alone --
  and the end of the lease under `fields.lease_expires_at`. A task another
  holds is refused as `CLAIM_HELD`, a deferred one before its instant as
  `DEFERRAL_PENDING`. An optional idempotency key belongs to its caller for 24
  hours: a repeat with the same arguments, while the caller holds the task by
  that claim, answers the task with a new receipt, and the earlier one no
  longer matches; otherwise the repeat takes nothing.
- `renew` extends the holder's lease from the time of its act, 30 minutes or
  the duration named.
- `release` gives the task back, `open`, with an optional remark.
- `defer` gives the task back, `open`, until the instant in
  `fields.not_before`; it is not taken up before it.

Each of the three is the holder's and presents the youngest receipt of its
holding; `renew` and `defer` take the task's conflict token, `release` none.
A lease lapses when its end is reached while the task is `active`, and the
lapse is derived when a task is read or a call is checked, never written: the
task reads `open` without a holder, and from its third lapse on `on_hold`,
`external`, parked. The former holder is then refused as `CLAIM_ABSENT`.

- **REST** under `/api` answers `GET /api/{scope}/{selector}` with the
  optional query `version`, which is `read_definition`; `POST
  /api/{scope}/{selector}:start`; `POST
  /api/{scope}/{selector}/{number}:<call>` for the four calls on an instance,
  the conflict token in `If-Match`; `POST /api/{scope}/{selector}`, which is
  `create`, its key in the header `Idempotency-Key`; `PATCH` and `DELETE` on
  `/api/{scope}/{selector}/{number}.{sub}`, which are `update` and `delete`,
  and `POST /api/{scope}/{selector}/{number}.{sub}:send`, the conflict token in
  `If-Match`; `POST /api/{scope}/{selector}/{number}.{sub}:<call>` for
  `claim`, its key in the header `Idempotency-Key`, and for `renew`,
  `release` and `defer`, the conflict token in `If-Match`; `GET
  /api/declaration`, the declaration, which lists the fourteen calls; and `GET
  /api/whoami`, the caller's view.
- **MCP** at `POST /mcp`, JSON-RPC 2.0, answers `initialize` and `ping`, lists
  the fourteen tools `dispatch_<call>`, and answers a `tools/call` of any other name
  as a call to an unknown tool. Present unless the build sets
  `kumbuka.local-mcp.enabled=false`.

What every request carries already:

- **A request id**: `Kumbuka-Request-Id` is taken where it is a UUID and
  minted otherwise, stands in every log line of the request, and is returned in
  the response header.
- **A refusal** is one envelope, `{reason, message, data}`, on both surfaces;
  `NOT_FOUND` says the same for an absent object, an invisible scope and an
  address no route serves.

The first part of the new kernel is the writer of acts: it appends an act to
an instance and gives it the next number of the instance's sequence, under a
lock on the instance's row, so two writers never take the same number, and a
time that rises strictly within the instance, one microsecond past its last
act where the clock has not moved on. It records the permission and the path
it is handed; the store refuses an authentication level on any path but
`console`.

Permissions are named `dispatch.<object>.<act>`. Until the platform hands them
to the identity, the service reads them off the two realm roles of the token,
in one place: `dispatch-console` and `dispatch-executor`, and both roles for
the union. A token with neither holds no permission of this service.

## How isolation is built

Four mechanisms, each of which can be removed independently.

**Two enforcement layers.** The ORM filters every statement it builds on the
tenant. PostgreSQL's row-level security filters everything else — raw SQL,
native queries, any path around the ORM. Either layer alone would carry the
guarantee; both together are the seam. `ActTenancyIT` observes each layer
keeping another tenant's act unread and unwritten with the other one taken out
of the way, so neither can be quietly inert.

**The policy fails closed.** The predicate compares `tenant_id` against a
session setting read with `current_setting('app.tenant_id', true)`. When
nothing bound it, the comparison is against NULL and matches no row. A
transaction that forgot to bind a tenant therefore sees nothing rather than
everything.

**`FORCE`, not just `ENABLE`.** `ENABLE ROW LEVEL SECURITY` exempts the
table's owner. The owner here is the migrating role, and a migration that
carries DML runs under it, so `ENABLE` alone would let a migration write
across the tenant boundary. `FORCE` binds it on every table of the store line,
and `StoreLineIT` removes `FORCE`, watches a foreign tenant's row appear under
the owner, and puts it back. The service role is not the owner and is
therefore bound by `ENABLE` already; holding no ownership, it cannot take
`FORCE` off, `DISABLE` the policy or `DROP` it.

**The service role owns nothing.** Its whole entitlement is `USAGE` on the
schema, `SELECT` and `INSERT` on every table of the store line, `DELETE` on
`task` and the four tables of the draft workspace alone, and `UPDATE` only on
the columns that are projection, every grant written out in the store line.
An act, a content and a field value are never changed and never deleted.
Nothing at all on `flyway_schema_history`. And in particular no `TRUNCATE`,
which bypasses row-level security completely, independently of every policy and
of whether a tenant is bound. An owner would hold all of those implicitly, with no grant
anywhere to show for it, which is what this arrangement replaces. The price is
accepted and named: a later migration that forgets its grant produces a service
that cannot read its own new table — loudly and immediately, rather than
silently and permanently. `StoreLineIT` reads the catalog and holds the
privileges to the store line's enumeration in both directions, so a grant too
many and a grant too few both fail the build.

**The operator boundary is a missing GRANT.** No privilege exists that would
let the provider's role read a task. It is not a rule in application
code and not a policy: the enforcing artifact is a line that does not exist.
`MissingGrantProbeIT` observes the refusal, then grants the privilege, watches
the access succeed, and revokes it — because an absence that was never seen to
matter is not a boundary. The role it uses carries `BYPASSRLS` on purpose, so
the refusal cannot be attributed to row-level security.

## Roles

Three, kept apart deliberately.

| Role | Holds | Why |
| --- | --- | --- |
| `kumbuka_dispatch` | `USAGE` on its schema, the table privileges written out above, and `SELECT` on the platform's read contract of scopes — and nothing else, anywhere | The service connects as this and nothing else. It owns nothing: an owner holds the full ACL implicitly and can grant itself back whatever is revoked. Neither superuser nor `BYPASSRLS` — either would make every policy in the schema inert. |
| the migrating role | `CREATEROLE`, `CREATE` on the database, and ownership of the schema and everything in it | Creating the service role is the one privileged act the migration set performs. It must carry neither superuser nor `BYPASSRLS`, and `V1` of the store line refuses to apply if it does: the migrator owns every view, and a view without `security_invoker` reads its base tables with its owner's privileges, so either attribute would void the tenant filter silently. |
| the provider role | nothing here | The operator boundary. |

Outside its schema the service role holds one thing: `SELECT` on the
platform's read contract of scopes, which the platform grants and this service
reads to resolve a scope. `ServiceRoleConformanceIT` asks the whole catalog
rather than one table, and refuses anything beyond it. Inside the
schema the entitlement is checked relation by relation and privilege by
privilege by `StoreLineIT`.

## Running it

Configuration is environment variables with a `DISPATCH_` prefix; see
`backend/src/main/resources/application.properties` for the full set and its
development defaults. There is no deployment hostname and no credential in
this repository.

```
DISPATCH_DB_JDBC_URL        jdbc:postgresql://<host>:5432/<database>
DISPATCH_DB_USERNAME        the service role         (default kumbuka_dispatch)
DISPATCH_DB_PASSWORD        its password
DISPATCH_MIGRATOR_USERNAME  the migrating role       (required: see below)
DISPATCH_MIGRATOR_PASSWORD  its password
DISPATCH_OIDC_ISSUER        the tenant realm's issuer URL
DISPATCH_OIDC_CLIENT_ID     this service's client in the realm
DISPATCH_OIDC_AUDIENCE      the audience a token must carry
DISPATCH_TENANT_ID          the tenancy axis for this deployment  (required, no default)
DISPATCH_SCOPE_ID           this deployment's own scope           (required, no default)
```

The service refuses to start without a tenant and without an audience and a
principal claim. The migrating role has no usable default: the development
fallback is the database superuser, which `V1` refuses, so a deployment names a
role that carries `CREATEROLE` and neither superuser nor `BYPASSRLS`.

Every log line has the form
`HH:mm:ss LEVEL [category] (thread) [request id] message` and carries no
exception: an unexpected failure is logged once, under the reference its
refusal hands the caller.

**The service role's password must be rotated.** `V1` of the store line
creates the role with a placeholder so that a cold start against an empty
database needs no manual step. Any deployment reachable from outside a
development machine replaces it with `ALTER ROLE kumbuka_dispatch PASSWORD …`
from its own secret store.

### Build and test

```
cd backend
mvn verify          # unit tests and the full integration suite
docker build -t kumbuka-dispatch .
```

The integration suite starts a PostgreSQL and a Keycloak of its own through
Testcontainers, so Docker must be available. It is **not** behind a profile:
every guarantee this service makes is a statement about a running database or
a running identity provider, and a gate that has to be switched on is one that
will be found switched off.

## Licence

AGPL-3.0-only. See `LICENSE`.
