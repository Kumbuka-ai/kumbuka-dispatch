---
type: contract-copy
title: "The dispatch service's verb surface: twenty-five calls, two sizes of answer, refusals that explain themselves"
written: 2026-10-06
amended: 2026-10-16
sources:
  - "platform-specs aa42521, docs/architecture/targets/TAR-0004-the-dispatch-service-carries-commissioned-work-from-issue-to-acceptance.md, sections 3, 5, 6, 7"
  - "platform-specs aa42521, docs/concepts/concept-dispatch-store-kernel-and-surface.md, section 3"
  - "the platform's pagination frame for every primary object, ratified by the operator 2026-10-11 (the substance of ADR-0049, not yet written): page_size, after, sort, direction, next_cursor, and the refusals PAGE_SIZE_REJECTED, CURSOR_MALFORMED, CURSOR_FOREIGN, SORT_UNDECLARED"
  - "platform-specs PR #9, docs/architecture/decisions/DEC-0043, rules R17 to R21, ratified 2026-10-15; DEC-0042 as ratified the same day (accepts in place of an unknown name)"
  - "platform-specs PR #9, docs/concepts/concept-dispatch-store-kernel-and-surface.md, section 3.5: the first entry of next after a claim"
  - "platform-specs 0f3978e, code-conventions/service-contract.md: CLAIM_ABSENT, TRANSITION_REFUSED, ROLE_INSUFFICIENT, SCOPE_READ_ONLY as a row of the table, HEADER_INVALID, rules C14.4 and C14.5, the members of data"
  - "the second change to code-conventions/service-contract.md the operator ratified 2026-10-16, not yet on platform-specs main: CHILDREN_UNFINISHED and OPTION_UNOFFERED, data.children as complete addresses, the names declaration and whoami reserved"
---
<!--
Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
SPDX-License-Identifier: AGPL-3.0-only
This file is part of Kumbuka and is licensed under the GNU Affero
General Public License v3.0 only. See the LICENSE file in the
repository root for the full licence text.
-->

# The dispatch service's verb surface

This document is the expectation the service's conformance tests read: the calls, what each one
says about itself, and the refusals. Every text in it is the service's text as its message bundles
hold it, under `src/main/resources/messages/`, and every text that tells a caller what to do is
worded positively (DEC-0043 R21). It is written by hand from TAR-0004 and from the concept
document named above, and never generated from the service's declaration. Where those two
documents fix a text, it stands here word for word and is marked so; where they leave the wording
to the service, the text below was written with this surface and is the service's
proposal until the concept apparatus ratifies it.

## 1. What the service holds

A **task** is one commission and its answer, addressed as
`dispatch://<scope>/<selector>/<number>.<sub>`; its technical address is `dispatch://<uuid>`. Tasks
are grouped in **brackets**: a root, sub-position `0`, and the children `1`, `2` and so on. A root
that is closed has only closed children.

A task is in one of six states: `draft`, `open`, `active`, `on_hold` (with `question`,
`dependency` or `external`), `delivered`, `closed` (with `accepted`, `rejected`, `failed` or
`withdrawn`).

## 2. One form for every call

What names the target stands at the top level: the address, or scope and selector where no task
exists yet. The transport artefacts stand at the top level too: conflict token, receipt, duration,
idempotency key and the confirmation for a bracket root. Everything a call writes stands under
`fields`. Both levels are closed: an argument a call does not declare is refused, the refusal lists the
arguments the level accepts under `data.accepts` and never the name that arrived, and nothing is
written. JSON `null` on an argument means the argument is absent, at both levels and on both
surfaces: an optional argument takes its fallback, a required one is refused as
`ARGUMENT_MISSING`, and a name the call does not declare is not refused when its value is `null`.
The description of every optional argument names its fallback and says that `null` has the same
effect as leaving the argument out.

On REST the same calls carry the same names without the `dispatch_` prefix; the path carries the
scope and selector or the address, the `If-Match` header the conflict token, and the body the rest.

## 3. The calls

### 3.1 Transitions

**`dispatch_send`**
> Sends your draft: the task is frozen and opens for an executor to take it up. Moves the task from
> draft to open (intermediate). Call as the commissioner, with the conflict token of your last read.
>
> From here on the commission's text stays as sent; add to it with dispatch_annotate. Withdraw the
> task with dispatch_withdraw.

