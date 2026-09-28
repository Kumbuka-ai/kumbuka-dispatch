#!/usr/bin/env python3
"""Generate the steering-corpus import for dispatch.exchange."""
import os, re, json, sys, collections, datetime

# Paths. The defaults assume this repository sits beside `steering` and
# `chat-context` in the platform-dev superrepo; override with KB_WORKSPACE.
WS      = os.environ.get("KB_WORKSPACE") or os.path.abspath(
              os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
STEER   = os.environ.get("KB_STEERING") or os.path.join(WS, "steering")
LEGACY  = os.environ.get("KB_LEGACY")   or os.path.join(WS, "chat-context", "sprints")
OUT     = os.environ.get("KB_OUT")      or os.path.join(
              os.path.dirname(os.path.abspath(__file__)), "001-import-steering-corpus.sql")

TENANT  = "a7b072e4-89dd-473e-acc0-91a98a8bae7a"
SCOPE   = "0845a29c-9b55-4405-a445-7849416731b8"
SPRINT_CUT = 172
CIRCLE_NEXT = {"sprint": 175, "satellite": 15}   # 173/174 are issued, see README

# --------------------------------------------------------------------------
# The backfill selection. Additive: with KB_ONLY unset, nothing below behaves
# differently and 001 comes out exactly as it did before this block existed.
#
# KB_ONLY names the brackets to emit, as `<selector>:<number>[,<number>...]`,
# several groups separated by `;`. A selection turns this generator from the
# first import into a BACKFILL run: it reads only the named brackets, ignores
# SPRINT_CUT and the legacy stock entirely, and emits the backfill form
# instead of 001's.
#
# WHY THE SELECTION CARRIES ITS OWN EMITTER
#
# Not taste -- the schema. 001 was generated against Flyway V9 and the chain
# has since moved five migrations on: V10 renamed `body`, `handover_body` and
# `handover_metadata` to `dispatch_body`, `return_body` and `return_metadata`;
# V11 folded `dispatch.number_circle` into `dispatch.selector.next_number` and
# dropped the table; V12 replaced `exchange.selector` (TEXT) with
# `exchange.selector_id` (BIGINT, foreign key). A backfill has to hit today's
# schema, and 001 has to stay the record of what was run on 2026-09-06. One
# emitter cannot be both, so the selection brings its own and 001's is left
# untouched.
#
# The two forms also differ on purpose in three places, each of which the
# backfill's own header states: the tenant is bound with SET LOCAL inside the
# transaction rather than with a session-level SET; an address that is already
# taken stops the run loudly instead of being skipped by WHERE NOT EXISTS; and
# no counter is read or written at all.
# --------------------------------------------------------------------------
def parse_only(spec):
    """'sprint:173,174' -> {('sprint', 173), ('sprint', 174)}; None when unset."""
    if not spec:
        return None
    sel = set()
    for group in spec.split(";"):
        group = group.strip()
        if not group:
            continue
        if ":" not in group:
            sys.exit(f"KB_ONLY: '{group}' ist keine Auswahl der Form <selektor>:<nummer>[,...]")
        name, nums = group.split(":", 1)
        name = name.strip()
        if not re.match(r"^[a-z][a-z0-9-]{0,62}$", name):
            sys.exit(f"KB_ONLY: '{name}' ist kein Selektorname (ck_selector_name)")
        for n in nums.split(","):
            n = n.strip()
            if not n.isdigit() or int(n) < 1:
                sys.exit(f"KB_ONLY: '{n}' ist keine Klammernummer (ck_exchange_number)")
            sel.add((name, int(n)))
    if not sel:
        sys.exit("KB_ONLY ist gesetzt, benennt aber keine Klammer")
    return sel

ONLY = parse_only(os.environ.get("KB_ONLY"))
if ONLY is not None and not os.environ.get("KB_OUT"):
    OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                       "002-backfill-steering-corpus.sql")

LEGACY_DROP = {"sprint-60-chore-68-ee-server-migration.md"}
LEGACY_OVERRIDE = {
    # file: (field, value, basis)
    "sprint-36-recovery-doctrine-and-bahn2-dispatches.md":
        {"apparatus": ("concept", "Prosa: 'Concept/Desktop-Session.'")},
    "sprint-104-worklist-schema-v2-reform.md":
        {"apparatus": ("concept", "keine Erklaerung im Kopf; Prosa nennt den Arbeitsschritt 'Concept/Desktop'"),
         "number":    (104, "Dateiname; das sprint-Feld fehlt")},
    "sprint-134-logbook-spec-and-steering-order.md":
        {"apparatus": ("concept", "keine Erklaerung im Kopf; Sitzungsrekord der Konzept-Apparatur")},
    "sprint-135-logbook-object-model-and-service-build.md":
        {"apparatus": ("concept", "keine Erklaerung im Kopf; Prosa beschreibt die Sitzung selbst als Konzept-Apparatur")},
}
LEGACY_ADDRESS = {   # file -> (number, sub, suffix), basis
    "sprint-10a-dogfooding-scope-isolation.md":            (10, 0, "a"),
    "sprint-19.A-dogfood-11-policy-on-success.md":         (19, 1, None),
    "sprint-31.1-templates-schema-language-retrofit.md":   (31, 1, None),
    "sprint-60.1-it1-v17-deploy-it3-ops-console-deploy.md":(60, 1, None),
}

MARK_LEGACY = ("Dieser Austausch stammt aus der Zeit vor dem Dispatch-Return-Mechanismus.\n"
               "Ein Auftrag wurde nie geschrieben; der Sprint wurde ausschliesslich als\n"
               "Rekord festgehalten. Die Prosa des Rekords steht in der Antworthaelfte.\n"
               "Quelle: {src}\n")
MARK_LOST   = ("Der Auftrag zu diesem Austausch existiert in der Quelle nicht. Er wurde\n"
               "nicht weggelassen, sondern ging verloren: das Quellverzeichnis enthaelt\n"
               "nur den Ruecklauf. Siehe constraint.create-overwrites-past-the-gate und\n"
               "BUG-48.\nQuelle: {src}\n")
