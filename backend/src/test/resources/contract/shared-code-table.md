<!--
Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
SPDX-License-Identifier: AGPL-3.0-only
This file is part of Kumbuka and is licensed under the GNU Affero
General Public License v3.0 only. See the LICENSE file in the
repository root for the full licence text.
-->

# Shared refusal codes

A copy of the two tables of rule C8.7 of the platform service contract
(`code-conventions/service-contract.md`, platform-specs commit `ff0a4ba`), taken
verbatim: the codes, and the members of `data`. `SharedCodeTableTest` reads
both; the copy is held here so that the expectation does not come from the
service it checks. When the contract tables change, this copy is replaced by
the new one.

## Codes

| Condition | Code | Status | `data` |
|---|---|---|---|
| body not parseable or not an object | `PAYLOAD_MALFORMED` | 400 | — |
| address not in the grammar | `ADDRESS_MALFORMED` | 400 | — |
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
| conflict token missing | `CONFLICT_TOKEN_MISSING` | 428 | `attempted` |
| conflict token stale | `CONFLICT_TOKEN_STALE` | 412 | `address`, `conflict_token`, `fields`, `service` (DEC-0041) |
| address occupied on create | `ALREADY_EXISTS` | 409 | `address` |
| object held by someone else | `CLAIM_HELD` | 409 | `attempted`, `state`, `next` |
| call needs a hold and none stands for the caller: nobody holds the object, or the caller's lease has ended, whatever receipt it presents | `CLAIM_ABSENT` | 409 | `attempted`, `state`, `next` |
| a hold stands and the receipt presented is not the holder's | `RECEIPT_WRONG` | 409 | `attempted` |
| nothing to draw | `DRAW_EMPTY` | 409 | — |
| transition refused by state | `TRANSITION_REFUSED` | 409 | `attempted`, `state`, `next`, `waiting_for` |
| role of the caller does not permit the call | `ROLE_INSUFFICIENT` | 403 | `attempted`, `next` |
| scope readable, not writable for the caller | `SCOPE_READ_ONLY` | 403 | — |
| page size outside 1 to 200 | `PAGE_SIZE_REJECTED` | 400 | `offenders` |
| cursor of another query | `CURSOR_FOREIGN` | 400 | `offenders` |
| cursor not readable | `CURSOR_MALFORMED` | 400 | `offenders` |
| sort field not declared | `SORT_UNDECLARED` | 400 | `offenders` |
| absent, invisible or unroutable | `NOT_FOUND` | 404 | none |
| unanticipated failure | `UNEXPECTED_FAILURE` | 500 | `reference` |

## Members of `data`

A refusal carries no member outside this list.

| Member | Meaning |
|---|---|
| `offenders` | declared names of the arguments the refusal concerns, a field written as its path (`fields.option`, `filter.status`), an element with its index (`apparatus[2]`, DEC-0043 R19); never a value |
| `accepts` | what the call or the address accepts: the arguments a call declares, the verbs carried at an address (C3.3), the tool names (C4.3) (DEC-0042) |
| `attempted` | the call as the caller's surface names it (C6.2) |
| `state` | the object's state as the caller's own `read` would answer it (DEC-0043 R10) |
| `next` | the calls open to the caller, as in an answer (C6.1) |
| `waiting_for` | who or what the object waits for, as in an answer (C6.1) |
| `address` | the complete address of the object the refusal concerns, as the service spells it (DEC-0041); see Open point 6 |
| `conflict_token`, `fields`, `service` | the current state of the object for a stale token (DEC-0041) |
| `children` | complete addresses of the objects under the addressed one that the refusal concerns (a service's own codes on brackets, TAR-0004 section 3) |
| `confirmation` | a confirmation the service issues now, to be passed with the repeated call; never one the caller sent |
| `reference` | the identifier of an unanticipated failure (C8.3) |