**`dispatch_claim`** (concept section 3.5, worded positively under DEC-0043 R21)
> Takes up one open task and makes you its holder. Moves the task from open to active
> (intermediate). Call as an executor, naming the task by its address.
>
> Returns a receipt and the end of your hold. Keep the receipt: every later call on this task needs
> it.
>
> The task's text comes from dispatch_read_text, part "dispatch": read it next, before you start
> working.
>
> Your hold lasts 30 minutes, or the duration you state; extend it with dispatch_renew.

**`dispatch_claim_next`** (concept section 3.5, worded positively under DEC-0043 R21)
> Takes up the next open task addressed to you, chosen by the service; use dispatch_claim when you
> know the address. Moves the task from open to active (intermediate). Call as an executor with
> scope, selector and apparatus.
>
> Returns the address, a receipt and the end of your hold. Keep the receipt: every later call on
> this task needs it.
>
> The task's text comes from dispatch_read_text, part "dispatch": read it next, before you start
> working.
>
> dispatch_query lists the open tasks you can draw.

**`dispatch_release`**
> Gives a task you hold back: it is open again for any executor. Moves the task from active to open
> (intermediate). Call as its holder, with your receipt, when you lose control over the run.
>
> To give it back until a later instant use dispatch_defer; to close it as failed, dispatch_fail.

**`dispatch_defer`**
> Gives a task you hold back until an instant you name; from that instant on an executor can take it
> up. Moves the task from active to open (intermediate). Call as its holder, with your receipt, for
> a technical abort that a later attempt may overcome.
>
> dispatch_read and dispatch_query show the instant.

**`dispatch_renew`**
> Extends your hold on a task you are working on. The task stays active (intermediate). Call as its
> holder, with your receipt.
>
> The answer carries the new end of your hold.

**`dispatch_ask`**
> Pauses a task you hold and asks the commissioner a question, with answer options, free text
> admitted, or both. Moves the task from active to on_hold (intermediate). Call as its holder, with
> your receipt.
>
> You keep the task. The commissioner's dispatch_answer makes it active again with a fresh 30-minute
> hold; read the answer with dispatch_read_text, part "thread".

**`dispatch_answer`**
> Answers the question the holder asked: name one of its options, or give text where free text is
> admitted. Moves the task from on_hold to active (intermediate); the holder continues with a fresh
> 30-minute hold. Call as the commissioner, with the conflict token of your last read.
>
> Read the question first with dispatch_read_text, part "thread".

**`dispatch_hold`**
> Pauses a task you hold while it waits on a dependency or on something external. Moves the task
> from active to on_hold (intermediate). Call as its holder, with your receipt. You keep the task,
> and your hold stays in place while the task is paused.
>
> Continue with dispatch_resume.

**`dispatch_resume`**
> Continues a task you paused with dispatch_hold. Moves the task from on_hold to active
> (intermediate). Call as its holder, with your receipt.
>
> A question you asked continues with the commissioner's dispatch_answer.

**`dispatch_deliver`**
> Delivers your answer, its text and your metadata in one act. Moves the task from active to
> delivered (intermediate). Call as its holder, with your receipt.
>
> You keep the task while the commissioner reviews it. The commissioner closes it with
> dispatch_accept, or sends it back to you with a remark through dispatch_rework.

**`dispatch_rework`**
> Sends a delivered answer back to its holder with a remark saying what to change. Moves the task
> from delivered to active (intermediate); the holder continues with a fresh 30-minute hold. Call as
> the commissioner, with the conflict token of your last read.
>
> Read the answer first with dispatch_read_text, part "return".

**`dispatch_accept`**
> Accepts the delivered answer and closes the task. Moves the task from delivered to closed, outcome
> accepted (final). Call as the commissioner, with the conflict token of your last read; acceptance
> is the act of an identity other than the one that delivered.
>
> Read the answer first with dispatch_read_text, part "return". On a bracket root with unfinished
> children, repeat with the confirmation the refusal hands out.

**`dispatch_reject`**
> Declines a commission you were offered, with a remark saying why. Moves the task from open to
> closed, outcome rejected (final). Call as an executor.

**`dispatch_fail`**
> Closes a task you hold as failed, with a remark saying why. Moves the task from active or on_hold
> to closed, outcome failed (final). Call as its holder, with your receipt. For a technical abort
> use dispatch_defer; to hand the task back, dispatch_release.
>
> On a bracket root with unfinished children, repeat with the confirmation the refusal hands out.