MARK_ADD    = ("Nachtrag zu {parent}. Ein Nachtrag traegt keinen eigenen Auftrag; er\n"
               "haengt an dem Austausch, den er korrigiert.\nQuelle: {src}\n")

problems = []
def stop(where, what): problems.append((where, what))

def split_fm(txt):
    """Return (frontmatter dict, body). Tolerates a missing opening fence."""
    lines = txt.split("\n")
    fenced = bool(lines) and lines[0].strip() == "---"
    start = 1 if fenced else 0
    end = None
    for i in range(start, min(len(lines), 80)):
        if lines[i].strip() == "---":
            end = i; break
    if end is None:
        return None, txt
    if not fenced:
        # No opening fence: only believe it is frontmatter when every line up to
        # the closing one is a key or a list item. Otherwise that '---' is a
        # horizontal rule and the document has no frontmatter at all.
        for line in lines[start:end]:
            if not line.strip(): continue
            if not (re.match(r"^[A-Za-z_-]+:", line) or line.lstrip().startswith("- ")):
                return None, txt
    fm, key = {}, None
    for line in lines[start:end]:
        if not line.strip(): continue
        m = re.match(r"^([A-Za-z_-]+):\s*(.*)$", line)
        if m:
            key, val = m.group(1), m.group(2).strip()
            if val.startswith("[") and val.endswith("]"):
                fm[key] = [x.strip().strip('"\'') for x in val[1:-1].split(",") if x.strip()]
            elif val == "":
                fm[key] = []
            else:
                fm[key] = val.strip('"\'')
        elif line.lstrip().startswith("- ") and key is not None:
            if not isinstance(fm.get(key), list): fm[key] = []
            fm[key].append(line.lstrip()[2:].strip().strip('"\''))
    return fm, "\n".join(lines[end+1:]).lstrip("\n")

def h1_of(body):
    m = re.search(r"^#\s+(.+)$", body, re.M)
    return m.group(1).strip() if m else None

def prose_date(body):
    m = re.search(r"^Dat[ue]m?:?\s*(\d{4}-\d{2}-\d{2})", body, re.M) or \
        re.search(r"(\d{4}-\d{2}-\d{2})", body)
    return m.group(1) if m else None

def prose_apparatus(body):
    head = "\n".join(body.split("\n")[:6])
    m = re.search(r"Apparat\w*:?\s*([A-Za-zÄÖÜäöü/]+)", head)
    if m:
        v = m.group(1).lower()
        for cand in ("concept", "code", "design"):
            if v.startswith(cand): return cand
    if re.search(r"\bConcept/Desktop\b", head): return "concept"
    return None

# --------------------------------------------------------------------------
# 1. read the steering corpus (the dispatch/return era)
# --------------------------------------------------------------------------
OBJ = re.compile(r"^(SPRINT|SATELLITE)_(\d+)\.(\d+)([a-z]?)-(dispatch|return)\.md$")
ROOTS = (("sprints", "sprint"), ("satellite", "satellite"))
ex = {}                      # (selector, number, sub, suffix) -> {role: rec}
src_files = collections.Counter()

for rootdir, selector in ROOTS:
    base = os.path.join(STEER, rootdir)
    for d in sorted(os.listdir(base)):
        p = os.path.join(base, d)
        if not os.path.isdir(p): continue
        for fn in sorted(os.listdir(p)):
            if not fn.endswith(".md"): continue
            m = OBJ.match(fn)
            if not m:
                stop(f"{rootdir}/{d}/{fn}", "Dateiname passt nicht auf das Objektmuster"); continue
            _, num, sub, suffix, role = m.groups()
            num, sub = int(num), int(sub)
            # Under a selection the cut does not apply: the whole point of a
            # backfill is to reach brackets the cut excluded. Without one this
            # is the line it always was.
            if ONLY is not None:
                if (selector, num) not in ONLY: continue
            elif selector == "sprint" and num > SPRINT_CUT: continue
            if str(num) != d:
                stop(f"{rootdir}/{d}/{fn}", f"Verzeichnisname {d} != Nummer {num}"); continue
            fm, body = split_fm(open(os.path.join(p, fn), encoding="utf-8").read())
            if fm is None:
                stop(f"{rootdir}/{d}/{fn}", "kein Frontmatter"); continue
            if fm.get("role") != role:
                stop(f"{rootdir}/{d}/{fn}", f"role-Feld {fm.get('role')} != Dateiname"); continue
            if str(fm.get("sprint")) != str(num):
                stop(f"{rootdir}/{d}/{fn}", f"sprint-Feld {fm.get('sprint')} != {num}"); continue
            src_files[selector] += 1
            ex.setdefault((selector, num, sub, suffix or None), {})[role] = dict(
                fm=fm, body=body, src=f"{rootdir}/{d}/{fn}")

