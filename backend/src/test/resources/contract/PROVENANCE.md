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
`CHILDREN_NOT_FINISHED`, which concerns no argument, names the unfinished
children under `data.children` and no longer under `data.offenders`; neither
a message nor a member of `data` returns a value the caller sent, so the
patterns that carried `{value}`, `{key}` or `{selector}` name the argument and
the form it takes instead; every remedy is an instruction, worded as the act
the caller takes, and so is the sentence of every step in `next`. The
descriptions of the calls are left as they were: their wording follows a
decision of its own.

## What is word for word, and what is not

The descriptions of `dispatch_claim` and `dispatch_claim_next`, the description
of the argument `apparatus` and the first entry of `next` after a claim are
concept section 3.5 word for word; the line breaks of the concept's code blocks
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