**`dispatch_withdraw`**
> Withdraws a commission you give up. Moves the task from open, active, on_hold or delivered to
> closed, outcome withdrawn (final). Call as the commissioner, with the conflict token of your last
> read.
>
> On a bracket root with unfinished children the first call is refused and names them; repeat it
> with the confirmation it hands out to withdraw them and the root together.

### 3.2 Calls that are not transitions

**`dispatch_create`**
> Creates a task as a draft, for you to complete and send. With parent it adds a child to that
> bracket; left out, it opens a new bracket and the task is its root. The service allocates the
> number. Call as a commissioner; you become the task's commissioner.
>
> The draft stays yours until you send it with dispatch_send. Change it with dispatch_update, or
> discard it with dispatch_delete.

**`dispatch_update`**
> Changes your draft: its title, apparatus, text or metadata; what you leave out stays as it is.
> Call as the commissioner, with the conflict token of your last read. The task stays a draft.
>
> Once sent, a task keeps its text; add to it with dispatch_annotate.

**`dispatch_delete`**
> Deletes your draft outright and removes every trace of it. Call as the commissioner, with the
> conflict token of your last read. Deleting applies to a draft; a sent task is withdrawn with
> dispatch_withdraw.
>
> The answer carries the address the draft had, and that alone.

**`dispatch_read`**
> Reads the head of one task: its state and attributes, whether you or another executor holds it,
> its metadata, which texts it has, and the calls open to you with what each does, or what the task
> waits for.
>
> The task's text comes from dispatch_read_text.

**`dispatch_read_text`**
> Reads one part of a task's text: "dispatch" (the commission), "return" (the valid answer),
> "thread" (questions, answers, remarks and earlier answers, in order) or "addenda". One part per
> call.
>
> The commissioner reads every part in every state; an executor reads the parts of a task it holds.
> For "dispatch" and "return" the answer says how many addenda the text has.

**`dispatch_query`**
> Lists the tasks of one bracket kind in a scope, each with its head: its state, attributes and the
> calls open to you. Narrow by state, apparatus, bracket or address, the four declared filters;
> comma-separated values are alternatives.
>
> One page per call, in address order or in the order sort names. While next_cursor is in the
> answer, more follow: pass it as after, with the same other arguments, for the next page.

**`dispatch_annotate`**
> Adds an addendum to a text of a sent task: a supplement with its own time and author that stays
> for good. Name the part it supplements. Call as the identity that wrote that text; the task keeps
> its state.
>
> Read addenda with dispatch_read_text, part "addenda".

**`dispatch_relate`**
> Records the object a closed task was curated into: another task you can see, in any scope and
> bracket kind, other than the task itself. Call as the commissioner, with the conflict token of
> your last read. The task stays closed.
>
> Remove the relation with dispatch_unrelate.

**`dispatch_unrelate`**
> Removes the record of the object a closed task was curated into. Call as the commissioner, with
> the conflict token of your last read. The task stays closed.

### 3.3 The argument `apparatus` of `dispatch_claim_next` (concept section 3.5, word for word)

> Which apparatus you draw for. Every task is addressed to an apparatus: the kind of executor it is
> meant for, such as "code" or "review". Give one or more patterns, matched as alternatives. "*"
> stands for any run of characters, so "agent-*" matches "agent-backend". Case-sensitive. A
> pattern of only "*" is refused. dispatch_query lists open tasks with their apparatus.

## 4. Answers

A writing call answers lean: `address`, `fields` with the state and its attributes (`hold_reason`,
`outcome`, `not_before`) and, for the holder, `lease_expires_at`; `conflict_token`; `next`.
`dispatch_claim` and `dispatch_claim_next` add `receipt`. `dispatch_read` and `dispatch_query`
answer the full head: in addition `title`, `apparatus`, `identity` (the technical address),
`holder` (`self`, `other` or `nobody`), `lapse_count`, the pending question's options, both
metadata, `curated_in`, and `texts`, the texts that exist by type and suffix, never their content.
`dispatch_query` answers `{ "objects": [...], "next_cursor": "..." }`, one page; `next_cursor` is
absent on the last page. It takes `page_size` (50 if omitted, a whole number from 1 to 200; any
other value is refused as `PAGE_SIZE_REJECTED`), `after` (the
`next_cursor` of the previous page), `sort` (`id` or `created_at`, `id` if omitted) and `direction`
(`asc` or `desc`, `asc` if omitted); `sort` and `direction` are closed sets of values, each value
described in the declaration. `id` sorts by the address, number and sub numerically;
`created_at` takes the address as its second key. A cursor continues only the listing that
handed it out: the same scope, selector, filter, sort and direction. `dispatch_delete` answers the address
and nothing else. No answer names the identity of an actor.