# --------------------------------------------------------------------------
# 2. read the legacy records (returns without a commission)
# --------------------------------------------------------------------------
LEG = re.compile(r"^sprint-(\d+)([a-zA-Z])?(?:\.(\w+))?-.*\.md$")
legacy_notes = []
# The legacy records belong to the first import alone: they are returns without
# a commission, from before the dispatch/return era, and no backfill selection
# names them. Under a selection the loop is not entered at all.
for fn in (sorted(os.listdir(LEGACY)) if ONLY is None else []):
    if not fn.endswith(".md") or fn == "README.md": continue
    if fn in LEGACY_DROP:
        legacy_notes.append((fn, "ausgelassen: Adresskollision auf Sprint 60, Operatorentscheid")); continue
    txt = open(os.path.join(LEGACY, fn), encoding="utf-8").read()
    fm, body = split_fm(txt)
    if fm is None: fm, body = {}, txt
    ov = LEGACY_OVERRIDE.get(fn, {})

    if fn in LEGACY_ADDRESS:
        num, sub, suffix = LEGACY_ADDRESS[fn]
        legacy_notes.append((fn, f"Adresse gesetzt: {num}.{sub}{suffix or ''}"))
    else:
        m = LEG.match(fn)
        if not m: stop(f"chat-context/sprints/{fn}", "Dateiname passt nicht"); continue
        num, sub, suffix = int(m.group(1)), 0, None
    if fn in LEGACY_ADDRESS:
        pass                      # the address was set by hand above
    elif "number" in ov:
        num = ov["number"][0]; legacy_notes.append((fn, "Nummer aus dem Dateinamen: " + ov["number"][1]))
    elif fm.get("sprint") is not None and str(fm["sprint"]).split(".")[0].lstrip("0") not in (str(num), ""):
        stop(f"chat-context/sprints/{fn}", f"sprint-Feld {fm.get('sprint')} != Dateiname {num}"); continue

    title = fm.get("title") or h1_of(body)
    if not title: stop(f"chat-context/sprints/{fn}", "kein Titel und keine H1"); continue
    if not fm.get("title"): legacy_notes.append((fn, "Titel aus der H1-Ueberschrift"))

    app = ov["apparatus"][0] if "apparatus" in ov else (fm.get("apparatus") or prose_apparatus(body))
    if "apparatus" in ov: legacy_notes.append((fn, "apparatus: " + ov["apparatus"][1]))
    elif not fm.get("apparatus") and app: legacy_notes.append((fn, "apparatus aus der Prosazeile"))
    if not app: stop(f"chat-context/sprints/{fn}", "kein apparatus ableitbar"); continue

    date = fm.get("date") or prose_date(body)
    if not date: stop(f"chat-context/sprints/{fn}", "kein Datum ableitbar"); continue
    if not fm.get("date"): legacy_notes.append((fn, "Datum aus der Prosazeile"))

    st = fm.get("status")
    if st not in ("closed", "consumed"):
        legacy_notes.append((fn, f"Status gesetzt auf closed (Quelle: {st!r})")); st = "closed"

    key = ("sprint", num, sub, suffix)
    if key in ex: stop(f"chat-context/sprints/{fn}", f"Adresse {key} doppelt belegt"); continue
    src_files["legacy"] += 1
    ex[key] = {"legacy": dict(fm=fm, body=body, src=f"chat-context/sprints/{fn}",
                              title=title, apparatus=app, date=date, status=st)}

# --------------------------------------------------------------------------
# 3. build the rows
# --------------------------------------------------------------------------
def collapse(sts):
    if "consumed" in sts: return "consumed", False
    if "closed"   in sts: return "closed", False
    return "closed", True

rows, forced, status_map = [], [], collections.Counter()
for key in sorted(ex, key=lambda k: (k[0], k[1], k[2], k[3] or "")):
    selector, num, sub, suffix = key
    roles = ex[key]
    if "legacy" in roles:
        L = roles["legacy"]
        dmeta, hmeta = None, {"source": L["src"]}
        if L["fm"].get("tracks"): hmeta["tracks"] = L["fm"]["tracks"]
        rows.append(dict(selector=selector, number=num, sub=sub, suffix=suffix,
            status=L["status"], title=L["title"], body=MARK_LEGACY.format(src=L["src"]),
            apparatus=L["apparatus"], date=L["date"], sent=L["date"],
            handover=L["body"], ratified=L["date"], dmeta=dmeta, hmeta=hmeta))
        status_map[("(kein Auftrag)", L["fm"].get("status"), L["status"])] += 1
        continue
    d, r = roles.get("dispatch"), roles.get("return")
    lead = d or r
    title, app, date = lead["fm"].get("title"), lead["fm"].get("apparatus"), lead["fm"].get("date")
    for name, v in (("title", title), ("apparatus", app), ("date", date)):
        if not v: stop(lead["src"], f"Pflichtfeld {name} fehlt")
    if d:
        body = d["body"]
        dmeta = {"source": d["src"]}
        if d["fm"].get("task"):    dmeta["task"] = d["fm"]["task"]
        if d["fm"].get("extends"): dmeta["extends"] = d["fm"]["extends"]
        if d["fm"].get("tracks"):  dmeta["tracks"] = d["fm"]["tracks"]
    else:
        body = (MARK_ADD.format(parent=f"{selector.upper()}_{num}.{sub}", src=r["src"])
                if suffix else MARK_LOST.format(src=r["src"]))
        dmeta = None
    hmeta = None
    if r:
        hmeta = {"source": r["src"]}
        if r["fm"].get("tracks"): hmeta["tracks"] = r["fm"]["tracks"]
    sts = [x["fm"].get("status") for x in (d, r) if x]
    st, was_forced = collapse(sts)
    if was_forced: forced.append((f"{selector} {num}.{sub}{suffix or ''}", sts))
    status_map[(sts[0] if d else "(kein Auftrag)", (r["fm"].get("status") if r else "(kein Ruecklauf)"), st)] += 1
    rows.append(dict(selector=selector, number=num, sub=sub, suffix=suffix, status=st,
        title=title, body=body, apparatus=app, date=date, sent=date,
        handover=(r["body"] if r else None), ratified=(r["fm"].get("date") if r else None),
        dmeta=dmeta, hmeta=hmeta))

if problems:
    print("STOPPBEDINGUNG -- der Lauf hat nichts geschrieben:", file=sys.stderr)
    for w, x in problems: print(f"  {w}: {x}", file=sys.stderr)
    sys.exit(2)

TAG = "kbimp"
def lit(s):
    if s is None: return "NULL"
    assert f"${TAG}$" not in s, "Dollar-Tag kollidiert mit dem Inhalt"
    return f"${TAG}${s}${TAG}$"
def jsonlit(o):
    return "NULL" if not o else lit(json.dumps(o, ensure_ascii=False, sort_keys=True)) + "::jsonb"

per_sel = collections.Counter(r["selector"] for r in rows)
maxnum  = collections.defaultdict(int)
for r in rows: maxnum[r["selector"]] = max(maxnum[r["selector"]], r["number"])

