<!--
Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
SPDX-License-Identifier: AGPL-3.0-only
This file is part of Kumbuka and is licensed under the GNU Affero
General Public License v3.0 only. See the LICENSE file in the
repository root for the full licence text.
-->
# Where `assistant-surface.md` came from

`assistant-surface.md` is **written by hand** from

    platform-specs @ aa425214ce66cefa3ce4a5789c76a4d778db560d
    docs/architecture/targets/TAR-0004-the-dispatch-service-carries-commissioned-work-from-issue-to-acceptance.md
    docs/concepts/concept-dispatch-store-kernel-and-surface.md

on 2026-10-06. It replaces the verbatim copy of the
earlier contract of the assistant surface, whose process verbs this surface no
longer carries.

## The amendment of 2026-10-11: the page of a listing

The request and answer of `dispatch_query` and its four refusals were rewritten
from the platform's pagination frame, which the operator ratified on
2026-10-11 and which ADR-0049 is to record. The frame fixes the names
(`page_size`, `after`, `sort`, `direction`, `next_cursor`), the bounds (50 by
default, 200 at most), the two sort keys and their second key, and the four
reason codes; the description of the call and the message patterns are this
surface's proposal, as the rest of the descriptions are.

## The amendment of 2026-10-12: the list under `objects`, and `offenders`

Rewritten from ADR-0049 and DEC-0042 as ratified on 2026-10-12
(platform-specs, branch `docs/refusal-form-and-paging-alignment`): a page
carries its objects under `objects`; every `page_size` a listing cannot take,
a value that is no whole number included, is `PAGE_SIZE_REJECTED`; a refusal of
a call's arguments names them in `data.offenders`, by name and never by value.

## The amendment of 2026-10-13: no value, an instruction, and `children`

Rewritten from DEC-0042 as sharpened on 2026-10-13 (platform-specs PR #9,
commit `57951ef`): every member of `data` means one thing on every service, so
`CHILDREN_UNFINISHED`, which concerns no argument, names the unfinished
children under `data.children` and no longer under `data.offenders`; neither
a message nor a member of `data` returns a value the caller sent, so the
patterns that carried `{value}`, `{key}` or `{selector}` name the argument and
the form it takes instead; every remedy is an instruction, worded as the act
the caller takes, and so is the sentence of every step in `next`. The
descriptions of the calls are left as they were: their wording follows a
decision of its own.

## The amendment of 2026-10-15: the platform's vocabulary, and null

