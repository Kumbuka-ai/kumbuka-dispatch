---
type: contract-copy
title: "The dispatch service's verb surface: twenty-five calls, two sizes of answer, refusals that explain themselves"
written: 2026-10-06
sources:
  - "platform-specs aa42521, docs/architecture/targets/TAR-0004-the-dispatch-service-carries-commissioned-work-from-issue-to-acceptance.md, sections 3, 5, 6, 7"
  - "platform-specs aa42521, docs/concepts/concept-dispatch-store-kernel-and-surface.md, section 3"
---

# The dispatch service's verb surface

This document is the expectation the service's conformance tests read: the calls, what each one
says about itself, and the refusals. It is written by hand from TAR-0004 and from the concept
document named above, and never generated from the service's declaration. Where those two
documents fix a text, it stands here word for word and is marked so; where they leave the wording
to the service, the text below was written with this surface (dispatch 200.7) and is the service's
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
`fields`. Both levels are closed: an argument a call does not declare is refused by its name, and
nothing is written.

On REST the same calls carry the same names without the `dispatch_` prefix; the path carries the
scope and selector or the address, the `If-Match` header the conflict token, and the body the rest.

## 3. The calls

### 3.1 Transitions

**`dispatch_send`**
> Sends your draft: the task is frozen and becomes open, waiting for an executor to take it up.
> Moves the task from draft to open (not final). Call as the commissioner, with the conflict token
> of your last read.
>
> After this the commission's text cannot change; add to it with dispatch_annotate. Withdraw it
> with dispatch_withdraw.

**`dispatch_claim`** (concept section 3.5, word for word)
> Takes up one open task and makes you its holder. Moves the task from open to active (not final).
> Call as an executor, naming the task by its address.
>
> Returns a receipt and the end of your hold. Keep the receipt: every later call on this task
> needs it.
>
> The answer does NOT contain the task's text. Read it next with dispatch_read_text, part
> "dispatch", before you start working.
>
> Your hold lasts 30 minutes unless you state a duration; extend it with dispatch_renew.

**`dispatch_claim_next`** (concept section 3.5, word for word)
> Takes up the next open task addressed to you, without naming one; use dispatch_claim when you
> know the address. Moves the task from open to active (not final). Call as an executor with
> scope, selector and apparatus.
>
> Returns the address, a receipt and the end of your hold. Keep the receipt: every later call on
> this task needs it.
>
> The answer does NOT contain the task's text. Read it next with dispatch_read_text, part
> "dispatch", before you start working.
>
> If nothing matches, nothing is taken.

**`dispatch_release`**
> Gives a task you hold back: it is open again for any executor. Moves the task from active to
> open (not final). Call as its holder, with your receipt. Use it when you lose control over the
> run.
>
> To give it back until a later instant use dispatch_defer; if the work cannot be done,
> dispatch_fail.

**`dispatch_defer`**
> Gives a task you hold back until an instant you name; nobody can take it up before then. Moves
> the task from active to open (not final). Call as its holder, with your receipt. Use it for a
> technical abort that a later attempt may overcome.
>
> dispatch_read and dispatch_query show the instant.

**`dispatch_renew`**
> Extends your hold on a task you are working on. The task stays active (not final). Call as its
> holder, with your receipt.
>
> The answer carries the new end of your hold.

**`dispatch_ask`**
> Pauses a task you hold and asks the commissioner a question, with answer options, free text
> admitted, or both. Moves the task from active to on_hold (not final). Call as its holder, with
> your receipt.
>
> You keep the task. The commissioner's dispatch_answer makes it active again with a fresh
> 30-minute hold; read the answer with dispatch_read_text, part "thread".

**`dispatch_answer`**
> Answers the question the holder asked: name one of its options, or give text where free text is
> admitted. Moves the task from on_hold to active (not final); the holder continues with a fresh
> 30-minute hold. Call as the commissioner, with the conflict token of your last read.
>
> Read the question first with dispatch_read_text, part "thread".

**`dispatch_hold`**
> Pauses a task you hold while it waits on a dependency or on something external. Moves the task
> from active to on_hold (not final). Call as its holder, with your receipt. You keep the task;
> the hold does not run out while it is paused.
>
> Continue with dispatch_resume.

**`dispatch_resume`**
> Continues a task you paused with dispatch_hold. Moves the task from on_hold to active (not
> final). Call as its holder, with your receipt.
>
> A question you asked is continued by the commissioner's dispatch_answer, not by this call.