# --------------------------------------------------------------------------
# 3b. The backfill form, emitted only under a KB_ONLY selection.
#
# Everything below runs instead of section 4, never beside it: the run ends
# here. Section 4 and the file it writes are therefore untouched by this
# block's existence, which is what lets 001 stay byte-identical.
# --------------------------------------------------------------------------
if ONLY is not None:
    # ----------------------------------------------------------------------
    # The halting condition, stated as the dispatch states it: a bracket is
    # emitted only when its source is complete -- four files, root 0 and child
    # 1, each carrying a dispatch and a return. A bracket that does not match
    # is not emitted, and the run writes nothing at all rather than emitting
    # the brackets that happen to be whole. Partial output is the one shape
    # that would be worse than no output: it looks like a finished backfill.
    # ----------------------------------------------------------------------
    per_bracket = collections.defaultdict(dict)
    for key in ex:
        per_bracket[(key[0], key[1])][(key[2], key[3])] = ex[key]

    refused = []
    for want in sorted(ONLY):
        got = per_bracket.get(want)
        if not got:
            refused.append((want, "keine Quelldatei unter dieser Adresse gefunden"))
            continue
        files = sum(len(roles) for roles in got.values())
        subs  = sorted(s for s, suf in got)
        halves = [f"{want[1]}.{s}{suf or ''}: " + ", ".join(sorted(roles))
                  for (s, suf), roles in sorted(got.items(), key=lambda kv: (kv[0][0], kv[0][1] or ""))
                  if not ("dispatch" in roles and "return" in roles)]
        if subs != [0, 1] or files != 4 or halves:
            why = f"erwartet vier Dateien in den Unternummern 0 und 1, gefunden {files} in {subs}"
            if halves:
                why += "; ohne beide Rollen -- " + " | ".join(halves)
            refused.append((want, why))

    if refused:
        print("STOPPBEDINGUNG -- der Nachtrag hat nichts geschrieben:", file=sys.stderr)
        for want, why in refused:
            print(f"  {want[0]} {want[1]}: {why}", file=sys.stderr)
        sys.exit(2)

    addr    = [(r["selector"], r["number"], r["sub"], r["suffix"]) for r in rows]
    sel_set = sorted({a[0] for a in addr})
    values  = ", ".join(f"('{s}', {n}, {b})" for s, n, b, _ in addr)
    brackets = ", ".join(f"{s} {n}" for s, n in sorted(ONLY))

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    o = open(OUT, "w", encoding="utf-8")
    W = o.write
    gen = datetime.date.today().isoformat()

    W(f"""-- ===========================================================================
-- {os.path.basename(OUT)}
--
-- The backfill of the brackets the first import left out: {brackets}.
-- {len(rows)} exchanges into dispatch.exchange, and nothing else.
--
-- Generated on {gen} by migration/generate-import.py under
-- KB_ONLY={os.environ.get('KB_ONLY')}. Do not hand-edit: the script is a
-- projection of the source corpus, and a hand edit makes the two disagree
-- without saying so. Change the generator and regenerate.
--
-- OUTSIDE EVERY FLYWAY CHAIN, like 001 beside it. Nothing in this repository
-- runs it, no build step invokes it, and the file as committed ends in
-- ROLLBACK. The operator runs it; the rehearsal and the real run differ by
-- that one last word and by nothing else.
--
-- WHY THESE BRACKETS WERE MISSING. Not because they did not exist: the first
-- import excluded them by scope and set the sprint counter to 175 so that
-- neither address would ever be handed out. Both have stood unissued since.
-- They cannot be reached over the verb surface either -- `create` allocates
-- the number transactionally and accepts none, and there is no delete verb to
-- take a wrong allocation back. The script path is the only one.
--
-- WHAT THIS SCRIPT IS NOT. It creates nothing, alters nothing, drops nothing.
-- It touches no existing row: no UPDATE, no DELETE, no ON CONFLICT DO UPDATE.
-- It does not declare a selector -- a missing selector means the stock is not
-- the one this was generated against, and that stops the run rather than
-- being repaired by it.
--
-- THREE DELIBERATE DIFFERENCES FROM 001, each measurable in what follows.
--
--   1. THE TENANT IS BOUND WITH `SET LOCAL`, INSIDE THE TRANSACTION. 001 used
--      a session-level SET, which outlives the script in the psql session
--      that ran it. SET LOCAL reverts at COMMIT or at ROLLBACK, so the
--      binding cannot leak into whatever the operator types next in the same
--      session. It is load-bearing either way: all tables of this schema
--      carry ENABLE plus FORCE ROW LEVEL SECURITY, so the policy binds the
--      owner too, and an unbound run sees an empty scope.
--
--   2. AN ADDRESS THAT IS ALREADY TAKEN STOPS THE RUN, LOUDLY. 001 guarded
--      every insert with WHERE NOT EXISTS and skipped silently, which is the
--      right shape for an import that may be re-run to completion. It is the
--      wrong shape here. `uq_exchange_address` spans `addendum_suffix`, which
--      is NULL on every row this file writes, and a UNIQUE constraint without
--      NULLS NOT DISTINCT treats those NULLs as distinct -- so the constraint
--      does NOT deduplicate these addresses and would let a second writer
--      land a second row on the same address. That is the data-loss shape of
--      item 459. The guard below is therefore explicit and it raises: a run
--      that finds any target address occupied writes nothing and says which.
--
--   3. NO COUNTER IS READ OR WRITTEN. The sprint counter already stands at
--      175, past both brackets, which is precisely why these addresses were
--      free to be backfilled. 001's way back set the counters to 1; that is
--      expressly not a template here -- with sprints 175 to 195 standing
--      today it would point the next `create` at an address in use. The
--      counter moved with V11 from `dispatch.number_circle` (dropped) onto
--      `dispatch.selector.next_number`; this script reads `selector.id` for
--      the foreign key V12 introduced and never reads or writes
--      `next_number`. That it did not move is checked by the operator around
--      the run, not by the script, so the script has no reason to touch it.
--
-- THE SCHEMA THIS TARGETS IS TODAY'S, NOT 001'S. V10 renamed `body`,
-- `handover_body` and `handover_metadata` to `dispatch_body`, `return_body`
-- and `return_metadata`; V11 dropped `dispatch.number_circle`; V12 replaced
-- `exchange.selector` (TEXT) with `exchange.selector_id` (BIGINT) behind a
-- foreign key. 001 predates all three and is left exactly as it was run.
--
-- FIDELITY. Title, dispatch body and return body of every row are carried
-- from the source file unchanged, and the return proves it by SHA-256 over
-- the same three fields, computed against the source files.
-- ===========================================================================

\\set ON_ERROR_STOP on

BEGIN;

-- The tenant, bound inside the transaction and reverting with it.
SET LOCAL app.tenant_id = '{TENANT}';

-- ---------------------------------------------------------------------------
-- Guard 1: a tenant is bound, and it is the one this file was generated for.
--
-- Without this the run would not be silent -- the first INSERT would raise on
-- the row-level-security policy -- but it would raise about the wrong thing,
-- and a reader would have to know the schema to see why. Worse, a run bound
-- to a DIFFERENT tenant would not raise at all: it would write four rows into
-- someone else's scope and report success. So the binding is asserted, by
-- value, before anything is read or written.
-- ---------------------------------------------------------------------------
DO $$
DECLARE bound TEXT;
BEGIN
    bound := NULLIF(current_setting('app.tenant_id', true), '');
    IF bound IS NULL THEN
        RAISE EXCEPTION 'no tenant is bound: app.tenant_id is unset or empty. Every '
            'table of this schema carries ENABLE plus FORCE ROW LEVEL SECURITY, so an '
            'unbound run reads an empty scope -- it would find no target address taken '
            'and could report nothing-to-do as success. It stops here instead.';
    END IF;
    IF bound <> '{TENANT}' THEN
        RAISE EXCEPTION 'the bound tenant is %, but this backfill was generated for {TENANT}. '
            'Writing it under another tenant would put this corpus in another scope.', bound;
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- Guard 2: every selector this file writes under must already stand.
--
-- The backfill does not declare one. A selector missing here means the stock
-- is not the stock this file was generated against, and inventing it would
-- hide that.
-- ---------------------------------------------------------------------------
DO $$
DECLARE missing TEXT;
BEGIN
    SELECT string_agg(t.sel, ', ' ORDER BY t.sel) INTO missing
      FROM (VALUES {", ".join(f"('{s}')" for s in sel_set)}) AS t(sel)
     WHERE NOT EXISTS (
        SELECT 1 FROM dispatch.selector s
         WHERE s.tenant_id = '{TENANT}'::uuid
           AND s.scope_id  = '{SCOPE}'::uuid
           AND s.name      = t.sel);
    IF missing IS NOT NULL THEN
        RAISE EXCEPTION 'selector(s) % are not declared in this scope. The backfill does '
            'not declare a selector: a missing one means this is not the stock the file '
            'was generated against.', missing;
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- Guard 3: every target address must be free, and the run stops if one is not.
--
-- This is the guard that makes a second run harmless, and it is deliberately
-- not `WHERE NOT EXISTS` on each insert. Two reasons, and the second is the
-- one that matters. A per-insert skip is silent, so a partially-occupied
-- stock would be half-filled and reported as done. And `uq_exchange_address`
-- cannot be the backstop: it spans `addendum_suffix`, NULL on every row here,
-- and without NULLS NOT DISTINCT those NULLs compare as distinct -- so the
-- constraint admits a duplicate address rather than refusing it. A second
-- writer on one address is the data loss of item 459; this raises instead.
-- ---------------------------------------------------------------------------
DO $$
DECLARE taken TEXT;
BEGIN
    SELECT string_agg(format('%s/%s.%s', t.sel, t.num, t.sub), ', '
                      ORDER BY t.sel, t.num, t.sub) INTO taken
      FROM (VALUES {values}) AS t(sel, num, sub)
      JOIN dispatch.selector s
        ON s.tenant_id = '{TENANT}'::uuid
       AND s.scope_id  = '{SCOPE}'::uuid
       AND s.name      = t.sel
      JOIN dispatch.exchange e
        ON e.tenant_id   = s.tenant_id
       AND e.scope_id    = s.scope_id
       AND e.selector_id = s.id
       AND e.number      = t.num
       AND e.sub         = t.sub
       AND e.addendum_suffix IS NULL;
    IF taken IS NOT NULL THEN
        RAISE EXCEPTION 'the backfill refuses to run: % already stand in this scope. '
            'Nothing was written. An occupied address is never overwritten and never '
            'skipped -- a second row on one address is the data loss this guard exists '
            'to prevent.', taken;
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- The exchanges: {len(rows)} rows. One statement per row, so a refusal names
-- the address it happened at. No WHERE NOT EXISTS -- guard 3 already
-- established that every one of these addresses is free, and a second,
-- silent check here would undo what that guard is for.
--
-- `selector_id` is resolved by sub-select on the selector's identity
-- (tenant, scope, name), which is what V12 made the join column. Guard 2
-- established the row is there, so the sub-select cannot be NULL.
-- ---------------------------------------------------------------------------
""")

    for r in rows:
        suf = lit(r["suffix"])
        W(f"""
-- {r['selector']} {r['number']}.{r['sub']}{r['suffix'] or ''}
INSERT INTO dispatch.exchange
    (tenant_id, scope_id, selector_id, number, sub, addendum_suffix, status,
     title, dispatch_body, apparatus, dispatch_date, sent_at,
     return_body, ratified_at, dispatch_metadata, return_metadata)
SELECT '{TENANT}'::uuid, '{SCOPE}'::uuid,
       (SELECT s.id FROM dispatch.selector s
         WHERE s.tenant_id = '{TENANT}'::uuid AND s.scope_id = '{SCOPE}'::uuid
           AND s.name = '{r['selector']}'),
       {r['number']}, {r['sub']}, {suf}, '{r['status']}',
       {lit(r['title'])}, {lit(r['body'])}, {lit(r['apparatus'])}, DATE '{r['date']}', TIMESTAMPTZ '{r['sent']}T00:00:00Z',
       {lit(r['handover'])}, {"TIMESTAMPTZ '" + r['ratified'] + "T00:00:00Z'" if r['ratified'] else 'NULL'},
       {jsonlit(r['dmeta'])}, {jsonlit(r['hmeta'])};
""")

    W(f"""
-- ---------------------------------------------------------------------------
-- The report. Addresses and counts only -- never a title and never a body.
-- These rows are steering content: the backfill moves it and does not read it
-- out, and a log line carrying a title would be the ops-no-content boundary
-- crossed by the import that is supposed to respect it.
--
-- Printed before the postconditions, so the numbers are on screen even when a
-- postcondition then stops the run.
-- ---------------------------------------------------------------------------
DO $$
DECLARE r RECORD;
BEGIN
    RAISE NOTICE '--- backfilled stock, by bracket and status ---';
    FOR r IN
        SELECT s.name AS sel, e.number, e.status, count(*) AS n
          FROM dispatch.exchange e
          JOIN dispatch.selector s ON s.id = e.selector_id
          JOIN (VALUES {values}) AS t(sel, num, sub)
            ON t.sel = s.name AND t.num = e.number AND t.sub = e.sub
         WHERE e.tenant_id = '{TENANT}'::uuid
           AND e.scope_id  = '{SCOPE}'::uuid
           AND e.addendum_suffix IS NULL
         GROUP BY s.name, e.number, e.status
         ORDER BY s.name, e.number, e.status
    LOOP
        RAISE NOTICE '  % % / % : % row(s)', r.sel, r.number, r.status, r.n;
    END LOOP;

    FOR r IN
        SELECT s.name AS sel, e.number, e.sub,
               (e.return_body IS NOT NULL) AS has_return
          FROM dispatch.exchange e
          JOIN dispatch.selector s ON s.id = e.selector_id
          JOIN (VALUES {values}) AS t(sel, num, sub)
            ON t.sel = s.name AND t.num = e.number AND t.sub = e.sub
         WHERE e.tenant_id = '{TENANT}'::uuid
           AND e.scope_id  = '{SCOPE}'::uuid
           AND e.addendum_suffix IS NULL
         ORDER BY s.name, e.number, e.sub
    LOOP
        RAISE NOTICE '  address %/%.% carries a return: %', r.sel, r.number, r.sub, r.has_return;
    END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- Four postconditions. Each raises. They are stated over the {len(rows)}
-- target addresses by equality, never over a range: a range would also cover
-- rows the service issued later, and this file has no business asserting
-- anything about those.
-- ---------------------------------------------------------------------------
DO $$
DECLARE n BIGINT;
BEGIN
    -- 1. exactly one row per target address, and no more than the targets.
    SELECT count(*) INTO n
      FROM dispatch.exchange e
      JOIN dispatch.selector s ON s.id = e.selector_id
      JOIN (VALUES {values}) AS t(sel, num, sub)
        ON t.sel = s.name AND t.num = e.number AND t.sub = e.sub
     WHERE e.tenant_id = '{TENANT}'::uuid
       AND e.scope_id  = '{SCOPE}'::uuid
       AND e.addendum_suffix IS NULL;
    IF n <> {len(rows)} THEN
        RAISE EXCEPTION 'postcondition 1: expected {len(rows)} rows on the target addresses, found %', n;
    END IF;

    -- 2. every row past draft carries a send time. The data-level constraint
    --    ck_sent_when_past_draft says the same thing; asserting it here is
    --    what makes a violation name the import rather than the schema.
    SELECT count(*) INTO n
      FROM dispatch.exchange e
      JOIN dispatch.selector s ON s.id = e.selector_id
      JOIN (VALUES {values}) AS t(sel, num, sub)
        ON t.sel = s.name AND t.num = e.number AND t.sub = e.sub
     WHERE e.tenant_id = '{TENANT}'::uuid AND e.scope_id = '{SCOPE}'::uuid
       AND e.addendum_suffix IS NULL
       AND e.status <> 'draft' AND e.sent_at IS NULL;
    IF n > 0 THEN RAISE EXCEPTION 'postcondition 2: % rows past draft without sent_at', n; END IF;

    -- 3. the backfilled stock is terminal throughout. Both brackets are
    --    closed work; a non-terminal status here would mean an exchange was
    --    backfilled as if it were still running.
    SELECT count(*) INTO n
      FROM dispatch.exchange e
      JOIN dispatch.selector s ON s.id = e.selector_id
      JOIN (VALUES {values}) AS t(sel, num, sub)
        ON t.sel = s.name AND t.num = e.number AND t.sub = e.sub
     WHERE e.tenant_id = '{TENANT}'::uuid AND e.scope_id = '{SCOPE}'::uuid
       AND e.addendum_suffix IS NULL
       AND e.status NOT IN ('closed', 'consumed');
    IF n > 0 THEN RAISE EXCEPTION 'postcondition 3: % backfilled rows are not terminal', n; END IF;

    -- 4. an answer without a ratification time would be a half-migrated
    --    return: the body carried over, the fact that it was frozen lost.
    SELECT count(*) INTO n
      FROM dispatch.exchange e
      JOIN dispatch.selector s ON s.id = e.selector_id
      JOIN (VALUES {values}) AS t(sel, num, sub)
        ON t.sel = s.name AND t.num = e.number AND t.sub = e.sub
     WHERE e.tenant_id = '{TENANT}'::uuid AND e.scope_id = '{SCOPE}'::uuid
       AND e.addendum_suffix IS NULL
       AND e.return_body IS NOT NULL AND e.ratified_at IS NULL;
    IF n > 0 THEN RAISE EXCEPTION 'postcondition 4: % rows carry an answer with no ratified_at', n; END IF;

    RAISE NOTICE 'all four postconditions hold.';
END $$;

-- ---------------------------------------------------------------------------
-- The difference between the rehearsal and the real thing is this one word.
-- ROLLBACK -> the run reports and leaves nothing. COMMIT -> it stays.
-- The SET LOCAL above reverts either way.
-- ---------------------------------------------------------------------------
ROLLBACK;
""")
    o.close()

    print(f"geschrieben: {OUT}")
    print(f"Nachtrag, Klammern: {brackets}")
    print(f"Austausche: {len(rows)}  (aus {sum(len(v) for v in ex.values())} Quelldateien)")
    for r in rows:
        print(f"    {r['selector']}/{r['number']}.{r['sub']}{r['suffix'] or ''}"
              f"  status={r['status']}  apparatus={r['apparatus']}  date={r['date']}"
              f"  return={'ja' if r['handover'] else 'nein'}")
    print("Zaehler: nicht gelesen und nicht geschrieben.")
    sys.exit(0)

