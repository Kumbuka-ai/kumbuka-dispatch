# kumbuka-dispatch

The dispatch service.

One party states a task. Another takes it up, performs it, and answers. A
person ratifies the answer. This service holds that exchange as durable,
addressable objects, so that months later it is still readable who asked what,
who answered, and what was accepted.

It is a **prospective** record — a commission and its answer — and not an
account of what a system did. That distinction is its whole boundary.

## What is here today

The **substrate**: the schema, its own database role, its own migration
sequence, row-level security on the tenancy axis, the binding to the tenant
realm, and the operator boundary.

The **object model is not here yet**: no roles of an exchange, no status
machine, no freeze, no addenda, no numbering, no audit log, no caller-facing
verbs. Those are the domain half and arrive separately. What this repository
carries is the ground they will stand on, built and proven first because
proving isolation is much harder once there is data to lose.

## How isolation is built

Four mechanisms, each of which can be removed independently and each of which
has a probe that watches it fail.

**Two enforcement layers.** The ORM filters every statement it builds on the
tenant. PostgreSQL's row-level security filters everything else — raw SQL,
native queries, any path around the ORM. Either layer alone would carry the
guarantee; both together are the seam. `FailClosedProbeIT` observes each layer
working with the other one switched off, so neither can be quietly inert.

**The policy fails closed.** The predicate compares `tenant_id` against a
session setting read with `current_setting('app.tenant_id', true)`. When
nothing bound it, the comparison is against NULL and matches no row. A
transaction that forgot to bind a tenant therefore sees nothing rather than
everything.

**`FORCE`, not just `ENABLE`.** `ENABLE ROW LEVEL SECURITY` exempts the
table's owner. The owner here is the migrating role, and it is not a
hypothetical caller: `V5` carries DML and runs under it, so `ENABLE` alone
would let a migration write across the tenant boundary. `FORCE` binds it, and
`RowLevelSecurityProbeIT` removes `FORCE`, watches a foreign tenant's row
appear under the owner, and puts it back. The service role is not the owner
and is therefore bound by `ENABLE` already — and, holding no ownership, it
cannot take `FORCE` off, cannot `DISABLE` the policy and cannot `DROP` it.
The same probe observes all three refusals.

**The service role owns nothing.** Its whole entitlement is `USAGE` on the
schema plus `SELECT`, `INSERT` and `UPDATE` on three named tables, written out
in `V8__enumerated_privileges.sql`. No `DELETE` — no verb deletes. Nothing at
all on `flyway_schema_history`. And in particular no `TRUNCATE`, which bypasses
row-level security completely, independently of every policy and of whether a
tenant is bound. An owner would hold all of those implicitly, with no grant
anywhere to show for it, which is what this arrangement replaces. The price is
accepted and named: a later migration that forgets its grant produces a service
that cannot read its own new table — loudly and immediately, rather than
silently and permanently. `ServiceRolePrivilegeIT` reads the catalog and
reports drift in both directions, so a grant too many and a grant too few both
fail the build.

**The operator boundary is a missing GRANT.** No privilege exists that would
let the provider's role read an exchange. It is not a rule in application
code and not a policy: the enforcing artifact is a line that does not exist.
`MissingGrantProbeIT` observes the refusal, then grants the privilege, watches
the access succeed, and revokes it — because an absence that was never seen to
matter is not a boundary. The role it uses carries `BYPASSRLS` on purpose, so
the refusal cannot be attributed to row-level security.

## Roles

Three, kept apart deliberately.

| Role | Holds | Why |
| --- | --- | --- |
| `kumbuka_dispatch` | `USAGE` on its schema, `SELECT`/`INSERT`/`UPDATE` on three named tables — and nothing else, anywhere | The service connects as this and nothing else. It owns nothing: an owner holds the full ACL implicitly and can grant itself back whatever is revoked. Neither superuser nor `BYPASSRLS` — either would make every policy in the schema inert. |
| the migrating role | `CREATEROLE`, `CREATE` on the database, and ownership of the schema and everything in it | Creating the service role is the one privileged act the migration set performs. It must carry neither superuser nor `BYPASSRLS`, and `V8` refuses to apply if it does: the migrator owns every view, and a view without `security_invoker` reads its base tables with its owner's privileges, so either attribute would void the tenant filter silently. |
| the provider role | nothing here | The operator boundary. |

The service role holds nothing outside its schema, and
`ServiceRoleConformanceIT` asks the whole catalog rather than one table — so
it also covers the neighbouring service that does not exist yet. Inside the
schema the entitlement is checked relation by relation and privilege by
privilege by `ServiceRolePrivilegeIT`.

## Running it

Configuration is environment variables with a `DISPATCH_` prefix; see
`backend/src/main/resources/application.properties` for the full set and its
development defaults. There is no deployment hostname and no credential in
this repository.

```
DISPATCH_DB_JDBC_URL        jdbc:postgresql://<host>:5432/<database>
DISPATCH_DB_USERNAME        the service role         (default kumbuka_dispatch)
DISPATCH_DB_PASSWORD        its password
DISPATCH_MIGRATOR_USERNAME  the migrating role
DISPATCH_MIGRATOR_PASSWORD  its password
DISPATCH_OIDC_ISSUER        the tenant realm's issuer URL
DISPATCH_TENANT_ID          the tenancy axis for this deployment
```

**The service role's password must be rotated.** `V2__service_role.sql`
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