Rewritten from DEC-0043 R17 to R19 and DEC-0042 as ratified on 2026-10-15
(platform-specs PR #9). JSON `null` on an argument is the argument absent, and
every optional argument's description names its fallback. A refusal repeats
neither the caller's input nor the tenant's vocabulary: no message names a
scope, a bracket kind, an address, a state, a conflict token or the instant of
a deferral, and each points at the member of `data` or the read that shows
it. A name a call does not declare is answered with the names it does declare,
under `data.accepts`, and a wrong value of several is named by its position.
Each refusal is now worded from one of several declared messages, so the
table lists messages beside the reasons they are raised under, and the forms
an argument takes are a declared set of their own. The first entry of `next`
after a claim is concept section 3.5 as amended on the same branch. The
descriptions of the calls are left as they were; their positive wording
follows in the next change.

## The amendment of 2026-10-15, second part: the texts in their bundles

Every text the service addresses to a caller now lives in a Qute message
bundle under `src/main/resources/messages/`, one per kind of text, and this
copy states them as those files hold them: the descriptions of the calls, the
messages of the refusals as their templates, the remedies. Every text that
tells a caller what to do is worded positively under DEC-0043 R21, the
descriptions of `dispatch_claim` and `dispatch_claim_next` included; the
concept's section 3.5 follows this wording. Each form an argument takes is a
message of its own, so the separate table of forms is gone.

## The amendment of 2026-10-15, third part: the declaration published

Section 6 is written from ADR-0039 as ratified on 2026-10-15: the declaration
held by the service, on its REST surface, with an effect class drawn from the
router's closed set, under a version. The counter-probe of the endpoint is
`contract/DeclarationTranscript.java`, transcribed from the router's
definitions, concept section 3.2 and DEC-0043 R17, and importing nothing from
the service.

## What is word for word, and what is not

The description of the argument `apparatus` and the first entry of `next`
after a claim are concept section 3.5 word for word, and the descriptions of
`dispatch_claim` and `dispatch_claim_next` are that section worded positively; the line breaks of the concept's code blocks
are its layout and are not part of the text. The concept fixes no other
wording, so the remaining descriptions and the refusal patterns are written
here, beside the surface, and are the service's proposal until the concept
apparatus ratifies them.

## Why a document and not a list in Java

The conformance tests hold the declaration against this document and against
the fixed values in `contract/TargetSurface.java`, both written from the
specification and neither from the code. A copy that is stale turns a test red
and somebody looks; an expectation derived from the declaration it checks is
green for ever and says nothing.

## The amendment of 2026-10-16: the service contract

Rewritten from the platform service contract, `code-conventions/service-contract.md`
(platform-specs commit `df81898`, ratified 2026-10-15). The codes and statuses are the
contract's table C8.7 wherever it names the condition: a holder's call by a caller who holds
nothing is `CLAIM_HELD` where another executor holds the task, the state's refusal where nobody
does, and `LEASE_LAPSED` where the caller's own lease ended; `NOTHING_TO_TAKE` is `DRAW_EMPTY`,
`SELECTOR_UNKNOWN` is `SELECTOR_UNDECLARED` beside a new `SELECTOR_WITHDRAWN`, a call at an
address it does not apply at or a verb this service does not carry is `VERB_NOT_CARRIED` with
`data.accepts`, an address out of the grammar is `ADDRESS_MALFORMED`, a body that is no object
`PAYLOAD_MALFORMED`, a lease that is no positive duration `ARGUMENT_INVALID`, and a call fenced
by the conflict token that arrives without one `CONFLICT_TOKEN_MISSING`, refused by the kernel
after the scope resolves. `CONFLICT_TOKEN_STALE` carries the task as it stands (DEC-0041). The
declaration is served at `/api/declaration` alone, with placements `TOP` and `FIELDS`, defaults
in their own JSON type, and a role only on a restricted call (C5.1, C5.4). The texts are
unchanged but for the messages and remedies of the codes that are new.

## The amendment of 2026-10-16, second: the contract change of that day

The operator changed rows of table C8.7 on 2026-10-16; until the change is on platform-specs
`main`, the change as it was handed to this service is the source. A holder's call by a caller
who holds no claim, because nobody holds the task or the caller's own lease ended, whatever
receipt it carried, is `CLAIM_ABSENT` 409 with `attempted`, `state` and `next`; `LEASE_LAPSED`
is no longer declared, and `RECEIPT_WRONG` stays for a hold that stands. The state's refusal is
`TRANSITION_REFUSED` 409, the call of another part `ROLE_INSUFFICIENT` 403 with `attempted` and
`next`, decided after the address resolves, and `SCOPE_READ_ONLY` 403 is a row of the table and
carries no `data`. A `Kumbuka-Surface` header with another value than `assistant`, or twice, is
`HEADER_INVALID` 400 with no `data`, decided after authentication and before anything else; the
MCP adapter does not read the header (C14.4, C14.5). The messages are unchanged but for the one
of the header, which names no call because none is known when it is decided.

## The amendment of 2026-10-16, third: the second contract change and the guards of the copy

Codes and table are those of platform-specs `ff0a4ba` (contract round 2), whose wording the
copy in `contract/shared-code-table.md` holds verbatim. The two codes below are not rows of the
table; they are this service's own, from the operator's addendum of the same day: a root
with unfinished children is `CHILDREN_UNFINISHED` 409 with `attempted`, `children` and the
confirmation handed out now, and `data.children` names each child by its complete address, as the
list of members has it; an answer the question does not offer, an option or free text, is
`OPTION_UNOFFERED` 409 with the field in `offenders`, decided against the question the task holds;
`DEFERRAL_PENDING` stays 409 with the task's situation. The names `declaration` and `whoami` are
the REST surface's literal paths and no scope: `ADDRESS_MALFORMED` 400 with no `data`, refused
before the route. The texts of the two new codes and of the reserved name were written with this
surface.