# --------------------------------------------------------------------------
# 4. emit
# --------------------------------------------------------------------------
os.makedirs(os.path.dirname(OUT), exist_ok=True)
o = open(OUT, "w", encoding="utf-8")
W = o.write
gen = datetime.date.today().isoformat()

W(f"""-- ===========================================================================
-- 001-import-steering-corpus.sql
--
-- Import of the steering corpus of the predecessor service into
-- dispatch.selector, dispatch.number_circle and dispatch.exchange.
--
-- Generated on {gen} by migration/generate-import.py. Do not hand-edit: the
-- script is a projection of the source corpus, and a hand edit makes the two
-- disagree without saying so. Change the generator and regenerate.
--
-- WHAT THIS SCRIPT IS NOT. It does not create, alter or drop anything, it
-- does not touch row-level security, and it does not run itself: the file
-- ends in ROLLBACK. Read migration/README.md before running it -- the way
-- back is written there before the way in.
--
-- LINE 1 OF THE BODY IS THE TENANT SETTING, and it is not decoration. All
-- three target tables carry ENABLE plus FORCE ROW LEVEL SECURITY, so the
-- policy binds the table owner too. Without the setting every INSERT below
-- writes nothing and says nothing about it.
--
-- IDEMPOTENCE IS NOT BUILT ON ON CONFLICT. uq_exchange_address spans
-- addendum_suffix, which is NULL on all but one row, and a UNIQUE constraint
-- without NULLS NOT DISTINCT treats those NULLs as distinct -- so the
-- constraint does not deduplicate the ordinary case and ON CONFLICT would
-- never fire for it. Every INSERT is therefore guarded by a WHERE NOT EXISTS
-- using IS NOT DISTINCT FROM. Reported as a finding; the schema is unchanged.
--
-- THE NUMBER CIRCLES ARE SET PAST THE IMPORT, not to its maximum plus one.
-- The import stops at sprint {SPRINT_CUT}, but sprints 173 and 174 are issued
-- addresses in the predecessor service. Handing out 173 again would collide
-- with an address that exists. sprint -> {CIRCLE_NEXT['sprint']}, satellite -> {CIRCLE_NEXT['satellite']}.
-- ===========================================================================

\\set ON_ERROR_STOP on

SET app.tenant_id = '{TENANT}';

BEGIN;

-- ---------------------------------------------------------------------------
-- Selectors and their circles. V5 already declared both; these statements are
-- here so the script also runs against a deployment where it did not.
-- ---------------------------------------------------------------------------
""")