**`dispatch_deliver`**
> Delivers your answer, its text and your metadata in one act. Moves the task from active to
> delivered (not final). Call as its holder, with your receipt.
>
> You keep the task while the commissioner reviews it. It closes when accepted, or comes back to
> you with a remark through dispatch_rework.

**`dispatch_rework`**
> Sends a delivered answer back to its holder with a remark saying what to change. Moves the task
> from delivered to active (not final); the holder continues with a fresh 30-minute hold. Call as
> the commissioner, with the conflict token of your last read.
>
> Read the answer first with dispatch_read_text, part "return".

**`dispatch_accept`**
> Accepts the delivered answer and closes the task. Moves the task from delivered to closed,
> outcome accepted (final). Call as the commissioner, with the conflict token of your last read;
> the identity that delivered cannot accept.
>
> Read the answer first with dispatch_read_text, part "return". On a bracket root with unfinished
> children, repeat with the confirmation the refusal hands out.

**`dispatch_reject`**
> Declines a commission you were offered, with a remark saying why. Moves the task from open to
> closed, outcome rejected (final). Call as an executor who could take it up; no claim is needed.

**`dispatch_fail`**
> Closes a task you hold as failed, with a remark saying why. Moves the task from active or
> on_hold to closed, outcome failed (final). Call as its holder, with your receipt. For a
> technical abort use dispatch_defer; to hand the task back, dispatch_release.
>
> On a bracket root with unfinished children, repeat with the confirmation the refusal hands out.

**`dispatch_withdraw`**
> Withdraws a commission that is no longer wanted. Moves the task from open, active, on_hold or
> delivered to closed, outcome withdrawn (final). Call as the commissioner, with the conflict
> token of your last read.
>
> On a bracket root with unfinished children the first call is refused and names them; repeat it
> with the confirmation it hands out to withdraw them and the root together.

### 3.2 Calls that are not transitions

**`dispatch_create`**
> Creates a task as a draft, for you to complete and send. Without parent it opens a new bracket
> and the task is its root; with parent it adds a child to that bracket. The service allocates the
> number. Call as a commissioner; you become the task's commissioner.
>
> Nobody is offered the draft until you send it with dispatch_send. Change it with
> dispatch_update, or discard it with dispatch_delete.

**`dispatch_update`**
> Changes your draft: its title, apparatus, text or metadata; only what you give changes. Call as
> the commissioner, with the conflict token of your last read. The task stays a draft.
>
> A sent task cannot be changed; add to its text with dispatch_annotate.

**`dispatch_delete`**
> Deletes your draft outright; it leaves no trace. Call as the commissioner, with the conflict
> token of your last read. Only a draft can be deleted; a sent task is withdrawn with
> dispatch_withdraw.
>
> The answer carries the address the draft had, and nothing else.

**`dispatch_read`**
> Reads the head of one task: its state and attributes, whether you, someone else or nobody holds
> it, its metadata, which texts it has, and the calls open to you with what each does, or what the
> task waits for.
>
> The answer does NOT contain the task's text; read that with dispatch_read_text.

**`dispatch_read_text`**
> Reads one part of a task's text: "dispatch" (the commission), "return" (the valid answer),
> "thread" (questions, answers, remarks and earlier answers, in order) or "addenda". One part per
> call.
>
> The commissioner reads every part in every state; an executor reads only a task it holds. For
> "dispatch" and "return" the answer says how many addenda the text has.

**`dispatch_query`**
> Lists the tasks of one bracket kind in a scope, without their text: each with its state,
> attributes and the calls open to you. Narrow by state, apparatus, bracket or address;
> comma-separated values are alternatives, and an undeclared filter is refused.
>
> The list follows the address order, stops at the page bound and says whether it was cut.

**`dispatch_annotate`**
> Adds an addendum to a text of a sent task: a supplement with its own time and author that cannot
> be removed. Name the part it supplements. Call as the identity that wrote that text; the task's
> state does not change.
>
> Read addenda with dispatch_read_text, part "addenda".

**`dispatch_relate`**
> Records the object a closed task was curated into: another task you can see, in any scope and
> bracket kind, never the task itself. Call as the commissioner, with the conflict token of your
> last read. The task stays closed.
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
`dispatch_query` answers `{ "tasks": [...], "cut": ... }`. `dispatch_delete` answers the address
and nothing else. No answer names the identity of an actor.