Text comes only from `dispatch_read_text`, one part per call. No other call, no listing and no
refusal carries a text of a task.

`next` lists the transitions open to the caller, each with one sentence. Right after a claim, its
first entry for the holder is (concept section 3.5, word for word):

```
{ "call": "dispatch_read_text",
  "does": "Reads the commission: the task's text, the work this claim takes on. Call it next, before you start the work." }
```

and for the commissioner of a delivered task, before `dispatch_accept`, it is `dispatch_read_text`
as well. Where nothing is open to the caller, `waiting_for` says what the task waits for; a
deferred task waits for "the instant in fields.not_before, and then an executor to take it up".
The step `dispatch_accept` is offered to the commissioner alone and says so: "Accepts, as the
commissioner, the answer the holder delivered, and closes the task."; the holder's step
`dispatch_deliver` says who takes it from there: "Delivers your answer and its metadata to the
commissioner, who accepts it or sends it back."

## 5. Refusals

```
{ "reason": "...", "message": "...", "data": { "attempted": "...", "state": "...", "next": [...] } }
```

Every refusal names the call as the caller made it, the reason and the way out; a refusal about a
task the caller may see carries its state in `data.state`. A refusal decided before the task is
read -- an argument, a scope, a bracket kind -- names no state, so a caller learns no state of a
task it may not see. Every member of `data` means one thing on every service. A refusal of a
call's arguments names them in `data.offenders`: the paths of the arguments it concerns, one under
`fields` with its path (`fields.title`), and the position of one value of several counted from
zero (`apparatus[2]`, `fields.options[1]`). A refusal of a name the call does not declare carries
no `offenders`: it lists the arguments the level accepts under `data.accepts`, with their paths,
and a call this service does not have is answered with the calls there are under `data.accepts`
and no `attempted`. `CHILDREN_UNFINISHED` names the unfinished children of the bracket root
under `data.children`, each by its complete address, beside `data.confirmation`, the one handed
out now. `OPTION_UNOFFERED` names the field of the answer under `data.offenders` and points at the
read for the options. `CONFLICT_TOKEN_STALE` carries the task as it stands -- `data.address`,
`data.fields`, `data.conflict_token` and `data.service` (DEC-0041) -- and `UNEXPECTED_FAILURE` its
reference in `data.reference`. `VERB_NOT_CARRIED` lists the calls the address carries under
`data.accepts`. `NOT_FOUND` carries no `data` and the same bytes whatever its cause. The codes and
their statuses are the platform's table (service contract C8.7) where it names the condition; a
call fenced by the conflict token that arrives without one is the kernel's to refuse, after the
scope is resolved, as `CONFLICT_TOKEN_MISSING`.

Neither the message nor a member of `data` repeats the caller's input or the tenant's vocabulary
(DEC-0043 R18): no value the caller sent, no name a call does not declare, no scope, selector or
address the caller named, no declared bracket kind of a scope, no conflict token in the message,
no instant of a deferral. Where a statement needs one of these, the message points at the member
of `data` or the read that shows it. The message names the argument and the form it takes: each form an
argument takes is a message of its own, `invalid<Form>`. Each reason has one remedy, an instruction
worded as the act the caller takes, and so is the sentence of every step in `next`. The patterns
below are the bundle's templates: a placeholder is a call's name, an argument's path or name, a
declared bound, or a list of them, and nothing else (DEC-0043 R18).