for name in ("sprint", "satellite"):
    W(f"""INSERT INTO dispatch.selector (tenant_id, scope_id, name)
SELECT '{TENANT}'::uuid, '{SCOPE}'::uuid, '{name}'
WHERE NOT EXISTS (SELECT 1 FROM dispatch.selector
                  WHERE tenant_id = '{TENANT}'::uuid AND scope_id = '{SCOPE}'::uuid AND name = '{name}');

INSERT INTO dispatch.number_circle (tenant_id, scope_id, selector)
SELECT '{TENANT}'::uuid, '{SCOPE}'::uuid, '{name}'
WHERE NOT EXISTS (SELECT 1 FROM dispatch.number_circle
                  WHERE tenant_id = '{TENANT}'::uuid AND scope_id = '{SCOPE}'::uuid AND selector = '{name}');

""")

W(f"""-- ---------------------------------------------------------------------------
-- The exchanges: {len(rows)} rows, {per_sel['sprint']} under 'sprint' and {per_sel['satellite']} under 'satellite'.
-- One statement per row, so a refusal names the address it happened at.
-- ---------------------------------------------------------------------------
""")

for r in rows:
    suf = lit(r["suffix"])
    W(f"""
-- {r['selector']} {r['number']}.{r['sub']}{r['suffix'] or ''}
INSERT INTO dispatch.exchange
    (tenant_id, scope_id, selector, number, sub, addendum_suffix, status,
     title, body, apparatus, dispatch_date, sent_at,
     handover_body, ratified_at, dispatch_metadata, handover_metadata)
SELECT '{TENANT}'::uuid, '{SCOPE}'::uuid, '{r['selector']}', {r['number']}, {r['sub']}, {suf}, '{r['status']}',
       {lit(r['title'])}, {lit(r['body'])}, {lit(r['apparatus'])}, DATE '{r['date']}', TIMESTAMPTZ '{r['sent']}T00:00:00Z',
       {lit(r['handover'])}, {"TIMESTAMPTZ '" + r['ratified'] + "T00:00:00Z'" if r['ratified'] else 'NULL'},
       {jsonlit(r['dmeta'])}, {jsonlit(r['hmeta'])}
WHERE NOT EXISTS (
    SELECT 1 FROM dispatch.exchange
     WHERE tenant_id = '{TENANT}'::uuid AND scope_id = '{SCOPE}'::uuid
       AND selector = '{r['selector']}' AND number = {r['number']} AND sub = {r['sub']}
       AND addendum_suffix IS NOT DISTINCT FROM {suf});
""")