Text comes only from `dispatch_read_text`, one part per call. No other call, no listing and no
refusal carries a text of a task.

`next` lists the transitions open to the caller, each with one sentence. Right after a claim, its
first entry for the holder is (concept section 3.5, word for word):

```
{ "call": "dispatch_read_text",
  "does": "Reads the commission. Do this first: the claim did not return it." }
```

and for the commissioner of a delivered task, before `dispatch_accept`, it is `dispatch_read_text`
as well. Where nothing is open to the caller, `waiting_for` says what the task waits for.

## 5. Refusals

```
{ "reason": "...", "message": "...", "data": { "attempted": "...", "state": "...", "next": [...] } }
```

Every refusal names the call as the caller made it, the state, the reason and the way out. A
refusal decided before the task is read -- an argument, a scope, a bracket kind -- names no state,
so a caller learns no state of a task it may not see. `NOT_FOUND` carries no `data` and the same
bytes whatever its cause.

| Reason | Message pattern | Remedy |
|---|---|---|
| `NOT_FOUND` | `nothing is addressed here. Check the address, and that you are a member of the scope it names.` | check scope and address |
| `STATE_DOES_NOT_ALLOW` | `{call} is not possible on {address}: it is {state}, and {call} applies {applies}. You can: {calls}.` | the calls in data.next |
| `ROLE_DOES_NOT_ALLOW` | `{call} can only be made by {role}. You take part in {address} as {participation}.` | the calls in data.next |
| `NOT_THE_HOLDER` | `{call} is the holder's call, and you do not hold {address}: {why}.` | take the task up first, or wait for its holder |
| `RECEIPT_WRONG` | `{call} needs the receipt your claim handed out for {address}: the receipt given is not the one it holds.` | pass the receipt from the latest claim |
| `CHILDREN_NOT_FINISHED` | `{call} would close the bracket root {address} while {count} task(s) of the bracket are unfinished: {offenders}. {confirm}` | repeat the call with data.confirmation, or finish the offenders first |
| `DEFERRAL_PENDING` | `{call} is not possible on {address} before {not_before}: the task was deferred until then.` | take it up after that instant, or another task now |
| `NOTHING_TO_TAKE` | `Nothing in {collection} that your patterns match can be taken up right now: every such task is a draft, held, paused, delivered, closed or deferred.` | list the tasks with the query call |
| `CLAIM_DURATION_INVALID` | `The duration {value} is not a positive ISO-8601 duration such as PT2H.` | correct the duration |
| `ARGUMENT_UNKNOWN` | `{subject} has no {kind} named {name}. It has: {known}.` | correct the name |
| `ARGUMENT_MISSING` | `{call} needs {name}: {what}.` | supply it |
| `ARGUMENT_INVALID` | `{name} = {value} is not valid for {call}: {why}.` | correct the value |
| `CONFLICT_TOKEN_STALE` | `{call} on {address} carries a conflict token that is not the one it holds: it was changed since you read it. Its current token is {current}.` | read the task again and repeat with data.conflict_token |
| `SELECTOR_UNKNOWN` | `{selector} is not a bracket kind declared in scope {scope}. Declared: {declared}.` | use a declared one |
| `SCOPE_KIND_UNSUPPORTED` | `{scope} is a {kind} scope, and this service does not carry tasks in one. Name a project or a global scope instead.` | name a project or a global scope |
| `SCOPE_READ_ONLY` | `{call} writes, and you may read {scope} without writing to it. Reading it is unaffected; the write right is granted with the membership.` | ask whoever administers the membership of this scope |
| `SCOPE_LOCKED` | `{scope} is locked, so it refuses every write whatever your role. Reading it is unaffected. The lock is lifted where it was set.` | wait for the lock to be lifted, or read instead |
| `IDEMPOTENCY_KEY_REUSED` | `The idempotency key {key} was spent on a different call, or with different arguments, in scope {scope} within the last 24 hours.` | choose a new key |
| `CALL_NOT_AT_THIS_ADDRESS` | `{call} cannot be made on {address}: it applies to {applies}.` | address the call where it applies |
| `UNEXPECTED_FAILURE` | `{call} on {address} failed unexpectedly. This is a defect, not a rule. Nothing was changed. Report reference {reference}.` | report the reference |

A closed task refuses with the terminal variant of `STATE_DOES_NOT_ALLOW`:
`{call} is not possible on {address}: it is {state} and finished. Nothing further can be done with it.`