| Reason | Remedy |
|---|---|
| `NOT_FOUND` | Check the scope and the address. |
| `TRANSITION_REFUSED` | Make one of the calls in data.next. |
| `ROLE_INSUFFICIENT` | Make one of the calls in data.next. |
| `RECEIPT_WRONG` | Pass the receipt from your latest claim. |
| `CHILDREN_UNFINISHED` | Repeat the call with data.confirmation, or finish the tasks in data.children first. |
| `OPTION_UNOFFERED` | Answer with one of the options the read of the task lists, or with free text where the question admits it. |
| `DEFERRAL_PENDING` | Take the task up after that instant, or take up another task now. |
| `PAGE_SIZE_REJECTED` | Send page_size as a whole number within the bounds the message names. |
| `CURSOR_MALFORMED` | Call the query again from the first page. |
| `CURSOR_FOREIGN` | Repeat the call with the arguments that handed the cursor out. |
| `SORT_UNDECLARED` | Send sort as id or created_at. |
| `ARGUMENT_UNKNOWN` | Use the names in data.accepts. |
| `ARGUMENT_MISSING` | Repeat the call with the argument in data.offenders. |
| `ARGUMENT_INVALID` | Send the argument in data.offenders in the form the message describes. |
| `HEADER_INVALID` | Send Kumbuka-Surface once with the value assistant, or leave it out. |
| `CONFLICT_TOKEN_STALE` | Read the task again and repeat the call with data.conflict_token. |
| `SCOPE_KIND_UNSUPPORTED` | Name a project or a global scope. |
| `SCOPE_READ_ONLY` | Ask whoever administers the membership of this scope for the write right. |
| `SCOPE_LOCKED` | Wait for the lock to be lifted, or read instead. |
| `IDEMPOTENCY_KEY_REUSED` | Choose a new key. |
| `PAYLOAD_MALFORMED` | Send the body as one JSON object. |
| `ADDRESS_MALFORMED` | Write the address in the form the declaration names for it. |
| `VERB_NOT_CARRIED` | Send one of the calls in data.accepts, on the route the message names. |
| `CLAIM_ABSENT` | Claim the task where it is open, then repeat the call with the new receipt. |
| `CLAIM_HELD` | Wait until its holder gives the task back, or take up another task. |
| `DRAW_EMPTY` | List the tasks with the query call. |
| `SELECTOR_UNDECLARED` | Name a bracket kind the scope declares. |
| `SELECTOR_WITHDRAWN` | Name a bracket kind the scope still offers. |
| `CONFLICT_TOKEN_MISSING` | Read the task, then repeat the call with its conflict token. |
| `UNEXPECTED_FAILURE` | Report the reference. |

