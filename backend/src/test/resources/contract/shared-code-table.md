<!--
Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
SPDX-License-Identifier: AGPL-3.0-only
This file is part of Kumbuka and is licensed under the GNU Affero
General Public License v3.0 only. See the LICENSE file in the
repository root for the full licence text.
-->

# Shared refusal codes

A copy of rule C8.7 of the platform service contract
(`code-conventions/service-contract.md`, platform-specs commit `37f4d6e`, contract round 3),
taken verbatim: the rule on `attempted`, the codes, and the members of `data`.
`SharedCodeTableTest` reads all three; the copy is held here so that the expectation does not
come from the service it checks. When the contract changes them, this copy is replaced by the new
one.

## Codes

**`attempted` is not listed per row.** Every refusal that falls once the route stands carries
`attempted` (C6.2); the column `data` names the members beyond it. `HEADER_INVALID` falls before
the route and carries none. `ADDRESS_MALFORMED` carries `attempted` where the route stands (an
address argument on MCP, a malformed part under a known route on REST) and none where it is
decided before routing (a path below a reserved name, C3.2). `NOT_FOUND` carries nothing (C8.4).

| Condition | Code | Status | `data` beyond `attempted` |
|---|---|---|---|
| body not parseable or not an object | `PAYLOAD_MALFORMED` | 400 | — |
| address not in the grammar | `ADDRESS_MALFORMED` | 400 | `offenders`: the declared name of the part that fails it (`scope`, `selector`, `address`) |
| write to an address without identifier | `ADDRESS_TRUNCATED` | 405 | `accepts` |
| unknown argument, also inside `fields` | `ARGUMENT_UNKNOWN` | 400 | `accepts` |
| mandatory argument missing | `ARGUMENT_MISSING` | 400 | `offenders` |
| value outside its declared form | `ARGUMENT_INVALID` | 400 | `offenders` |
| `Kumbuka-Surface` with another value or more than once (C14.5) | `HEADER_INVALID` | 400 | — |
| value not in the vocabulary of the scope | `VALUE_UNDECLARED` | 422 | `offenders` |
| filter on an undeclared field | `PREDICATE_UNKNOWN` | 422 | `offenders` |
| selector not declared in the scope | `SELECTOR_UNDECLARED` | 422 | — |
| selector withdrawn | `SELECTOR_WITHDRAWN` | 422 | — |
| verb unknown or not carried at this address | `VERB_NOT_CARRIED` | 405 | `accepts` |
| conflict token missing | `CONFLICT_TOKEN_MISSING` | 428 | — |
| conflict token stale | `CONFLICT_TOKEN_STALE` | 412 | `address`, `conflict_token`, `fields`, `service` (DEC-0041) |
| address occupied on create | `ALREADY_EXISTS` | 409 | `address` |
| object held by someone else | `CLAIM_HELD` | 409 | `state`, `next` |
| call needs a hold and none stands for the caller: nobody holds the object, or the caller's lease has ended, whatever receipt it presents | `CLAIM_ABSENT` | 409 | `state`, `next` |
| a hold stands and the receipt presented is not the holder's | `RECEIPT_WRONG` | 409 | `state`, `next` |
| nothing to draw | `DRAW_EMPTY` | 409 | `next` |
| transition refused by state | `TRANSITION_REFUSED` | 409 | `state`, `next`, `waiting_for` |
| role of the caller does not permit the call | `ROLE_INSUFFICIENT` | 403 | `next` where the call names an object; nothing beyond `attempted` where the caller holds no capacity of the service |
| scope readable, not writable for the caller | `SCOPE_READ_ONLY` | 403 | — |
| page size outside 1 to 200 | `PAGE_SIZE_REJECTED` | 400 | `offenders` |
| cursor of another query | `CURSOR_FOREIGN` | 400 | `offenders` |
| cursor not readable | `CURSOR_MALFORMED` | 400 | `offenders` (`after`), never the cursor |
| sort field not declared | `SORT_UNDECLARED` | 400 | `offenders` |
| absent, invisible or unroutable | `NOT_FOUND` | 404 | none |
| unanticipated failure | `UNEXPECTED_FAILURE` | 500 | `reference` |

## Members of `data`

**Members of `data`.** Each member has one meaning on every service and every hop (DEC-0042). A
refusal carries no member outside this list; the list grows only through this rule.

| Member | Meaning |
|---|---|
| `offenders` | declared names of the arguments the refusal concerns, a field written as its path (`fields.option`, `filter.status`), an element with its index (`apparatus[2]`, DEC-0043 R19); never a value, and never a transport location such as `body` (a body that does not parse has one possible offender and names none) |
| `accepts` | what the call or the address accepts: the arguments a call declares, the verbs carried at an address (C3.3), the tool names (C4.3) (DEC-0042) |
| `attempted` | the call as the caller's surface names it (C6.2); present on every refusal that falls once the route stands, see the rule above the table |
| `state` | the object's state as the caller's own `read` would answer it (DEC-0043 R10) |
| `next` | the calls open to the caller, as in an answer (C6.1) |
| `waiting_for` | who or what the object waits for, as in an answer (C6.1) |
| `address` | the complete address of the object the refusal concerns, as the service spells it (DEC-0041); see Open point 6 |
| `conflict_token`, `fields`, `service` | the current state of the object for a stale token (DEC-0041) |
| `children` | complete addresses of the objects under the addressed one that the refusal concerns (a service's own codes on brackets, TAR-0004 section 3) |
| `confirmation` | a confirmation the service issues now, to be passed with the repeated call; never one the caller sent |
| `reference` | the identifier of an unanticipated failure (C8.3) |
