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
            if selector == "sprint" and num > SPRINT_CUT: continue
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
for fn in sorted(os.listdir(LEGACY)):
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