| Message | Reason | Pattern |
|---|---|---|
| `notFound` | `NOT_FOUND` | `nothing is addressed here. Check the address, and that you are a member of the scope it names.` |
| `stateDoesNotAllow` | `TRANSITION_REFUSED` | `{call} is not possible in the task's present state, which data.state shows. You can make: {#each calls}{it}{#if it_hasNext}, {/if}{/each}.` |
| `stateNothingOpen` | `TRANSITION_REFUSED` | `{call} is not possible in the task's present state, which data.state shows, and no call is open to you on it now; data.waiting_for says what it waits for.` |
| `stateClosed` | `TRANSITION_REFUSED` | `{call} is not possible on this task: it is closed and finished. Nothing further can be done with it.` |
| `roleCommissioner` | `ROLE_INSUFFICIENT` | `{call} is the commissioner's call, and you take part in this task in another part.` |
| `roleExecutor` | `ROLE_INSUFFICIENT` | `{call} is an executor's call, and you take part here in another part.` |
| `roleHolder` | `ROLE_INSUFFICIENT` | `{call} is the holder's call, and you take part in this task in another part.` |
| `roleAuthor` | `ROLE_INSUFFICIENT` | `{call} is the call of the identity that wrote the text it supplements.` |
| `roleOtherThanTheDeliverer` | `ROLE_INSUFFICIENT` | `{call} is the commissioner's call, made by an identity other than the one that delivered the answer.` |
| `roleCapacity` | `ROLE_INSUFFICIENT` | `{call} needs a caller holding the commissioning or the executing capacity, and exactly one of them.` |
| `holderOther` | `CLAIM_HELD` | `{call} is the holder's call, and another executor holds this task.` |
| `holderNobody` | `CLAIM_ABSENT` | `{call} is the holder's call, and nobody holds this task.` |
| `holderLapsed` | `CLAIM_ABSENT` | `{call} is the holder's call, and your lease on this task ended.` |
| `textHolderOther` | `CLAIM_HELD` | `{call} reads the text of a task its executor holds, and another executor holds this one.` |
| `textHolderNobody` | `CLAIM_ABSENT` | `{call} reads the text of a task its executor holds, and nobody holds this one.` |
| `textHolderLapsed` | `CLAIM_ABSENT` | `{call} reads the text of a task its executor holds, and your lease on this one ended.` |
| `receiptWrong` | `RECEIPT_WRONG` | `{call} needs the receipt your claim handed out for this task: the receipt given is a different one.` |
| `childrenUnfinished` | `CHILDREN_UNFINISHED` | `{call} would close a bracket root while tasks of its bracket are unfinished; data.children lists them. Repeat the call with data.confirmation to withdraw them and close the root.` |
| `childrenConfirmationStale` | `CHILDREN_UNFINISHED` | `{call} carries a confirmation that no longer holds: the unfinished tasks of the bracket changed since it was handed out, and data.children lists them now. Repeat the call with the one in data.confirmation.` |
| `optionUnoffered` | `OPTION_UNOFFERED` | `{call} names an option the question does not offer; data.offenders names the field, and the read of the task lists the options.` |
| `freeTextUnoffered` | `OPTION_UNOFFERED` | `{call} answers in free text, and the question admits none; data.offenders names the field, and the read of the task lists the options.` |
| `deferralPending` | `DEFERRAL_PENDING` | `{call} is not possible yet: the task is deferred, and {read} shows until when, in fields.not_before.` |
| `nothingToTake` | `DRAW_EMPTY` | `Nothing that your patterns match can be taken up in this bracket kind right now: every such task is a draft, held, paused, delivered, closed or deferred.` |
| `claimDurationInvalid` | `ARGUMENT_INVALID` | `duration is not valid for {call}: it is a positive ISO-8601 duration such as PT2H.` |
| `pageSizeRejected` | `PAGE_SIZE_REJECTED` | `page_size is not valid for {call}: it is a whole number from 1 to {max}.` |
| `cursorMalformed` | `CURSOR_MALFORMED` | `after is not a cursor {query} handed out: it is the next_cursor of the previous page.` |
| `cursorForeign` | `CURSOR_FOREIGN` | `{call} carries a cursor of another listing: it was handed out for other values of {#each differs}{it}{#if it_hasNext}, {/if}{/each}. A cursor continues only the listing that handed it out.` |
| `sortUndeclared` | `SORT_UNDECLARED` | `sort is not valid for {call}: it takes id or created_at.` |
| `argumentUnknown` | `ARGUMENT_UNKNOWN` | `{call} takes these arguments: {#each accepts}{it}{#if it_hasNext}, {/if}{/each}.` |
| `argumentUnknownUnderFields` | `ARGUMENT_UNKNOWN` | `{call} takes these arguments under fields: {#each accepts}{it}{#if it_hasNext}, {/if}{/each}.` |
| `callUnknown` | `VERB_NOT_CARRIED` | `This service takes these calls: {#each accepts}{it}{#if it_hasNext}, {/if}{/each}.` |
| `argumentMissing` | `ARGUMENT_MISSING` | `{call} needs {path}.` |
| `argumentMissingOptions` | `ARGUMENT_MISSING` | `{call} needs fields.options, or fields.free_text set to true.` |
| `argumentMissingAnswer` | `ARGUMENT_MISSING` | `{call} needs fields.option or fields.text.` |
| `conflictTokenMissing` | `CONFLICT_TOKEN_MISSING` | `{call} is fenced by the conflict token, and it arrived without one.` |
| `argumentMissingChange` | `ARGUMENT_MISSING` | `{call} needs at least one of fields.title, fields.apparatus, fields.text and fields.metadata.` |
| `invalidFieldsObject` | `ARGUMENT_INVALID` | `{path} is not valid for {call}: it carries the values this call writes, and is an object.` |
| `invalidString` | `ARGUMENT_INVALID` | `{path} is not valid for {call}: it is a string.` |
| `invalidOneOf` | `ARGUMENT_INVALID` | `{path} is not valid for {call}: it is one of the values the description of the argument lists.` |
| `invalidListOfStrings` | `ARGUMENT_INVALID` | `{path} is not valid for {call}: it is a list of strings, even when it carries one value.` |
| `invalidObject` | `ARGUMENT_INVALID` | `{path} is not valid for {call}: it is an object.` |
| `invalidBoolean` | `ARGUMENT_INVALID` | `{path} is not valid for {call}: it is true or false.` |
| `invalidWholeNumber` | `ARGUMENT_INVALID` | `{path} is not valid for {call}: it is a whole number.` |
| `invalidParentElsewhere` | `ARGUMENT_INVALID` | `{path} is not valid for {call}: it names another scope or bracket kind than the call does, and a child is created in its root's bracket.` |
| `invalidParentNotARoot` | `ARGUMENT_INVALID` | `{path} is not valid for {call}: a parent is a bracket root, the <number>.0 of its bracket.` |
| `invalidApparatusPattern` | `ARGUMENT_INVALID` | `{path} is not valid for {call}: a pattern is one or more of A-Z, a-z, 0-9, '+', '-' and '*', and '*' is the only wildcard; the underscore and the percent sign carry a meaning of their own in the comparison and are excluded.` |
| `invalidApparatusUnbounded` | `ARGUMENT_INVALID` | `{path} is not valid for {call}: a pattern of nothing but '*' matches every apparatus, which is the blind draw the argument exists to refuse.` |
| `invalidStateFilter` | `ARGUMENT_INVALID` | `{path} is not valid for {call}: it is one or more of draft, open, active, on_hold, delivered and closed, comma-separated.` |
| `invalidBracketFilter` | `ARGUMENT_INVALID` | `{path} is not valid for {call}: it is one or more bracket numbers, comma-separated.` |
| `invalidAddressFilter` | `ARGUMENT_INVALID` | `{path} is not valid for {call}: it is one or more complete task addresses, comma-separated.` |
| `invalidAddressFilterElsewhere` | `ARGUMENT_INVALID` | `{path} is not valid for {call}: a listing filters the tasks of its own scope and bracket kind.` |
| `invalidValueFilter` | `ARGUMENT_INVALID` | `{path} is not valid for {call}: it is one or more values, comma-separated.` |
| `invalidLeaseEnd` | `ARGUMENT_INVALID` | `{path} is not valid for {call}: a lease ends before the year 294276.` |
| `invalidInstant` | `ARGUMENT_INVALID` | `{path} is not valid for {call}: an instant is written in ISO-8601 with its offset, such as 2026-10-07T09:00:00Z.` |
| `invalidInstantRange` | `ARGUMENT_INVALID` | `{path} is not valid for {call}: an instant lies from 0001-01-01T00:00:00Z up to +294276-01-01T00:00:00Z.` |
| `invalidAnswerOneOfTwo` | `ARGUMENT_INVALID` | `{path} is not valid for {call}: an answer names an option or carries text, not both.` |
| `invalidAddress` | `ADDRESS_MALFORMED` | `{path} is not valid for {call}: a task's address is written dispatch://<scope>/<selector>/<number>.<sub>.` |
| `invalidScope` | `ADDRESS_MALFORMED` | `{path} is not valid for {call}: a scope's name is a DNS label: lower case, digits and inner hyphens.` |
| `invalidSelector` | `ADDRESS_MALFORMED` | `{path} is not valid for {call}: a bracket kind's name is lower case, digits and inner hyphens.` |
| `invalidMetadata` | `ARGUMENT_INVALID` | `{path} is not valid for {call}: metadata carries your own keys, each value a string or a list of strings of at most 512 characters, and no URL carrying credentials.` |
| `invalidCurationTarget` | `ARGUMENT_INVALID` | `{path} is not valid for {call}: it names another task than this one.` |
| `invalidAddendumTarget` | `ARGUMENT_INVALID` | `{path} is not valid for {call}: it names a part the task carries a text of; the read of the task lists them under fields.texts.` |
| `invalidAddendaExhausted` | `ARGUMENT_INVALID` | `{path} is not valid for {call}: that text already carries addenda a to z, and a further letter would reissue one that exists.` |
| `invalidOnce` | `ARGUMENT_INVALID` | `{path} is not valid for {call}: it arrived twice: in the query or the body, and in the path or a header, and a call takes each argument once.` |
| `invalidBody` | `PAYLOAD_MALFORMED` | `{path} is not valid for {call}: a body is a JSON object of the call's arguments.` |
| `headerInvalid` | `HEADER_INVALID` | `The header Kumbuka-Surface takes the one value assistant, once, or is left out.` |
| `scopeReserved` | `ADDRESS_MALFORMED` | `The names declaration and whoami are the REST surface's own paths and are no scope; a scope is named otherwise.` |
| `invalidInRequest` | `ADDRESS_MALFORMED` | `{path} is not valid for this request: a task's address is written dispatch://<scope>/<selector>/<number>.<sub>.` |
| `valueInvalid` | `ARGUMENT_INVALID` | `A value given is not valid for {call}: it is a value this call takes.` |
| `valueGivenTwice` | `ARGUMENT_INVALID` | `A value given is not valid for {call}: it arrived twice, in the query or the body and in the path or a header, and a call takes each argument once.` |
| `conflictTokenStale` | `CONFLICT_TOKEN_STALE` | `{call} carries a conflict token that is not the one the task holds: it was changed since you read it. Its current token is in data.conflict_token.` |
| `selectorUnknown` | `SELECTOR_UNDECLARED` | `selector names a bracket kind this scope does not declare.` |
| `selectorWithdrawn` | `SELECTOR_WITHDRAWN` | `selector names a bracket kind this scope has withdrawn.` |
| `scopeKindUnsupported` | `SCOPE_KIND_UNSUPPORTED` | `The scope named is of a kind this service carries no tasks in; it carries tasks in project and global scopes.` |
| `scopeReadOnly` | `SCOPE_READ_ONLY` | `{call} writes, and you may read this scope without writing to it. Reading it is unaffected; the write right is granted with the membership.` |
| `scopeLocked` | `SCOPE_LOCKED` | `This scope is locked, so it refuses every write whatever your role. Reading it is unaffected. The lock is lifted where it was set.` |
| `idempotencyKeyReused` | `IDEMPOTENCY_KEY_REUSED` | `The idempotency key given was spent on a different call, or with different arguments, in this scope within the last 24 hours.` |
| `routeCreate` | `VERB_NOT_CARRIED` | `{call} is a call on a bracket kind: POST /api/<scope>/<selector>.` |
| `routeQuery` | `VERB_NOT_CARRIED` | `{call} is a call on a bracket kind: GET /api/<scope>/<selector>.` |
| `routeOnABracketKind` | `VERB_NOT_CARRIED` | `{call} is a call on a bracket kind: POST /api/<scope>/<selector>:{call}.` |
| `routeRead` | `VERB_NOT_CARRIED` | `{call} is a call on a task: GET /api/<scope>/<selector>/<number>.<sub>.` |
| `routeReadCall` | `VERB_NOT_CARRIED` | `{call} is a call on a task: GET /api/<scope>/<selector>/<number>.<sub>:{call}.` |
| `routeUpdate` | `VERB_NOT_CARRIED` | `{call} is a call on a task: PATCH /api/<scope>/<selector>/<number>.<sub>.` |
| `routeDelete` | `VERB_NOT_CARRIED` | `{call} is a call on a task: DELETE /api/<scope>/<selector>/<number>.<sub>.` |
| `routeOnATask` | `VERB_NOT_CARRIED` | `{call} is a call on a task: POST /api/<scope>/<selector>/<number>.<sub>:{call}.` |
| `unexpectedFailure` | `UNEXPECTED_FAILURE` | `{call} failed unexpectedly. This is a defect, not a rule. Nothing was changed. Report the reference in data.reference.` |
| `unexpectedFailureOfRequest` | `UNEXPECTED_FAILURE` | `The request failed unexpectedly. This is a defect, not a rule. Nothing was changed. Report the reference in data.reference.` |

## 6. The declaration

The service publishes its declaration on its REST surface at `GET /api/declaration` and nowhere
else, for any caller with a valid token and no role (ADR-0039, service contract C5.1, C5.2). Per
call it states the identity `dispatch_<verb>` and the REST name, the effect class by the router's
name (`ITEM_TRANSITION`, `COLLECTION_COMPOUND`, `CREATE`, `FIELD_WRITE`, `ITEM_DELETE`,
`ITEM_READ`, `ITEM_READ_CALL`, `COLLECTION_READ`), the role where the call is restricted, the
arguments with name, type, placement (`TOP` or `FIELDS`), requiredness, the default of an optional
one in its own JSON type (absent where it sets nothing), the closed set of values with a
description per value, the shape of the answer with its
members, the reasons a refusal of the call may name, its description and, for a transition, the
sentence of its step in `next`. Per reason it states the templates of its messages and its remedy.
The whole carries `version`, `sha256:` and the hex digest of the declaration's canonical text --
every member but the version, object members ordered by name, no whitespace -- which the answer
also carries as its `ETag`.

