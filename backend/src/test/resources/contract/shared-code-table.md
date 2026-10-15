<!--
Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
SPDX-License-Identifier: AGPL-3.0-only
This file is part of Kumbuka and is licensed under the GNU Affero
General Public License v3.0 only. See the LICENSE file in the
repository root for the full licence text.
-->

# Shared refusal codes

A copy of the table of rule C8.7 of the platform service contract
(`code-conventions/service-contract.md`, platform-specs commit `df81898`), taken
verbatim. `SharedCodeTableTest` reads it; the copy is held here so that the
expectation does not come from the service it checks. When the contract table
grows, this copy is replaced by the new one.

| Condition | Code | Status | `data` |
|---|---|---|---|
| body not parseable or not an object | `PAYLOAD_MALFORMED` | 400 | — |
| address not in the grammar | `ADDRESS_MALFORMED` | 400 | — |
| write to an address without identifier | `ADDRESS_TRUNCATED` | 405 | `accepts` |
| unknown argument, also inside `fields` | `ARGUMENT_UNKNOWN` | 400 | `accepts` |
| mandatory argument missing | `ARGUMENT_MISSING` | 400 | `offenders` |
| value outside its declared form | `ARGUMENT_INVALID` | 400 | `offenders` |
| value not in the vocabulary of the scope | `VALUE_UNDECLARED` | 422 | `offenders` |
| filter on an undeclared field | `PREDICATE_UNKNOWN` | 422 | `offenders` |
| selector not declared in the scope | `SELECTOR_UNDECLARED` | 422 | — |
| selector withdrawn | `SELECTOR_WITHDRAWN` | 422 | — |
| verb unknown or not carried at this address | `VERB_NOT_CARRIED` | 405 | `accepts` |
| conflict token missing | `CONFLICT_TOKEN_MISSING` | 428 | `attempted` |
| conflict token stale | `CONFLICT_TOKEN_STALE` | 412 | `address`, `conflict_token`, `fields`, `service` (DEC-0041) |
| address occupied on create | `ALREADY_EXISTS` | 409 | `address` |
| object held by someone else | `CLAIM_HELD` | 409 | `attempted`, `state`, `next` |
| receipt not the holder's | `RECEIPT_WRONG` | 409 | `attempted` |
| nothing to draw | `DRAW_EMPTY` | 409 | — |
| transition refused by state | per declaration | 409 | `attempted`, `state`, `next`, `waiting_for` |
| page size outside 1 to 200 | `PAGE_SIZE_REJECTED` | 400 | `offenders` |
| cursor of another query | `CURSOR_FOREIGN` | 400 | `offenders` |
| cursor not readable | `CURSOR_MALFORMED` | 400 | `offenders` |
| sort field not declared | `SORT_UNDECLARED` | 400 | `offenders` |
| absent, invisible or unroutable | `NOT_FOUND` | 404 | none |
| unanticipated failure | `UNEXPECTED_FAILURE` | 500 | `reference` |