W(f"""
-- ---------------------------------------------------------------------------
-- The circles, set past every issued address. See the header.
-- ---------------------------------------------------------------------------
UPDATE dispatch.number_circle SET next_number = {CIRCLE_NEXT['sprint']}
 WHERE tenant_id = '{TENANT}'::uuid AND scope_id = '{SCOPE}'::uuid
   AND selector = 'sprint' AND next_number < {CIRCLE_NEXT['sprint']};

UPDATE dispatch.number_circle SET next_number = {CIRCLE_NEXT['satellite']}
 WHERE tenant_id = '{TENANT}'::uuid AND scope_id = '{SCOPE}'::uuid
   AND selector = 'satellite' AND next_number < {CIRCLE_NEXT['satellite']};

-- ---------------------------------------------------------------------------
-- The report. Printed before the postconditions so that the numbers are on
-- screen even when a postcondition then stops the run.
--
-- "the migrated stock" below means the address ranges this import writes:
-- sprint <= {maxnum['sprint']} and satellite <= {maxnum['satellite']}. Rows the service creates later
-- carry higher numbers and are deliberately outside every check.
-- ---------------------------------------------------------------------------
DO $$
DECLARE r RECORD;
BEGIN
    RAISE NOTICE '--- imported stock by selector and status ---';
    FOR r IN
        SELECT selector, status, count(*) AS n
          FROM dispatch.exchange
         WHERE tenant_id = '{TENANT}'::uuid AND scope_id = '{SCOPE}'::uuid
           AND ((selector = 'sprint'    AND number <= {maxnum['sprint']})
             OR (selector = 'satellite' AND number <= {maxnum['satellite']}))
         GROUP BY selector, status ORDER BY selector, status
    LOOP
        RAISE NOTICE '  % / % : %', r.selector, r.status, r.n;
    END LOOP;
    FOR r IN
        SELECT selector, count(*) AS n,
               count(*) FILTER (WHERE handover_body IS NULL) AS ohne_antwort,
               count(*) FILTER (WHERE addendum_suffix IS NOT NULL) AS nachtraege
          FROM dispatch.exchange
         WHERE tenant_id = '{TENANT}'::uuid AND scope_id = '{SCOPE}'::uuid
           AND ((selector = 'sprint'    AND number <= {maxnum['sprint']})
             OR (selector = 'satellite' AND number <= {maxnum['satellite']}))
         GROUP BY selector ORDER BY selector
    LOOP
        RAISE NOTICE '  % : % rows, % without a handover, % addenda', r.selector, r.n, r.ohne_antwort, r.nachtraege;
    END LOOP;
    FOR r IN
        SELECT selector, next_number FROM dispatch.number_circle
         WHERE tenant_id = '{TENANT}'::uuid AND scope_id = '{SCOPE}'::uuid ORDER BY selector
    LOOP
        RAISE NOTICE '  circle % -> next_number %', r.selector, r.next_number;
    END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- Five postconditions. An import script without one is an assertion.
-- ---------------------------------------------------------------------------
DO $$
DECLARE n BIGINT; m INTEGER; c INTEGER;
BEGIN
    -- 1. one row per source pair, per selector.
    SELECT count(*) INTO n FROM dispatch.exchange
     WHERE tenant_id = '{TENANT}'::uuid AND scope_id = '{SCOPE}'::uuid
       AND selector = 'sprint' AND number <= {maxnum['sprint']};
    IF n <> {per_sel['sprint']} THEN
        RAISE EXCEPTION 'postcondition 1 (sprint): expected {per_sel['sprint']} rows, found %', n;
    END IF;

    SELECT count(*) INTO n FROM dispatch.exchange
     WHERE tenant_id = '{TENANT}'::uuid AND scope_id = '{SCOPE}'::uuid
       AND selector = 'satellite' AND number <= {maxnum['satellite']};
    IF n <> {per_sel['satellite']} THEN
        RAISE EXCEPTION 'postcondition 1 (satellite): expected {per_sel['satellite']} rows, found %', n;
    END IF;

    -- 2. every row past draft carries a send time.
    SELECT count(*) INTO n FROM dispatch.exchange
     WHERE tenant_id = '{TENANT}'::uuid AND scope_id = '{SCOPE}'::uuid
       AND ((selector = 'sprint' AND number <= {maxnum['sprint']}) OR (selector = 'satellite' AND number <= {maxnum['satellite']}))
       AND status <> 'draft' AND sent_at IS NULL;
    IF n > 0 THEN RAISE EXCEPTION 'postcondition 2: % rows past draft without sent_at', n; END IF;

    -- 3. each circle stands past every imported number of its selector.
    FOR m, c IN
        SELECT max(e.number), (SELECT nc.next_number FROM dispatch.number_circle nc
                                WHERE nc.tenant_id = e.tenant_id AND nc.scope_id = e.scope_id
                                  AND nc.selector = e.selector)
          FROM dispatch.exchange e
         WHERE e.tenant_id = '{TENANT}'::uuid AND e.scope_id = '{SCOPE}'::uuid
         GROUP BY e.tenant_id, e.scope_id, e.selector
    LOOP
        IF c IS NULL OR c <= m THEN
            RAISE EXCEPTION 'postcondition 3: circle stands at %, highest number is %', c, m;
        END IF;
    END LOOP;

    -- 4. the migrated stock is terminal throughout.
    SELECT count(*) INTO n FROM dispatch.exchange
     WHERE tenant_id = '{TENANT}'::uuid AND scope_id = '{SCOPE}'::uuid
       AND ((selector = 'sprint' AND number <= {maxnum['sprint']}) OR (selector = 'satellite' AND number <= {maxnum['satellite']}))
       AND status NOT IN ('closed', 'consumed');
    IF n > 0 THEN RAISE EXCEPTION 'postcondition 4: % migrated rows are not terminal', n; END IF;

    -- 5. an answer without a ratification time would be a half-migrated handover.
    SELECT count(*) INTO n FROM dispatch.exchange
     WHERE tenant_id = '{TENANT}'::uuid AND scope_id = '{SCOPE}'::uuid
       AND ((selector = 'sprint' AND number <= {maxnum['sprint']}) OR (selector = 'satellite' AND number <= {maxnum['satellite']}))
       AND handover_body IS NOT NULL AND ratified_at IS NULL;
    IF n > 0 THEN RAISE EXCEPTION 'postcondition 5: % rows carry an answer with no ratified_at', n; END IF;

    RAISE NOTICE 'all five postconditions hold.';
END $$;

-- ---------------------------------------------------------------------------
-- The difference between the rehearsal and the real thing is this one word.
-- ROLLBACK -> the run reports and leaves nothing. COMMIT -> it stays.
-- ---------------------------------------------------------------------------
ROLLBACK;
""")
o.close()



print(f"geschrieben: {OUT}")
print(f"Zeilen gesamt: {len(rows)}  | sprint: {per_sel['sprint']}  satellite: {per_sel['satellite']}")
print(f"Quelldateien: steering sprint {src_files['sprint']}, satellite {src_files['satellite']}, legacy {src_files['legacy']}")
print(f"Hoechste Nummer: sprint {maxnum['sprint']}, satellite {maxnum['satellite']}")
print(f"erzwungen terminiert: {len(forced)}")
for f_, s_ in forced: print("   ", f_, s_)
print("Altbestand-Notizen:")
for f_, note in legacy_notes: print("   ", f_, "->", note)
print("Statusabbildung (auftrag, ruecklauf) -> ziel:")
for k, v in sorted(status_map.items(), key=lambda x: -x[1]): print("   ", k, v)
