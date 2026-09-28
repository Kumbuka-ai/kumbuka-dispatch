-- ===========================================================================
-- 002-backfill-steering-corpus.sql
--
-- The backfill of the brackets the first import left out: sprint 173, sprint 174.
-- 4 exchanges into dispatch.exchange, and nothing else.
--
-- Generated on 2026-09-28 by migration/generate-import.py under
-- KB_ONLY=sprint:173,174. Do not hand-edit: the script is a
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

\set ON_ERROR_STOP on

BEGIN;

-- The tenant, bound inside the transaction and reverting with it.
SET LOCAL app.tenant_id = 'a7b072e4-89dd-473e-acc0-91a98a8bae7a';

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
    IF bound <> 'a7b072e4-89dd-473e-acc0-91a98a8bae7a' THEN
        RAISE EXCEPTION 'the bound tenant is %, but this backfill was generated for a7b072e4-89dd-473e-acc0-91a98a8bae7a. '
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
      FROM (VALUES ('sprint')) AS t(sel)
     WHERE NOT EXISTS (
        SELECT 1 FROM dispatch.selector s
         WHERE s.tenant_id = 'a7b072e4-89dd-473e-acc0-91a98a8bae7a'::uuid
           AND s.scope_id  = '0845a29c-9b55-4405-a445-7849416731b8'::uuid
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
      FROM (VALUES ('sprint', 173, 0), ('sprint', 173, 1), ('sprint', 174, 0), ('sprint', 174, 1)) AS t(sel, num, sub)
      JOIN dispatch.selector s
        ON s.tenant_id = 'a7b072e4-89dd-473e-acc0-91a98a8bae7a'::uuid
       AND s.scope_id  = '0845a29c-9b55-4405-a445-7849416731b8'::uuid
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
-- The exchanges: 4 rows. One statement per row, so a refusal names
-- the address it happened at. No WHERE NOT EXISTS -- guard 3 already
-- established that every one of these addresses is free, and a second,
-- silent check here would undo what that guard is for.
--
-- `selector_id` is resolved by sub-select on the selector's identity
-- (tenant, scope, name), which is what V12 made the join column. Guard 2
-- established the row is there, so the sub-select cannot be NULL.
-- ---------------------------------------------------------------------------

-- sprint 173.0
INSERT INTO dispatch.exchange
    (tenant_id, scope_id, selector_id, number, sub, addendum_suffix, status,
     title, dispatch_body, apparatus, dispatch_date, sent_at,
     return_body, ratified_at, dispatch_metadata, return_metadata)
SELECT 'a7b072e4-89dd-473e-acc0-91a98a8bae7a'::uuid, '0845a29c-9b55-4405-a445-7849416731b8'::uuid,
       (SELECT s.id FROM dispatch.selector s
         WHERE s.tenant_id = 'a7b072e4-89dd-473e-acc0-91a98a8bae7a'::uuid AND s.scope_id = '0845a29c-9b55-4405-a445-7849416731b8'::uuid
           AND s.name = 'sprint'),
       173, 0, NULL, 'closed',
       $kbimp$cimd-proxy v0.2.0: der Proxy sagt nach aussen, was er kann -- scopes_supported, offline_access als Vorgabe, DCR neben CIMD$kbimp$, $kbimp$## Auftrag

Der CIMD-Proxy laeuft seit dem 2026-09-06 in Produktion und ist in zwei Punkten
unbenutzbar. Beide sind gemessen, beide haben dieselbe Wurzel: der Proxy sagt
nach aussen zu wenig ueber sich selbst.

**Erstens: die Verbindung stirbt nach 30 Minuten Untaetigkeit.** Der Refresh
funktioniert -- dreimal im Vier-Minuten-Takt belegt -- und scheitert nach einer
Pause von 61 Minuten mit `invalid_grant`. Ursache: der Client schickt keinen
`scope`, weil das Discovery-Dokument des Proxies kein `scopes_supported` traegt;
der Proxy faellt auf `openid` zurueck; Keycloak stellt ein an die SSO-Sitzung
gebundenes Token aus, das an `ssoSessionIdleTimeout` stirbt statt an
`offlineSessionIdleTimeout` (2592000).

**Zweitens: Clients der alten MCP-Revision koennen sich nicht verbinden.**
Mistral meldet "Dieser Connector unterstuetzt keine dynamische
Client-Registrierung". Das Discovery-Dokument traegt bewusst keinen
`registration_endpoint`. Die MCP-Revision vom 2026-07-28 hat DCR zugunsten von
CIMD deprecated und haelt es fuer mindestens zwoelf Monate am Leben; Mistral
sitzt auf der alten Seite.

## Frame

Beides wird in einem Durchgang gebaut, weil beide dasselbe Dokument und
denselben Autorisierungspfad beruehren.

Ratifiziert am 2026-09-06: `openid offline_access` wird die **Vorgabe** des
Proxies, konfigurierbar und standardmaessig aktiv; ein Client, der selbst einen
Scope anfordert, ueberschreibt sie. Und DCR wird neben CIMD angeboten, nicht
statt dessen.

Die tragende Regel bleibt unangetastet: **der Proxy liest das Token nie.** Er
stellt keins aus, prueft keins, parst keins, signiert keins. DCR aendert daran
nichts -- der Upstream-Client bleibt der statische aus der Ressourcentabelle.

## Grenze

Nicht in diesem Sprint: Variante 2, also eigene Tokenausstellung mit
Signierschluessel. Nicht in diesem Sprint: der Realmwechsel der
Steuerungsdienste. Nicht in diesem Sprint: `wlm.jbaconsult.com` hinter den Proxy
zu ziehen -- das ist eine eigene Aenderung mit eigener URL, siehe F-0331.

Nicht in diesem Sprint: der Owner-Normalization-Sweep, der die
IST-Synchronisation blockiert, und die sechs Findings F-0329 bis F-0334.
$kbimp$, $kbimp$concept$kbimp$, DATE '2026-09-06', TIMESTAMPTZ '2026-09-06T00:00:00Z',
       $kbimp$## Ergebnis

Antwortet SPRINT_173.0. Der Proxy sagt jetzt nach aussen, was er kann. Drei
Bauteile, je einzeln nachgewiesen, in einem Pull Request gemergt und als
`v0.2.0` getaggt.

**Teil 1 -- `scopes_supported`.** Das Discovery-Dokument traegt es, gespeist aus
`PROXY_SCOPES_SUPPORTED` mit dem Standardwert `openid offline_access`. Ein leer
konfigurierter Wert laesst das Feld weg statt ein leeres Feld auszuliefern; die
Unterscheidung ist die zwischen Schweigen und der Aussage "kein einziger Scope".

**Teil 2 -- `PROXY_DEFAULT_SCOPE`.** Die Konstante `openid` in `authorize.py`
ist durch einen Konfigurationswert ersetzt, Standardwert `openid offline_access`.
Ein Client, der `scope` mitschickt, bleibt unangetastet -- die Vorgabe fuellt
Schweigen und ueberschreibt keine Anforderung. Der gewaehlte Scope steht jetzt
in `authorize.forwarded`; die heutige Diagnose hat dafuer noch den Umweg ueber
das Caddy-Zugriffslog gebraucht.

**Teil 3 -- `POST /register` nach RFC 7591.** Der `registration_endpoint` steht
im Discovery-Dokument, `client_id_metadata_document_supported` bleibt daneben:
der Proxy spricht ab hier beide Registrierungswege. Die zurueckgegebene
`client_id` ist ein vierter Fernet-Umschlag mit dem Tag `rg`, zustandslos wie
die drei anderen. `/authorize` unterscheidet https-URL (CIMD) von Umschlag
(DCR) und weist alles andere typisiert ab.

Damit sind beide Symptome aus dem Briefing adressiert: die
30-Minuten-Sterblichkeit der Verbindung und die Aussperrung von Clients der
alten MCP-Revision.

## Nachweis

**Kontrollpruefung nach APPARATUS 14.3, gegen origin gelesen statt aus dem
Return uebernommen:** PR 2 in `Kumbuka-ai/cimd-proxy` steht auf `merged`,
2026-09-06 15:12:39Z durch `jbaconsult`, 20 Dateien, +1305/-44, zwei Commits.
Tag `v0.2.0` zeigt auf `84088a76` -- den **Merge-Commit**, nicht auf den
Zweigkopf `f9476598`. Damit ist die Herkunftsdrift, die bei `v0.1.0` heute
Vormittag auffiel, hier nicht eingetreten.

**Testsuite:** 169 bestanden, 7 als erwarteter Fehlschlag markiert -- die
roten Kontrollen aller sieben Proben, vier aus v0.1.0 und drei aus diesem
Sprint. Lint und Format sauber.

**Die drei geforderten Nachtraege sind im Return beantwortet:** die Allowlist
wird auf den Host jeder `redirect_uri` bei der Registrierung angewandt; die
drei bestehenden Umschlagformate sind feldidentisch und durch
Round-Trip-Tests gedeckt; und die Vorgabe ueberschreibt einen anfordernden
Client nachweislich nicht -- bei Vorgabe `openid offline_access profile` und
Anfrage `scope=openid` erreicht den Upstream genau `openid`.

**Laufender Container**, lokal gebaut: das Discovery-Dokument liefert
`scopes_supported` und `registration_endpoint`, eine Registrierung mit
`token_endpoint_auth_method: none` wird mit einem Fernet-Umschlag als
`client_id` beantwortet, eine mit `client_secret_post` mit 400 und
`invalid_client_metadata`. Kein `client_secret` in der Antwort.

## Abweichung

**Die rote Probe RP7 ist anders gebaut als RP5 und RP6, und das ist der eine
Punkt, den die Kontrollpruefung ausdruecklich benennt.** RP5 und RP6 kippen
eine Konfiguration und beobachten die geaenderte Wirkung -- das ist
"Pruefung herausnehmen, Gate rot werden sehen". RP7 behauptet stattdessen die
Negation (`status_code != 400`) und ist damit rot, solange der Waechter
greift. Als Detektor ist das wirksam: kippt der Waechter, wird der Test gruen
und die strikte Markierung faellt laut auf. Als Nachweis ist es schwaecher,
weil nie gezeigt wird, dass GERADE dieser Waechter den 400 erzeugt -- ein 400
aus einem unbeteiligten Grund saehe identisch aus. Das ist die Regel "das
Gate prueft die Ebene unter der Behauptung", und sie ist hier nicht ganz
eingeloest. Kein Blocker, kein Rueckbau; benannt, damit es beim naechsten
Bauteil dieser Art anders gemacht wird.

**Eine Concept-Frage kam zurueck und ist offen.** Die Allowlist greift auf dem
DCR-Weg nur bei der Registrierung, nicht erneut bei `/authorize`. Folge: eine
spaetere Verschaerfung der Allowlist beruehrt bestehende Registrierungen nicht,
weil ihre `redirect_uris` im Umschlag versiegelt sind. Ob das ein Vorteil oder
ein Waechterverlust ist, hat der Bau nicht entschieden, sondern als Kommentar
im Modul markiert. Eine spaetere Ratifizierung waere rein additiv.

**Ein Regime-Zusatz, deklariert:** `POST /register` laesst ausschliesslich
`token_endpoint_auth_method: none` durch und weist auch die asymmetrischen
Verfahren ab, die RFC 7591 technisch zulaesst. Begruendung des Baus: der Proxy
unterstuetzt nur oeffentliche Clients mit PKCE, und ein angenommener Wert
ausserhalb `none` waere ein Versprechen, das er spaeter nicht einloest.
Fail-loud vor Versprechen halten -- richtig, und richtig gemeldet.

**Ein Sonar-Nachzyklus im Pull Request.** Der erste Push liess drei Code-Smells
ueber den Schwellenwert; ein Nachschub-Commit hat sie behoben, danach gruen.
Normale Hygiene, keine Substanz -- aber es ist der zweite Sprint in Folge, in
dem das Qualitaetstor einen zweiten Durchgang braucht.

**Nicht erledigt und ausdruecklich offen: der Rollout.** `v0.2.0` ist getaggt,
aber `deploy.cfg` traegt weiterhin `CIMD_PROXY_VERSION=v0.1.0`. In Produktion
laeuft der alte Stand, und damit gilt die 30-Minuten-Sterblichkeit weiter.
Ausserdem steht `CIMD_PROXY_DEBUG` unveraendert auf `true` -- ein Messfenster,
das mit demselben Handgriff geschlossen gehoert.
$kbimp$, TIMESTAMPTZ '2026-09-06T00:00:00Z',
       $kbimp${"source": "sprints/173/SPRINT_173.0-dispatch.md", "task": ["FEAT-95"]}$kbimp$::jsonb, $kbimp${"source": "sprints/173/SPRINT_173.0-return.md"}$kbimp$::jsonb;

-- sprint 173.1
INSERT INTO dispatch.exchange
    (tenant_id, scope_id, selector_id, number, sub, addendum_suffix, status,
     title, dispatch_body, apparatus, dispatch_date, sent_at,
     return_body, ratified_at, dispatch_metadata, return_metadata)
SELECT 'a7b072e4-89dd-473e-acc0-91a98a8bae7a'::uuid, '0845a29c-9b55-4405-a445-7849416731b8'::uuid,
       (SELECT s.id FROM dispatch.selector s
         WHERE s.tenant_id = 'a7b072e4-89dd-473e-acc0-91a98a8bae7a'::uuid AND s.scope_id = '0845a29c-9b55-4405-a445-7849416731b8'::uuid
           AND s.name = 'sprint'),
       173, 1, NULL, 'consumed',
       $kbimp$cimd-proxy v0.2.0 bauen: scopes_supported ankuendigen, offline_access als konfigurierbare Vorgabe, DCR als vierter Umschlagtyp neben CIMD$kbimp$, $kbimp$## Auftrag

`Kumbuka-ai/cimd-proxy` auf v0.2.0. Drei Bauteile, in dieser Reihenfolge. Jedes
wird EINZELN nachgewiesen: eigene rote Probe mit beobachtetem rotem Zustand,
eigene legitime Gegenprobe, eigene Messung. Ein gemeinsamer Nachweis ueber alle
drei ist keiner.

Der Stand auf `main` ist am 2026-09-06 gemessen: `src/cimd_proxy/` traegt
`app.py`, `authorize.py`, `callback.py`, `token.py`, `discovery.py`,
`cimd_service.py`, `fetcher.py`, `cache.py`, `envelope.py`, `config.py`,
`pkce.py`, `errors.py`, `correlation.py`, `logging_setup.py`, `upstream.py`.
Der Dienst laeuft als `ghcr.io/kumbuka-ai/cimd-proxy:v0.1.0` in Produktion.

### TEIL 1 -- der Proxy kuendigt seine Scopes an

`build_discovery_document` liefert heute kein `scopes_supported`. RFC 8414 sieht
das Feld genau dafuer vor, und ein Client, der nichts angekuendigt sieht, fordert
nichts an.

Gebaut wird `scopes_supported` im Discovery-Dokument, gespeist aus der
Konfiguration. Neue Umgebungsvariable `PROXY_SCOPES_SUPPORTED`, Standardwert
`openid offline_access`.

**Rote Probe:** das Discovery-Dokument traegt `offline_access` in
`scopes_supported`; wird das Feld aus dem Dokument entfernt, muss der Test rot
fahren. Gegenprobe: ein leer konfigurierter Wert laesst das Feld weg statt es
leer auszuliefern -- ein leeres `scopes_supported` behauptet, der Server koenne
keinen einzigen Scope.

### TEIL 2 -- offline_access als Vorgabe

`authorize.py` setzt heute `scope = params.scope or _UPSTREAM_SCOPE_DEFAULT`,
und die Konstante ist `"openid"`. Das ist die gemessene Ursache dafuer, dass
Keycloak ein an die SSO-Sitzung gebundenes Token ausstellt.

Gebaut wird: die Vorgabe kommt aus der Konfiguration, neue Umgebungsvariable
`PROXY_DEFAULT_SCOPE` mit dem Standardwert `openid offline_access`. Ein Client,
der `scope` mitschickt, ueberschreibt sie vollstaendig -- **nicht** ergaenzt. Der
Proxy erfindet keine Rechte an einer Anforderung vorbei; er fuellt nur eine
Luecke, wo der Client schweigt. Das ist eine ratifizierte Festlegung und keine
Bauentscheidung.

Der gewaehlte Scope gehoert in die Protokollzeile `authorize.forwarded`. Heute
steht er dort nicht, und genau deshalb hat die Diagnose einen Umweg ueber das
Caddy-Zugriffslog gebraucht.

**Rote Probe:** eine Anfrage ohne `scope` erreicht den Upstream mit
`scope=openid offline_access`; wird der Vorgabewert auf `openid` gesetzt, muss
der Test rot fahren und die Anfrage ohne `offline_access` sehen. Gegenprobe: eine
Anfrage MIT `scope=openid` erreicht den Upstream mit genau `openid` -- die
Vorgabe darf einen anfordernden Client nicht ueberschreiben.

### TEIL 3 -- DCR neben CIMD

`POST /register` nach RFC 7591, plus `registration_endpoint` im
Discovery-Dokument. `client_id_metadata_document_supported` bleibt stehen: der
Proxy spricht ab hier BEIDE Seiten.

Der Bau ist billig, weil der Upstream-Client statisch ist: der Proxy braucht fuer
einen dynamisch registrierten Client keine Upstream-Registrierung. Die
zurueckgegebene `client_id` ist ein **selbstbeschreibender Fernet-Umschlag** mit
den registrierten Metadaten -- zustandslos, keine Datenbank, dieselbe Mechanik
wie `AuthorizeEnvelope`, `CodeEnvelope` und `RefreshEnvelope`. Ein vierter
Umschlagtyp mit eigenem `TAG`, damit ein Umschlag der falschen Sorte nicht still
aufgeht.

`/authorize` unterscheidet danach zwei Formen von `client_id`: eine https-URL
geht den CIMD-Weg (holen, validieren, Allowlist), ein Umschlag geht den
DCR-Weg (auspacken, `redirect_uris` daraus pruefen). Alles andere wird typisiert
abgewiesen.

Die Registrierung validiert nach denselben Regeln, die CIMD-02 fuer das Dokument
setzt: `redirect_uris` vorhanden und nicht leer, kein `client_secret`, kein
symmetrisches `token_endpoint_auth_method` (`client_secret_post`,
`client_secret_basic`, `client_secret_jwt`). `client_id_issued_at` wird gesetzt,
`client_secret` NIE ausgegeben -- der Proxy kennt nur oeffentliche Clients mit
PKCE.

**Rote Probe:** eine Registrierung mit `token_endpoint_auth_method:
client_secret_post` wird typisiert abgewiesen; wird die Pruefung entfernt, muss
der Test rot fahren und die Registrierung durchgehen. Gegenprobe: eine
Registrierung mit `none` geht durch, und die zurueckgegebene `client_id` traegt
danach durch `/authorize` bis zum 302 auf den Upstream.

## Frame

**Die tragende Regel bleibt in Kraft: der Proxy liest das Token nie.** Er stellt
keins aus, prueft keins, parst keins, signiert keins. Wer im Bau eine Stelle
findet, an der ein Token dekodiert werden soll, hat die Anforderung falsch
verstanden. DCR aendert daran nichts.

**Keine Datenbank.** Der Registrierungsumschlag ist der Grund, warum keine noetig
ist. Wer eine Tabelle fuer registrierte Clients anlegt, hat den Entwurf
verfehlt -- und `envelope.py` sagt im Modulkommentar, welches Modul waechst,
wenn Variante 2 kaeme, und dass sie hier nicht vorweggenommen wird.

**Die SSRF-Grenzen des Abrufers bleiben unangetastet:** nur https, kein
Redirect-Folgen, nur 200 gilt, `client_id` im Dokument zeichengleich zur URL,
5 KB Lesegrenze, DNS-Aufloesung gegen RFC 6890 VOR dem Verbinden, keine
Loopback-Ausnahme. Der DCR-Weg holt kein Dokument und braucht den Abrufer nicht;
er darf ihn auch nicht umgehen, indem er eine URL als Umschlag durchreicht.

**Die Allowlist gilt weiter fuer den CIMD-Weg.** Ob und wie sie fuer den DCR-Weg
gilt, ist eine offene Frage: ein DCR-Client hat keine Domaene, an der man ihn
festmachen koennte, ausser seinen `redirect_uris`. Der Bau entscheidet das
nicht, sondern meldet, welche Wahl er getroffen hat und warum -- Vorschlag:
`redirect_uris`-Hosts gegen dieselbe Allowlist, weil das die einzige Groesse
ist, die ein Angreifer nicht frei waehlen kann, ohne die Weiterleitung zu
verlieren.

**Rueckwaertskompatibilitaet ist Pflicht.** Bestehende Umschlaege aus v0.1.0
muessen weiter aufgehen; der Fernet-Schluessel bleibt derselbe. Eine Aenderung
an den drei bestehenden Umschlagformaten wuerde jede laufende Verbindung
toeten.

**Messgrundlage der Diagnose**, damit nichts nachgemessen werden muss:
`token.issued` mit `has_refresh: true` und `expires_in: 300`, dreimal im
Vier-Minuten-Takt; dann `token.upstream_error` mit `invalid_grant` nach 61
Minuten Pause; und drei `/authorize`-Zeilen im Caddy-Zugriffslog **ohne**
`scope=`-Parameter.

## Grenze

Keine eigene Tokenausstellung, kein Signierschluessel, kein JWKS. Das ist
Variante 2 und ausdruecklich verworfen.

Keine Aenderung an `envelope.py`s bestehenden drei Formaten, an `fetcher.py`s
SSRF-Grenzen, an der Pruefreihenfolge in `authorize.py` oder an der
Ressourcentabelle.

Keine Aenderung an `infra`. Die neuen Umgebungsvariablen werden in
`deploy/.env.example` und im Runbook dokumentiert; das Einsetzen in
`compose.prod.yml` und `deploy.env` ist Operatorarbeit und ausdruecklich nicht
Teil dieses Auftrags. Beide haben Standardwerte und muessen nicht gesetzt werden,
damit der Dienst startet.

Kein Anfassen von `wlm.jbaconsult.com` und keiner der sechs Findings F-0329 bis
F-0334.

## Regime

Einzelvariable ist die Aussenankuendigung des Proxies. Beruehrt werden duerfen
`discovery.py`, `authorize.py`, `config.py`, `envelope.py` (additiv: ein vierter
Umschlagtyp) und ein neues Modul fuer `/register`. Alles andere ist Lesestoff.

**Qualitaetsregeln, sie reisen inline und gelten hier:**

1. Jedes Gate bringt eine rote Probe mit. Nimm die Pruefung heraus, zeige das
   Gate rot werden, halte es im Bericht fest. Ein Gate, das nie hat scheitern
   sehen, ist kein Gate.
2. Jede Zusicherung haengt an einem Mechanismus, nie an ihrer Beschreibung.
3. Ein Waechter, der nicht ausgefuehrt wird, ist derselbe Defekt wie ein
   fehlender. Ein uebersprungener Test traegt einen geschriebenen Grund und die
   Bedingung, unter der er wieder laeuft.
4. Eine neue Invariante bekommt ihren Waechter in derselben Aenderung.
5. Das Gate prueft die Ebene unter der Behauptung, und Identitaet vor
   Gesundheit.

**Stoppbedingung.** Verlangt der DCR-Weg eine Entscheidung, die weder in RFC
7591 noch in diesem Auftrag steht -- etwa eine Ablauffrist fuer eine
Registrierung oder das Verhalten bei `POST /register` mit einer bereits
bekannten `redirect_uri` -- wird laut abgebrochen und zurueckgemeldet, nicht
gewaehlt. Dasselbe, wenn die Vorgabe des Scopes eine Bedingung aus dem
bestehenden Test verletzt, die nicht additiv erweiterbar ist.

**Der Return benennt ausdruecklich:** wie die Allowlist auf den DCR-Weg
angewandt wurde und warum; ob die drei bestehenden Umschlagformate unveraendert
geblieben sind; und ob `PROXY_DEFAULT_SCOPE` einen anfordernden Client
tatsaechlich nicht ueberschreibt.

**Abnahme.** Testsuite gruen, drei rote Proben mit beobachtetem rotem Zustand
und je einer Gegenprobe, Container baut und startet, `ci.yml` gruen im Pull
Request, Tag `v0.2.0` erzeugt ein Image in GHCR -- belegt durch die Ausgabe des
Arbeitsablaufs. Zusaetzlich ein End-to-End-Lauf gegen einen lokalen
Falschspieler-OIDC-Anbieter ueber BEIDE Registrierungswege, CIMD und DCR.

Nicht Teil der Abnahme, weil Produktion: die Live-Anmeldung von Claude und
Mistral. Die laeuft der Operator.

## Bericht

Das Logbuch ist wieder erreichbar. Der Return ist ein Logbuch-Objekt,
`SPRINT_173.1` in der Rolle `return`, und KEINE Datei. Falls der
Logbuch-Konnektor in deiner Sitzung keine Verben zeigt, ist das der bekannte
Befund F-0331 -- dann und nur dann als Datei unter
`/Users/johannes/Work/kumbuka.ai/dev/chat-context/handover/` ablegen und im
Ergebnis vermerken, dass der Weg der zweite war.

Drei Pflichtabschnitte, deutsch: `## Ergebnis`, `## Nachweis`, `## Abweichung`.
Im Nachweis fuer JEDE der drei roten Proben beide Laeufe, den gruenen und den
roten. Ein leerer Abweichungsabschnitt ist erlaubt, aber selten wahr -- und eine
Abweichung gehoert auch dann dorthin, wenn sie im Nachweisteil ohnehin sichtbar
wird.
$kbimp$, $kbimp$code$kbimp$, DATE '2026-09-06', TIMESTAMPTZ '2026-09-06T00:00:00Z',
       $kbimp$## Ergebnis

Der Auftrag SPRINT_173.1 ist gebaut. `cimd-proxy` traegt drei additive
Aenderungen, jede fuer sich isoliert nachweisbar; die tragende Regel bleibt in
Kraft (der Proxy liest kein Token, stellt keins aus, keine Datenbank), und
keine der drei bestehenden Umschlagsformate wurde beruehrt. Die Pull Request
ist [Kumbuka-ai/cimd-proxy#2](https://github.com/Kumbuka-ai/cimd-proxy/pull/2),
Zweig `feature/sprint-173-1-cimd-proxy-v0-2-0`. Merge und Tag `v0.2.0` sind
Operatorwerk.

**TEIL 1** — Discovery kuendigt seine Scopes an. `build_discovery_document`
liefert jetzt `scopes_supported`, gespeist aus `PROXY_SCOPES_SUPPORTED`
(Standard `openid offline_access`). Ein leerer Konfigurationswert laesst das
Feld weg statt es leer auszuliefern — leer waere die Aussage „der Server
kennt keinen einzigen Scope", die andere ist Schweigen. Neue Konfiguration
ohne Pflichtcharakter, mit Standardwert.

**TEIL 2** — `PROXY_DEFAULT_SCOPE` fuellt Scope-Schweigen an `/authorize`.
Die Konstante `_UPSTREAM_SCOPE_DEFAULT = "openid"` in `authorize.py` ist
entfernt; der Wert kommt aus der Konfiguration (Standard
`openid offline_access`). Ein Client, der `scope` mitschickt, bleibt
unangetastet — der Proxy erfindet keine Rechte an einer Anforderung vorbei.
Der gewaehlte Scope steht jetzt auf `authorize.forwarded`, damit die Diagnose
den Umweg ueber das Caddy-Zugriffslog nicht mehr braucht.

**TEIL 3** — `POST /register` nach RFC 7591, `registration_endpoint` im
Discovery-Dokument, `client_id_metadata_document_supported: true` bleibt.
Der Proxy spricht ab hier beide Seiten. Die zurueckgegebene `client_id` ist
ein vierter Fernet-Umschlag `RegistrationEnvelope` (TAG `"rg"`), zustandslos;
`/authorize` unterscheidet zwei Formen der `client_id`: eine `https://`-URL
geht den CIMD-Weg (unveraendert), ein Umschlag geht den DCR-Weg
(auspacken, `redirect_uri` gegen die versiegelten `redirect_uris` pruefen).
Alles andere wird typisiert abgewiesen (`invalid_client`). Die Validierung
der Registrierung folgt CIMD-02: `redirect_uris` vorhanden und nicht leer,
kein `client_secret`, kein symmetrisches `token_endpoint_auth_method`;
`client_id_issued_at` wird gesetzt, `client_secret` NIE ausgegeben.

Der Bau beruehrt ausschliesslich `discovery.py`, `authorize.py`, `config.py`,
`envelope.py` (additiv), das neue `register.py`, `app.py` (Router-Einhaengung
und Version), `pyproject.toml`/`__init__.py`/`fetcher.py` (Versionsstempel)
sowie `deploy/.env.example` und `deploy/README.md`. `infra` und
`compose.prod.yml`/`deploy.env` bleiben unangetastet — beide neuen
Umgebungsvariablen haben Standardwerte und muessen nicht gesetzt werden,
damit der Dienst startet.

**Drei ausdruecklich benannte Nachtraege** aus dem Regime des Auftrags:

- *Wie wurde die Allowlist auf den DCR-Weg angewandt und warum?* Der
  Vorschlag des Auftrags ist uebernommen: `CIMD_ALLOWED_DOMAINS` gilt fuer
  den Host jeder `redirect_uri` und wird bei `POST /register` erzwungen
  (loud, `invalid_redirect_uri`). Ein DCR-Client hat keine Domaene, an der
  man ihn festmachen koennte, ausser seinen `redirect_uris`; deren Host ist
  die einzige Groesse, die ein Angreifer nicht frei waehlen kann, ohne die
  Weiterleitung zu verlieren. Am `/authorize`-Ende der Kette wird der
  Umschlag nur noch geoeffnet und die versiegelte Liste als Wahrheit
  akzeptiert — das ist die Rueckseite derselben Muenze und in `register.py`
  und `authorize.py._validate_dcr` erklaert.
- *Sind die drei bestehenden Umschlagformate unveraendert?* Ja. Der
  Fernet-Schluessel rotiert nicht, und die drei Dataclasses
  `AuthorizeEnvelope`, `CodeEnvelope`, `RefreshEnvelope` sind
  feldidentisch. Die Klasse `TestExistingEnvelopesUnchanged` in
  `tests/unit/test_envelope.py` fuehrt einen Round-Trip fuer jede der drei
  durch — eine Aenderung am Feldsatz oder Typ wuerde diese Tests umkippen.
- *Ueberschreibt `PROXY_DEFAULT_SCOPE` einen anfordernden Client?* Nein.
  Belegt in `TestAuthorizeScope::test_client_scope_reaches_upstream_unchanged`
  und `test_client_scope_beats_configured_default` sowie in der Gegenprobe
  `TestRP6DefaultScope::test_gegenprobe_client_scope_not_overridden`: bei
  konfigurierter Vorgabe `openid offline_access profile` und einer Anfrage
  mit `scope=openid` erreicht den Upstream genau `openid` — die Vorgabe
  fuellt nur Schweigen.

Beide Wege (CIMD und DCR) laufen im erweiterten End-to-End-Test
`tests/e2e/test_full_flow.py` gegen einen lokalen Falschspieler-OIDC-Anbieter
(respx-Mock) durch: `/register` → `/authorize` → `/callback` → `/token` →
Refresh. Der Container ist lokal gebaut (`cimd-proxy:v0.2.0-local`) und
gestartet; drei Probeaufrufe (`/.well-known/oauth-authorization-server`,
`POST /register` happy, `POST /register` mit `client_secret_post`) lieferten
die erwarteten Antworten.

## Nachweis

**Testsuite** (im Worktree, gegen den v0.2.0-Code): `169 passed, 7 xfailed`.
Die 7 xfailed sind die roten Kontrollen aller sieben roten Proben — vier
aus v0.1.0 (RP1–RP4) und die drei aus diesem Sprint (RP5–RP7), jeweils in
ihrem beobachteten roten Zustand.

**Rote Probe RP5 — `tests/red_probes/test_rp5_scopes_supported.py`.**

- *guarded_announces* (gruen): mit `PROXY_SCOPES_SUPPORTED="openid
  offline_access"` traegt das Discovery-Dokument `offline_access` in
  `scopes_supported`. Der Container liefert im Laufversuch
  `"scopes_supported":["openid","offline_access"]`.
- *bypass_omits* (gruen): mit `PROXY_SCOPES_SUPPORTED=""` fehlt der
  gesamte Schluessel `scopes_supported` im Dokument.
- *bypass_does_not_ship_empty_array* (gruen, Gegenprobe): ein leerer
  Konfigurationswert liefert kein `scopes_supported: []` — der Test
  weist explizit nach, dass `doc.get("scopes_supported") != []`.
- *bypass_would_break_announcement_gate* (**xfail strict, beobachteter
  roter Zustand**): mit `PROXY_SCOPES_SUPPORTED=""` behauptet der Test
  weiterhin `offline_access in doc["scopes_supported"]` — das kippt und
  wird strikt als Fehlschlag erwartet, weil der Schluessel gar nicht
  mehr im Dokument ist. Der Testlauf berichtet den Fehlschlag
  ausdruecklich als XFAIL mit Grund.

**Rote Probe RP6 — `tests/red_probes/test_rp6_default_scope.py`.**

- *guarded_defaults* (gruen): mit `PROXY_DEFAULT_SCOPE="openid
  offline_access"` erreicht die Anfrage ohne `scope` den Upstream mit
  genau `scope=openid offline_access`.
- *bypass_defaults_shrunk* (gruen): mit `PROXY_DEFAULT_SCOPE="openid"`
  erreicht dieselbe Anfrage den Upstream mit `scope=openid` — der Umstand,
  den die Diagnose von 2026-09-06 in Produktion gesehen hat.
- *gegenprobe_client_scope_not_overridden* (gruen): mit fetter Vorgabe
  `openid offline_access profile` UND `scope=openid` in der Anfrage sieht
  der Upstream `scope=openid` — Vorgabe ueberschreibt Client nicht.
- *bypass_would_break_default_gate* (**xfail strict, beobachteter roter
  Zustand**): mit `PROXY_DEFAULT_SCOPE="openid"` behauptet der Test
  weiterhin `scope=openid offline_access` beim Upstream — kippt, wird
  strikt als Fehlschlag erwartet.

**Rote Probe RP7 — `tests/red_probes/test_rp7_dcr_auth_method.py`.**

- *guarded_refuses_client_secret_post* (gruen): eine Registrierung mit
  `token_endpoint_auth_method: client_secret_post` wird 400 mit
  `invalid_client_metadata` abgewiesen.
- *bypass_admits_none* (gruen, Gegenprobe & „traegt bis 302"): eine
  Registrierung mit `token_endpoint_auth_method: none` wird 201
  angenommen, die zurueckgegebene `client_id` (der Fernet-Umschlag) wird
  an `/authorize` weitergegeben und der Aufruf endet mit 302 auf
  `https://issuer.example/…` — also bis zum Upstream.
- *bypass_would_break_auth_method_gate* (**xfail strict, beobachteter
  roter Zustand**): der Test behauptet `r.status_code != 400` bei der
  `client_secret_post`-Registrierung. Solange der Waechter greift,
  faehrt der Test rot (das ist der beobachtete rote Zustand). Wuerde
  der Waechter je stumm aufgeben, kippt der Test in Gruen und die
  strikt-xfail-Marke faellt loud auf.

**Live-Nachweis am gestarteten Container** (`cimd-proxy:v0.2.0-local`,
Port 18089):

    GET  /.well-known/oauth-authorization-server →
      "scopes_supported":["openid","offline_access"]
      "registration_endpoint":"https://x.example/register"

    POST /register  {"redirect_uris":["https://claude.ai/cb"],
                     "token_endpoint_auth_method":"none"} → 201
      { "client_id":"gAAAAABqnXjB…", "client_id_issued_at":1788704961,
        "redirect_uris":["https://claude.ai/cb"],
        "token_endpoint_auth_method":"none",
        "grant_types":["authorization_code","refresh_token"],
        "response_types":["code"] }

    POST /register  {..., "token_endpoint_auth_method":"client_secret_post"}
      → 400
      {"error":"invalid_client_metadata",
       "error_description":"token_endpoint_auth_method 'client_secret_post'
                            relies on a shared symmetric secret"}

Kein `client_secret` in der Antwort, der Umschlag ist ein Fernet-Blob
(`gAAAAA…`), die Verweigerung tippt genau das benannte OAuth-Fehlerwort.

**End-to-End**: `tests/e2e/test_full_flow.py` fuehrt jetzt
`test_full_flow` (CIMD) UND `test_full_flow_dcr` (DCR). Beide durchlaufen
die volle Kette `/authorize` → `/callback` → `/token` → Refresh gegen einen
respx-gemockten Upstream. Beide gruen.

**Lint und Format**: `ruff check src/ tests/` clean, `ruff format --check
src/ tests/` clean.

**Container**: `docker build -t cimd-proxy:v0.2.0-local -f Dockerfile .`
erfolgreich, Startversuch mit `docker run` liefert die drei oben zitierten
Antworten.

**Pull Request**: Kumbuka-ai/cimd-proxy#2. Die Ratifizierung des Merges
und die Erzeugung des Tags `v0.2.0` (und damit des GHCR-Images) sind
Operatorwerk.

## Abweichung

**Keine Abweichung am Auftragskern.** Die drei Bauteile sind so gebaut,
wie der Auftrag sie beschreibt; keine der ausdruecklichen Grenzen ist
beruehrt (keine eigene Tokenausstellung, keine Datenbank, keine Aenderung
an `envelope.py`s bestehenden drei Formaten, an `fetcher.py`s SSRF-Grenzen
oder an der Pruefreihenfolge in `authorize.py`; keine Aenderung an `infra`;
`wlm.jbaconsult.com` und die sechs Findings F-0329 bis F-0334 nicht
angefasst).

**Ein bewusst getroffener Bau-Entscheid mit Begruendung** (nicht Abweichung,
aber der Auftrag verlangt die Nennung): die Allowlist wird auf den DCR-Weg
**am Registrierungsschluss** angewandt (`POST /register`), nicht erneut
beim `/authorize`. Die Rueckseite dieser Wahl: eine spaetere Verschaerfung
der Allowlist stoert existierende Registrierungen zunaechst nicht, weil ihre
`redirect_uris` bereits im Umschlag versiegelt sind. Ob das ein Vorteil
oder ein Waechterverlust ist, ist eine Concept-Frage — ich habe sie in
`authorize.py._validate_dcr` in einem Modulkommentar als offen markiert
statt sie unilateral zu entscheiden. Eine spaetere Ratifizierung „nein,
`/authorize` prueft die Allowlist im DCR-Weg noch einmal" ist rein
additiv und wuerde denselben Waechter aus einer anderen Ecke des Codes
noch einmal aufrufen.

**Ein Regime-Zusatz**, den ich nicht als Abweichung, sondern als Fuellung
einer Regime-Luecke ansehe: der Bau lehnt in `POST /register` auch nicht-
symmetrische Auth-Methoden (`private_key_jwt`, `tls_client_auth` …) ab
und laesst nur `none` durch. RFC 7591 laesst diese Werte technisch zu, aber
der Proxy unterstuetzt nur oeffentliche Clients mit PKCE, und ein
angenommener Wert ausserhalb `none` waere ein Versprechen, das der Proxy
spaeter nicht einloesen wuerde. Fail-loud vor Versprechen-halten.

**Kein Waechterverlust in dieser Aenderung, aber ein Sonar-Nachfolgezyklus
im PR** (das ist normale PR-Hygiene, kein Substanzfehler): der erste Push
liess drei MINOR/MAJOR-Code-Smells (`S5713`, `S9073`, `S5778`) durchgehen,
die den Schwellenwert der neuen-Code-Code-Smells knapp ueberschritten
haben. Der Nachschub-Commit `f947659` entfernt eine redundante
`UnicodeDecodeError`-Klausel, trennt eine zusammengesetzte Assertion und
zieht `pack_refresh` aus einem `pytest.raises`-Block heraus. Sonar meldet
danach gruen, Testsuite unveraendert `169 passed / 7 xfailed`.

**Nicht Teil der Abnahme dieses Sprints, weil Produktion**: die
Live-Anmeldung von Claude und Mistral gegen die neue Discovery + DCR. Der
Auftrag hat das ausdruecklich zur Operatorarbeit erklaert; hier nur zur
Aktenlage nochmal genannt.
$kbimp$, TIMESTAMPTZ '2026-09-06T00:00:00Z',
       $kbimp${"source": "sprints/173/SPRINT_173.1-dispatch.md", "task": ["FEAT-95"]}$kbimp$::jsonb, $kbimp${"source": "sprints/173/SPRINT_173.1-return.md"}$kbimp$::jsonb;

-- sprint 174.0
INSERT INTO dispatch.exchange
    (tenant_id, scope_id, selector_id, number, sub, addendum_suffix, status,
     title, dispatch_body, apparatus, dispatch_date, sent_at,
     return_body, ratified_at, dispatch_metadata, return_metadata)
SELECT 'a7b072e4-89dd-473e-acc0-91a98a8bae7a'::uuid, '0845a29c-9b55-4405-a445-7849416731b8'::uuid,
       (SELECT s.id FROM dispatch.selector s
         WHERE s.tenant_id = 'a7b072e4-89dd-473e-acc0-91a98a8bae7a'::uuid AND s.scope_id = '0845a29c-9b55-4405-a445-7849416731b8'::uuid
           AND s.name = 'sprint'),
       174, 0, NULL, 'consumed',
       $kbimp$Datenmigration des Steuerungskorpus in den neuen Dispatch-Dienst: ein SQL-Importskript, das der Operator selbst faehrt$kbimp$, $kbimp$## Auftrag

Den Steuerungskorpus vom Dateispeicher des Vorgaengerdienstes in die Tabellen
des neuen Dispatch-Dienstes ueberfuehren. Ergebnis dieses Sprints ist ein
SQL-Importskript, das der Operator selbst gegen die Datenbank faehrt -- nicht
der Vollzug der Migration.

Danach steht der neue Dienst als MCP-Zugang zur Verfuegung, und der alte bleibt
als Rueckfall bestehen.

## Lage

Der alte Dienst (`Kumbuka-ai/worklist-deprecated`) haelt Objekte als Markdown
mit Frontmatter unter `<root>/<N>/<PREFIX><N>.<M>-<role>.md`, zwei Wurzeln:
`sprints/` mit Praefix `SPRINT_` und `satellite/` mit `SATELLITE_`. Findings
liegen flach unter `findings/F-<n>.md` mit einer Hochwassermarke daneben.

Der neue Dienst (`Kumbuka-ai/kumbuka-dispatch`, Schema `dispatch`, Flyway V1..V9
auf Prod) haelt drei Tabellen: `selector`, `number_circle`, `exchange`.

**Es ist keine Kopie, sondern eine Modelltransformation.** `V4__domain.sql` sagt
es in eigener Sache: der Auftrag und seine Antwort sind zwei Rollen EINER
Identitaet, nicht zwei Objekte. Aus zwei Dateien mit je eigenem Status wird eine
Zeile mit einer Statusspalte und zwei Feldgruppen.

**Eine Korrektur zur Ausgangslage.** Die Annahme, Satelliten teilten sich einen
Nummernkreis mit den Sprints, ist falsch. `store.py` fuehrt getrennte Raeume je
Art -- ein Satellit startet bei 1, waehrend der Sprintraum bei 139 steht. Was
wie ein geteilter Kreis aussieht, ist ein Feldname: das Frontmatter nennt die
Nummer bei beiden Arten `sprint`. Das Zielmodell repariert das strukturell, weil
dort `selector` und `number` getrennt sind. Es ist nichts umzunummerieren.

## Frame

Sechs Festlegungen, alle am 2026-09-06 ratifiziert. Der Bau trifft keine davon
neu.

**Findings wandern nicht.** Sie gehen spaeter nach D-CTX und werden bis dahin
auf Dateisystembasis gefuehrt. Das neue Schema hat fuer sie keine Tabelle, und
eine anzulegen waere eine Schemaerweiterung und keine Migration.

**Die Migration schneidet bei Sprint 172 ab.** Sprint 173 ist offen -- er wurde
am 2026-09-06 eroeffnet und der Bau laeuft. Er wandert spaeter als einzelner
Nachtrag. Die Alternative, den Build abzuwarten, haette das Skript blockiert.

**Mandantenfelder sind Konstanten**, aus der Scope-Tabelle gemessen:
`tenant_id = a7b072e4-89dd-473e-acc0-91a98a8bae7a`,
`scope_id  = 0845a29c-9b55-4405-a445-7849416731b8`.

**`sent_at` wird aus dem `date`-Feld abgeleitet**, 00:00:00Z. Die Quelle kennt
keinen Sendezeitpunkt, und `ck_sent_when_past_draft` verlangt einen fuer jeden
Nicht-Entwurf. Eine Erfindung, aber eine deklarierte und rueckrechenbare.

**Beim Statuszusammenfall gewinnt `consumed` gegen `closed`.** Der alte
`close_sprint_check` laesst Dispatch und Return ausdruecklich verschiedene
terminale Status tragen; das Zielmodell hat eine Spalte. `consumed` ist die
informativere Aussage, weil sie besagt, dass Inhalt weiterkuratiert wurde. Der
Verlust ist benannt, und der alte Dienst bleibt als Rueckfall stehen.

**`task` wandert nach `dispatch_metadata`, `ratified` wandert nicht.** Die
Metadaten-Regel des Zielschemas laesst nur Adressen und Kennungen zu, keine
Aussagen. Eine Ratifizierungsliste ist eine Aussage. `curated-in` ist vertagt,
bis die variablen Felder und die Scope-Settings definiert sind.

## Grenze

Nicht in diesem Sprint: der Vollzug der Migration -- das Skript wird geliefert,
gefahren wird es vom Operator. Nicht in diesem Sprint: die Findings. Nicht in
diesem Sprint: Sprint 173. Nicht in diesem Sprint: die Umstellung des
MCP-Zugangs auf den neuen Dienst, die ohnehin an dessen fehlender OAuth-Kontur
haengt (404 auf `/.well-known/oauth-protected-resource`, 405 statt 401 auf
`/mcp`). Nicht in diesem Sprint: eine Aenderung am Schema des neuen Dienstes.
$kbimp$, $kbimp$concept$kbimp$, DATE '2026-09-06', TIMESTAMPTZ '2026-09-06T00:00:00Z',
       $kbimp$## Was der Sprint erreicht hat

**Der Kernauftrag ist erfuellt.** Das SQL-Importskript ist gebaut, gegen ein
Wegwerf-Postgres bewiesen und am 2026-09-06 vom Operator gegen die produktive
Datenbank gefahren worden. 278 Austausche stehen in `dispatch.exchange`, 254
unter dem Selektor `sprint` und 24 unter `satellite`. Die Gegenprobe
unmittelbar danach hat dieselben Zahlen gezaehlt. Einzelheiten im Ruecklauf
SPRINT_174.1.

**Der Dienst ist erreichbar.** Was das Briefing als Kontur-Defekt beschrieb --
404 auf das Ressourcendokument, 405 statt 401 auf `/mcp` -- ist ueberholt. Der
Dispatch-Dienst nimmt heute Token aus dem Mandanten-Realm entgegen, validiert
sie ueber JWKS, prueft die Kapazitaet und bindet den Mandanten. Die Kette ist
vollstaendig in Produktion verifiziert, nicht abgeleitet.

**Und er ist lesbar.** Der Bestand war nach dem Import zwar da, aber jeder
Lesezugriff endete in einem HTTP 500. Der Fix ist gebaut, ausgerollt und gegen
`sprint/151.0` in Produktion belegt -- eine Zeile, die Listen in beiden
Metadatenfeldern traegt und vorher zuverlaessig toetete.

## Was sonst geschah

Der Sprint hat mehr getragen als sein Auftrag, weil ein Betriebsversuch die
Reihenfolge diktiert hat.

**Der Deploy-Stopp.** Das Ausrollen von cimd-proxy v0.2.0 brach ab, weil der
Owner-Normalisierungslauf das Material aus einem privaten Repositorium nicht
holen konnte. Ursache war ein Repository-Scope am Wirt-Token, nicht ein
fehlender Pfad: die Datei lag am erwarteten Ort, und derselbe Lauf hatte
`deploy.cfg` ueber denselben Aufruf erfolgreich aus einem anderen privaten
Repositorium geholt. Der Token wurde auf ein fein granuliertes PAT mit
Lesezugriff umgestellt; der Release lief danach vollstaendig durch, samt
`sync_ist`.

Nebenbei fiel eine unversionierte Handkopie des Sweep-Skripts auf dem Wirt auf
-- Rueckstand eines frueheren Notgriffs, in keinem Repositorium, von keinem Pull
erreicht. Sie wurde bewusst erst nach der Token-Reparatur zur Entfernung
freigegeben.

**Die Mistral-Verbindung.** Der Live-Versuch belegte alle drei Teile von
cimd-proxy v0.2.0 in Produktion: die Ankuendigung im Discovery-Dokument, die
dynamische Registrierung mit `register.issued`, und die Vorgabe im Refresh --
letztere durch eine Nachtpause von rund zwoelf Stunden, nach der ein Aufruf ohne
Neuanmeldung durchging. Das ist ein staerkerer Beweis als der geplante Test mit
61 Minuten.

Zwei Huerden traten dabei zutage. Der Allowlist-Waechter wies
`callback.mistral.ai` ab -- korrekt, denn die Liste war fuer den CIMD-Weg
gepflegt und prueft beim DCR-Weg eine andere Groesse. Der Host wurde exakt
aufgenommen, kein Platzhalter. Danach wies der Ressourcenabgleich eine URI ab,
die sich nur durch einen abschliessenden Schraegstrich unterschied. Der Client
verhaelt sich dabei korrekt; der Abgleich vergleicht zeichengleich, obwohl die
Tabellenseite bereits normalisiert wird. Beauftragt als SATELLITE_15.0.

**Der Mandanten-Realm.** Der Dispatch-Dienst haengt per Entwurf am Mandanten-
und nicht am Betreiber-Realm; ein Token aus dem Betreiber-Realm wird dort ueber
die Audience abgewiesen, und das ist die Absicht. Was fehlte, war der Weg
dorthin. Angelegt wurden zwei Clients -- `kumbuka-dispatch` als reine
Audience-Zielscheibe und `dispatch-mcp` als oeffentlicher Client mit PKCE -- und
zwei Kapazitaetsrollen, `dispatch-console` und `dispatch-executor`. Beide
Clients auch in der kuratierten Realm-Quelle.

Der Dienst verweigerte danach mit einer typisierten Begruendung, die
ausdruecklich festhaelt, dass die Kapazitaet nicht per Vorgabe gesetzt wird,
weil eine Berechtigung sonst durch Auslassung entschieden waere. Das ist die
Bauart, die dieser Sprint an mehreren anderen Stellen vermisst hat.

**Der Regimewechsel.** Zwischen der Abschaltung des alten Logbuch-Dienstes und
dem Rollout des Metadaten-Fixes war die Steuerungsebene ohne Verben. Sie lief in
dieser Zeit ueber Dateien im Klon `Kumbuka-ai/steering`. Das hat getragen, weil
der Zustand im Frontmatter steht und nicht nur in einer Datenbank -- ein
Uebergang ist dort eine Textaenderung. Die Nummernvergabe und das Abschlussgatter
fielen weg und wurden von Hand gehalten.

## Befunde

- **F-0335** -- der Owner-Normalisierungslauf meldete gruen, obwohl alle vier
  Tabellen des `dispatch`-Schemas der migrierenden Rolle gehoeren. Reichweite
  seiner Objektmenge ungeklaert.
- **F-0336** -- `platform.scope_access` verbindet ueber die Mandantenkennung
  statt ueber eine Mitgliedschaft; jedes aktive Konto sieht alle Projekt-Scopes
  seines Mandanten.
- **F-0337** -- `release.sh` nennt den unveraenderten IST nach einem Abbruch
  wahr, obwohl der Container bereits umgeschaltet ist; dazu die verschluckte
  Diagnose im Sweep.
- **F-0338** -- der erzeugte Kopf des Importskripts behauptet einen Schnitt, den
  das Skript nicht mehr einhaelt, und wuerde den Nummernkreis auf eine belegte
  Nummer setzen.

F-0337 und F-0338 sind dieselbe Klasse: eine Beschreibung, die als Zusicherung
gelesen wird und keine ist. Zwei Repositorien, zwei Tage.

Im Sprint aufgetreten und ohne Nummer geblieben, weil mit ihrem Anlass erledigt:
die irrefuehrende Konnektor-Meldung "server isn't responding" fuer einen HTTP
500, die eine Diagnoserunde gekostet hat; der leere Lesevorgang auf
`platform.scope_access` als Superuser, der wie ein leerer Bestand aussah und ein
Messartefakt war; und ein `LOGIN_ERROR` mit `user_not_found`, dessen Realm
nachweislich vollstaendig war.

## Was offen bleibt

**Drei Auftraege stehen beauftragt und unbearbeitet:**

- SATELLITE_15.0 -- Normalisierung der angefragten Ressourcen-URI im Proxy
  (BUG-51).
- SATELLITE_17.0 -- Nachtrag des Steuerungskorpus ueber den Schnitt bei 172
  hinaus (CHORE-357).

**Der Bestand ist unvollstaendig.** Sprint 173, Sprint 174 und die Satelliten 15
bis 17 liegen ausschliesslich als Dateien im Klon. Sprint 175 beginnt damit auf
einem Bestand, dem die letzten drei Sprints fehlen. Das ist bewusst so und wird
mit SATELLITE_17.0 aufgeloest.

**Zwei Handgriffe am Realm** sind offen, weil der Konnektor sie nicht setzen
kann: die PKCE-Erzwingung an `dispatch-mcp` und das Abschalten des
Standard-Flows an `kumbuka-dispatch`. In der Realm-Quelle stehen beide korrekt.

**Die Hostfrage ist unentschieden.** Der Dienst sitzt auf einer Betreiberdomaene,
waehrend der vorgesehene Block am Plattform-Ingress deaktiviert danebenliegt.
Die heutige Lage ist ein bewusst reversibler Vorgriff auf dem Wirt und kein
Dauerzustand.

**Die Metadaten-Doktrin haengt an einer Schleuse.** Der Dienst laesst
`Map<String, Object>` zu und prueft die Tiefe beim Senden. Ein Schreibpfad, der
daran vorbeigeht, ist nicht gebunden. Fuer den Nachtrag ist die Regel als
eigenes Abnahmekriterium in SATELLITE_17.0 gesetzt; darueber hinaus ist sie
nirgends erzwungen.
$kbimp$, TIMESTAMPTZ '2026-09-07T00:00:00Z',
       $kbimp${"source": "sprints/174/SPRINT_174.0-dispatch.md", "task": ["CHORE-356"]}$kbimp$::jsonb, $kbimp${"source": "sprints/174/SPRINT_174.0-return.md"}$kbimp$::jsonb;

-- sprint 174.1
INSERT INTO dispatch.exchange
    (tenant_id, scope_id, selector_id, number, sub, addendum_suffix, status,
     title, dispatch_body, apparatus, dispatch_date, sent_at,
     return_body, ratified_at, dispatch_metadata, return_metadata)
SELECT 'a7b072e4-89dd-473e-acc0-91a98a8bae7a'::uuid, '0845a29c-9b55-4405-a445-7849416731b8'::uuid,
       (SELECT s.id FROM dispatch.selector s
         WHERE s.tenant_id = 'a7b072e4-89dd-473e-acc0-91a98a8bae7a'::uuid AND s.scope_id = '0845a29c-9b55-4405-a445-7849416731b8'::uuid
           AND s.name = 'sprint'),
       174, 1, NULL, 'consumed',
       $kbimp$COWORK: SQL-Importskript bauen -- Steuerungskorpus bis Sprint 172 nach dispatch.exchange, mit Trockenlauf und fuenf Nachbedingungen$kbimp$, $kbimp$## Auftrag

**Ausfuehrungsort ist Cowork, nicht Claude Code.** Das Feld `apparatus` steht auf
`code`, weil das Vokabular nur `code`, `concept` und `design` kennt; ein Wert
fuer Cowork existiert nicht. Wer diesen Auftrag aus `next_open_dispatch` zieht
und Claude Code ist: **stehen lassen.** Parallel laeuft SPRINT_173.1, und das
ist der Auftrag fuer Code.

Liefere **ein einziges SQL-Skript**, das den Steuerungskorpus des
Vorgaengerdienstes in die Tabellen des neuen Dispatch-Dienstes ueberfuehrt. Du
faehrst es NICHT. Der Operator fuehrt es selbst gegen die Produktionsdatenbank
aus.

Ablage: `Kumbuka-ai/kumbuka-dispatch`, neuer Pfad `migration/`, Dateiname
`001-import-steering-corpus.sql`. Dazu eine `migration/README.md` mit Ablauf,
Reihenfolge und Rueckbau -- **der Rueckbau steht vor dem Hinweg**.

Zweigname `chore/356-import-steering-corpus`, Pull Request gegen `main`, kein
Merge.

## Lage: die Quelle

Repository `Kumbuka-ai/worklist-deprecated`, gelesen am 2026-09-06.

Objekte liegen als Markdown mit YAML-Frontmatter:

    sprints/<N>/SPRINT_<N>.<M>-<role>.md
    satellite/<N>/SATELLITE_<N>.<M>-<role>.md

`role` ist `dispatch` oder `return`. Die beiden Wurzeln haben **unabhaengige
Nummernraeume** -- Satellit 14 hat nichts mit Sprint 14 zu tun.

Frontmatter-Felder, wie `store.py` und `logbook_schema` sie fuehren: `id`,
`kind`, `sprint` (die Nummer innerhalb der Art), `sub`, `role`, `status`,
`apparatus`, `date`, `title`, optional `task`, `ratified`, `curated-in`. Der
Rumpf ist die Prosa unter dem Frontmatter.

Findings unter `findings/F-<n>.md` samt Datei `HWM`.

Den Klon bekommst du vom Operator, oder du liest die Struktur aus dem
Repository. Ein Skript, das gegen eine vermutete Verzeichnisstruktur generiert
wird, ist wertlos -- lies, was da ist, bevor du generierst.

## Lage: das Ziel

Repository `Kumbuka-ai/kumbuka-dispatch`, Schema `dispatch`, Flyway V1..V9 auf
Produktion. Drei Tabellen, deren Definition du in
`backend/src/main/resources/db/migration/` liest, bevor du eine Zeile schreibst:

`dispatch.selector` -- deklarierter Bracketname je Scope. `name` muss auf
`^[a-z][a-z0-9-]{0,62}$` passen; `sprint` und `satellite` passen.

`dispatch.number_circle` -- ein Zaehler je (tenant, scope, selector),
`next_number >= 1`.

`dispatch.exchange` -- EINE Zeile je Austausch mit zwei Feldgruppen. Der Auftrag
in `title`, `body`, `apparatus`, `dispatch_date`, `sent_at`,
`dispatch_metadata`; die Antwort in `handover_body`, `ratified_at`,
`handover_metadata`. Eine Statusspalte mit neun zulaessigen Werten.

## Die Abbildung

Ein Paar aus der Quelle wird EINE Zeile im Ziel:

    SPRINT_171.1-dispatch.md  ->  body, title, apparatus, dispatch_date
    SPRINT_171.1-return.md    ->  handover_body, ratified_at

    selector          = 'sprint' bzw. 'satellite' -- aus der WURZEL, nicht aus
                        dem Feldnamen; das Frontmatter nennt beide `sprint`
    number            = das Feld `sprint`
    sub               = das Feld `sub`
    addendum_suffix   = NULL (die Quelle kennt keine Addenda)
    tenant_id         = 'a7b072e4-89dd-473e-acc0-91a98a8bae7a'
    scope_id          = '0845a29c-9b55-4405-a445-7849416731b8'
    sent_at           = <date>T00:00:00Z
    ratified_at       = <date des Returns>T00:00:00Z, NULL ohne Return
    dispatch_metadata = {"task": [...]} aus dem Feld `task`, sonst NULL
    handover_metadata = NULL

**Status.** Beide Rollen tragen je einen terminalen Status, das Ziel hat eine
Spalte: traegt eine der beiden Rollen `consumed`, wird die Zeile `consumed`,
sonst `closed`.

**Ein Dispatch ohne Return** wird eine Zeile mit `handover_body IS NULL` und
`ratified_at IS NULL`. Zulaessig und normal.

## Frame

Sechs Festlegungen, am 2026-09-06 ratifiziert. Du triffst keine davon neu.

**Findings wandern nicht.** Sie gehen spaeter nach D-CTX und werden bis dahin
auf Dateisystembasis gefuehrt. Das Zielschema hat fuer sie keine Tabelle, und
eine anzulegen waere eine Schemaerweiterung.

**Nur Sprints bis einschliesslich Nummer 172.** Sprint 173 ist offen und wird
NICHT migriert. Satelliten sind davon nicht betroffen und wandern vollstaendig.

**Die Mandantenfelder sind Konstanten**, aus der Scope-Tabelle gemessen. Du
leitest sie nicht her und suchst sie nicht.

**`sent_at` wird aus `date` abgeleitet**, 00:00:00Z. Die Quelle kennt keinen
Sendezeitpunkt, und `ck_sent_when_past_draft` verlangt einen fuer jeden
Nicht-Entwurf. Eine deklarierte, rueckrechenbare Erfindung.

**Beim Statuszusammenfall gewinnt `consumed` gegen `closed`.** Der Verlust ist
benannt; der alte Dienst bleibt als Rueckfall bestehen.

**`ratified` wandert nicht.** Die Metadatenregel des Zielschemas laesst nur
Adressen und Kennungen zu, keine Aussagen. Nicht erfinden, nicht in den Rumpf
kopieren, nicht in `handover_metadata` stopfen. `curated-in` ist vertagt.

## Regime

Einzelvariable ist der Import des Steuerungskorpus. Nichts anderes wird
angefasst.

**Zeile 1 des Skripts ist `SET app.tenant_id`.** Alle drei Zieltabellen stehen
auf `ENABLE` **und** `FORCE ROW LEVEL SECURITY` -- die Politik greift auch gegen
den Tabelleneigentuemer. Ohne dieses Setzen fuegt der Import null Zeilen ein und
meldet dabei nichts. Das ist der teuerste stille Fehlschlag in diesem Auftrag.

**Trockenlauf zuerst.** Das Skript laeuft vollstaendig unter `BEGIN; ...
ROLLBACK;` und meldet dabei die Zeilenzahlen je Selektor. Der Operator sieht die
Zahlen, bevor etwas bleibt. Der Unterschied zwischen Probe und Ernstfall ist
genau ein Wort am Ende.

**Idempotent.** Ein zweiter Lauf verdoppelt nichts. `ON CONFLICT` auf
`uq_exchange_address`, `uq_selector_name` und `uq_circle`.

**Nummernkreise nach dem Import setzen**, auf das Maximum je Selektor plus eins.
Vergessen heisst, dass die erste neue Vergabe mit einer importierten Adresse
kollidiert -- und Adressen sind ausgegeben.

**Fuenf Nachbedingungen, jede mit `RAISE EXCEPTION`.** Ein Importskript ohne
Nachbedingung ist eine Behauptung:

1. Zeilenzahl je Selektor gleich der Zahl der Quellpaare je Wurzel.
2. Jede Zeile mit `status <> 'draft'` hat `sent_at IS NOT NULL`.
3. `number_circle.next_number` je Selektor groesser als jede importierte
   `number` desselben Selektors.
4. Kein `status` ausserhalb `('closed','consumed')` im migrierten Bestand.
5. Keine Zeile mit `handover_body IS NOT NULL` und `ratified_at IS NULL`.

**Keine Schemaaenderung.** Kein `CREATE TABLE`, kein `ALTER TABLE`, kein
`DROP TRIGGER`, kein `DISABLE ROW LEVEL SECURITY`. Steht eine Zielbedingung dem
Import im Weg, ist das ein Befund und wird gemeldet -- nicht abgeschaltet. Der
Freeze-Trigger stoert nicht: er ist `BEFORE UPDATE`, nicht `BEFORE INSERT`.

**Stoppbedingung.** Findest du einen Fall, den die Abbildung nicht deckt -- ein
Objekt ohne `apparatus`, einen Status ausserhalb der neun Zielwerte, ein Paar
mit widerspruechlichen Daten, ein `sub` ohne zugehoerige `.0`, ein
Nummernverzeichnis, dessen Name nicht dem `sprint`-Feld entspricht -- dann brich
laut ab und melde ihn. Waehle nicht. Erfinde keinen Standardwert.

## Grenze

Kein Ausfuehren des Skripts. Keine Verbindung zur Produktionsdatenbank. Keine
Aenderung an `Kumbuka-ai/worklist-deprecated`. Keine Findings. Kein Sprint 173.
Keine Aenderung am Schema oder am Code des neuen Dispatch-Dienstes. Kein
`infra`. Und ausdruecklich nicht SPRINT_173.1 -- das ist ein anderer Auftrag an
einen anderen Ausfuehrungsort.

## Bericht

Return als Logbuch-Objekt `SPRINT_174.1` in der Rolle `return`, Korpus
`kumbuka`. Zeigt dein Logbuch-Konnektor keine Verben, lege den Bericht als Datei
unter `/Users/johannes/Work/kumbuka.ai/dev/chat-context/handover/` ab und
vermerke im Ergebnis, dass der Weg der zweite war.

Drei Pflichtabschnitte, deutsch:

**`## Ergebnis`** -- Zweigname, PR-Nummer, Pfad des Skripts, und in einem Satz,
was es tut.

**`## Nachweis`** -- die Zaehlung der Quelle, die du tatsaechlich gelesen hast:
Anzahl Nummernverzeichnisse je Wurzel, Anzahl Dateien, Anzahl Paare, Anzahl
alleinstehender Dispatches, die Menge der vorgefundenen Statuswerte. Dazu die
Ausgabe eines Trockenlaufs, falls du eine Testdatenbank hattest -- und falls
nicht, ausdruecklich, dass keiner lief.

**`## Abweichung`** -- jeder Fall, den die Abbildung nicht deckte, jede Annahme,
die du treffen musstest, und jede Stelle, an der die Quelle anders aussah als
hier beschrieben. Auch dann, wenn es im Nachweisteil ohnehin sichtbar wird.
$kbimp$, $kbimp$code$kbimp$, DATE '2026-09-06', TIMESTAMPTZ '2026-09-06T00:00:00Z',
       $kbimp$## Ergebnis

Zweig `chore/356-import-steering-corpus`, Pull Request **#9** gegen `main` in
`Kumbuka-ai/kumbuka-dispatch`. Der Auftrag verbot den Merge; der Operator hat
ihn nach dem Produktionslauf am 2026-09-06 selbst vollzogen. Drei Dateien unter
`migration/`: `001-import-steering-corpus.sql` (3.912.122 Bytes, SHA-256
`e74b6b363d0f9aeca14eb1e54409c3e3dd19719aaac6add58c7233519db17a5a`),
`README.md` und `generate-import.py`.

Das Skript ueberfuehrt **278 Austausche** des Steuerungskorpus in
`dispatch.selector`, `dispatch.number_circle` und `dispatch.exchange` -- 254
unter dem Selektor `sprint`, 24 unter `satellite` -- in einer Transaktion, die
auf `ROLLBACK` endet, bis der Operator genau dieses eine Wort tauscht.

Der Import ist am 2026-09-06 vom Operator gegen die Produktionsdatenbank
gefahren worden und steht. Die Gegenprobe unmittelbar danach hat weiterhin 254
und 24 gezaehlt. Offen und nicht Teil dieses Ruecklaufs: der Zeigerhub des
Superrepos auf den neuen Stand des Submoduls.

## Nachweis

Die Quelle wurde gelesen, nicht vermutet, und zwar gegen den Klon
`Kumbuka-ai/steering` auf `6e97f75` sowie gegen `chat-context/sprints/` im
Superrepo.

**Nummernverzeichnisse.** Wurzel `sprints/` haelt 137 bis 174; im Schnitt
(kleiner gleich 172) liegen **36 Verzeichnisse**. Wurzel `satellite/` haelt 1
bis 14, also **14 Verzeichnisse**, vollstaendig im Schnitt. Sprints 1 bis 136
liegen nicht in diesem Repository, sondern flach als **140 Rekorddateien**
unter `chat-context/sprints/`.

**Dateien.** 224 Objektdateien in den 36 Sprintverzeichnissen, 44 in den 14
Satellitenverzeichnissen, 140 Altrekorde. 93 Findings unter `findings/` wurden
gelesen und nicht angefasst.

**Paare und Einzelstuecke.** Aus den beiden Steuerungswurzeln: 109 vollstaendige
Paare unter `sprint` und 20 unter `satellite`. Acht Auftraege ohne Ruecklauf --
SPRINT_149.1, SPRINT_150.3, SPRINT_158.4, SPRINT_166.4, SATELLITE_5.0,
SATELLITE_12.0, SATELLITE_12.1, SATELLITE_13.0. Zwei Rueckläufe ohne Auftrag --
SPRINT_160.0 und SPRINT_155.2a. Dazu 139 Altrekorde, die durchweg Rueckläufe
ohne Auftrag sind. Summe 278.

**Vorgefundene Statuswerte** im Frontmatter des Steuerungskorpus: `consumed`,
`closed`, `active`, `open`, `draft`, `returned`. Nicht vorgefunden: `rejected`,
`failed`, `needs_input`.

**Trockenlauf.** Es gab eine Testdatenbank: ein Wegwerf-PostgreSQL 16 im
Ausfuehrungscontainer, auf das V1 bis V9 aufgespielt wurden. V5 scheitert dort
wie im eigenen Kommentar angekuendigt an der RLS-Politik, solange
`app.tenant_id` nicht gesetzt ist, und wurde mit gesetztem Mandanten
nachgeholt. Gemessen wurde:

- Probe: 278 Zeilen, alle fuenf Nachbedingungen halten.
- Ernstfall: 254 unter `sprint`, 24 unter `satellite`.
- Zweiter Ernstfall: weiterhin 278 Zeilen.
- Treue: alle 278 Zeilen sind in `title`, `body` und `handover_body`
  byteidentisch mit ihren Quelldateien, verglichen ueber SHA-256 gegen ein
  Manifest, das der Generator aus denselben geparsten Werten schreibt. Damit
  ist die Emission geprueft, insbesondere das Dollar-Quoting ueber 2,2 MB Prosa.
- Rote Probe eins: `SET app.tenant_id` entfernt. Das Skript schreibt keine
  stille Null, sondern bricht an der ersten Anweisung ab, weil der erste
  `INSERT` auf `dispatch.selector` an der `WITH CHECK`-Klausel scheitert.
- Rote Probe zwei: die beiden Nummernkreis-Updates entfernt. Nachbedingung 3
  feuert mit `circle stands at 1, highest number is 14`.

**Produktion.** Der Operator hat vorab gesichert (`pg_dump -n dispatch`, 16 KB,
leeres Schema) und den Vorflug gefahren: null Zeilen in `dispatch.exchange`,
beide Kreise auf 1, beide Selektoren deklariert. Die Probe gegen Produktion hat
die neun Meldungszeilen des lokalen Laufs **zeilengleich** reproduziert, danach
der Ernstfall und die Gegenprobe. Verbindung ueber `docker exec` als Rolle
`kumbuka_dispatch`, nicht als Superuser, damit die Mandantenpolitik in Kraft
bleibt.

## Abweichung

Der Auftrag benennt `Kumbuka-ai/worklist-deprecated` als Quelle. Dort liegt der
**Dienst**, kein Korpusobjekt. Der Steuerungskorpus liegt in
`Kumbuka-ai/steering`. Die Absicht war eindeutig, die Ortsangabe falsch.

Das Frontmatter fuehrt **kein `kind` und kein `sub`**, anders als die Lage es
beschreibt. `sub` steckt im Dateinamen und im `id`-Feld, beide stimmen in allen
gelesenen Objektdateien ueberein; `kind` folgt aus der Wurzel.

**Die Quelle kennt Addenda**, entgegen dem Frame. `SPRINT_155.2a` traegt
`extends: SPRINT_155.2`, existiert nur als Ruecklauf und ist mit
`addendum_suffix = 'a'` gewandert. Der Altbestand traegt ein zweites,
`sprint-10a`.

**Rueckläufe ohne Auftrag** deckte die Abbildung nicht ab. Die Zeile entsteht
aus dem Ruecklauf: `title`, `apparatus` und `dispatch_date` aus dessen
Frontmatter, `body` traegt einen deklarierten Markertext, die Prosa geht nach
`handover_body`. Es gibt drei Markertexte, je Sachverhalt einen wortgleichen:
kein Auftrag existierte (Altbestand), ein Auftrag existierte und ging verloren
(SPRINT_160.0, Klasse `constraint.create-overwrites-past-the-gate`, BUG-48),
ein Nachtrag traegt konstruktiv keinen (SPRINT_155.2a). Ein leerer Rumpf waere
zwischen diesen drei Faellen nicht unterscheidbar gewesen.

**Acht Objekte standen nicht terminal** und wurden auf Operatorentscheid vom
2026-09-06 zu `closed` geschlossen: SATELLITE_5.0, 7.0, 8.0, 12.0, 12.1, 13.0,
SPRINT_155.2a und der offene Ruecklauf von SPRINT_168.0. Der letzte ist ein
eigener Befund: Auftrag `closed`, Ruecklauf `open` -- genau die Paarung, die
`close_sprint` nach `constraint.terminal-status-pairs` blockiert. Sprint 168 ist
nie durch sein Gate gegangen, und ein offener Ruecklauf sagt das nicht laut.

**Ein Entwurfs-Ruecklauf hat ein `ratified_at` bekommen.** SATELLITE_7.0 traegt
einen Ruecklauf im Status `draft`. Zusammen mit der Terminierung verlangt
Nachbedingung 5 ein `ratified_at`; es ist aus dem Datum abgeleitet. Die
Alternative waere gewesen, den Entwurfstext wegzuwerfen.

**Der Altbestand 1 bis 136 ist nicht Teil des gesendeten Auftrags.** Er wurde
auf Operatorentscheid aufgenommen. Der Auftrag ist eingefroren, also ist diese
Erweiterung additiv nachzutragen -- ein Addendum `SPRINT_174.1a` steht aus und
ist mit diesem Ruecklauf nicht erledigt.

Im Altbestand mussten mehrere Werte extrahiert statt gelesen werden. Fuenf
Dateien tragen ein vollstaendiges Frontmatter **ohne oeffnenden Zaun** (Sprint
33, 42, 46, 58, 61) -- eine Parserregel, keine Erfindung. Fuenf tragen keins
(66, 75, 76, 135, 136); dort kommt der Titel aus der H1 und Datum wie Apparat
aus der Prosazeile darunter. Elf weitere vermissen einzelne Felder; fehlende
Titel kommen aus der H1. **Drei Rekorde erklaeren ihren Apparat nirgends** --
Sprint 104, 134 und 135 -- und tragen abgeleitet `concept`; das ist der einzige
Ort, an dem ein Wert ohne Quellaussage gesetzt wurde, und er betrifft drei
namentlich genannte Zeilen. Fehlende Statuswerte wurden zu `closed`: bei Sprint
66, 75 und 76 durch die Commit-Nachricht bezeugt, die das Wort `closure` fuehrt,
bei 135 und 136 nur durch die Existenz eines Rekord-Commits gestuetzt.

**Sprint 60 ist im Altbestand doppelt vergeben.** Auf Operatorentscheid wurde
`sprint-60-chore-69-repo-renames.md` importiert und
`sprint-60-chore-68-ee-server-migration.md` ausgelassen. Die Datei bleibt in Git.
**`19.A`** ist grossgeschrieben, was `ck_exchange_suffix` nicht annimmt und die
Toleranzregel nicht falten laesst; der Rekord steht als `19.1`. **Sprint 48
existiert nicht** -- keine Datei, keine Luecke im Slug.

**`uq_exchange_address` dedupliziert nicht.** Die Bedingung umfasst das
nullbare `addendum_suffix` ohne `NULLS NOT DISTINCT`, und Postgres behandelt
solche NULLs paarweise als verschieden. Fuer alle Zeilen ausser zweien greift
die Adresseindeutigkeit damit nicht, und `ON CONFLICT` darauf feuert nie. Die
Idempotenz traegt stattdessen `WHERE NOT EXISTS` mit `IS NOT DISTINCT FROM`.
Kein Schemaeingriff. Das ist kein Importproblem, sondern eine Aussage ueber den
laufenden Dienst: er kann zwei Austausche an derselben Adresse anlegen.

**Die Nummernkreise stehen auf 175 und 15, nicht auf Maximum plus eins.** Der
Auftrag nennt die Arithmetik, begruendet sie aber mit ausgegebenen Adressen --
und 173 und 174 sind ausgegeben, nur ausserhalb dieses Schnitts. Gefolgt wurde
dem Grund.

**`tracks`, `extends` und der Quellpfad wandern in die Metadaten**, je Rolle mit
eigenem Pfad. Beides sind Kennungen und Adressen und damit regelkonform, der
Frame kannte sie nur nicht. `handover_metadata` ist damit nicht mehr
durchgaengig NULL: 270 Zeilen tragen es, 137 tragen `dispatch_metadata`.

**Der Generator ist mitgeliefert** -- `migration/generate-import.py`, eine
dritte Datei, die die Ablageanweisung nicht nennt. Ein erzeugtes Artefakt von
3,9 MB ohne seinen Erzeuger ist nicht pruefbar und beim naechsten Nachtrag nur
noch von Hand fortzuschreiben.

**Das Skript wurde ausgefuehrt, aber nicht von der ausfuehrenden Apparatur.**
Der Auftrag verbietet das Fahren und die Verbindung zur Produktionsdatenbank;
beides ist eingehalten. Der Operator ist gefahren, diese Apparatur hat den
Ablauf geschrieben und die Ausgabe gegen eine vorab genannte Vorhersage
geprueft. Gegen ein Wegwerf-Postgres im eigenen Container wurde gefahren; das
ist keine Produktionsverbindung.

**Instrumente.** Der `workspace`-Konnektor ist praesent und unbrauchbar: jeder
Verbaufruf scheitert am draft-07-Ausgabeschema (F-0288, hier erneut gemessen).
Die Geraete-Shell erreicht GitHub im Netz, hat aber weder Schluessel noch
Credential-Helper -- der Push ist Operatorhandarbeit geblieben. Und sie bekommt
ihre git-Locks nicht wieder los: nach jedem Aufruf, der eine `index.lock`
anlegt, blockiert die Leiche den naechsten. Das ist F-0287 mit einer Ecke, die
dort nicht steht -- es trifft nicht nur die optionalen Locks. Drei Lockdateien
und drei `tmp_obj_*`-Leichen sind liegengeblieben.

**Der PROJECT.md-Block behauptet, `create_pull_request` sei ueber den
Konnektor nicht erreichbar.** Gemessen falsch: PR #9 ist genau so entstanden.
Vierte Instanz der Klasse, in der die Steuerungsanweisung selbst die Drift
traegt (F-0275).

**Nicht migriert**, wie im Frame festgelegt: die 93 Findings, Sprint 173 und
174, jede Schemaaenderung.
$kbimp$, TIMESTAMPTZ '2026-09-06T00:00:00Z',
       $kbimp${"source": "sprints/174/SPRINT_174.1-dispatch.md", "task": ["CHORE-356"]}$kbimp$::jsonb, $kbimp${"source": "sprints/174/SPRINT_174.1-return.md"}$kbimp$::jsonb;

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
          JOIN (VALUES ('sprint', 173, 0), ('sprint', 173, 1), ('sprint', 174, 0), ('sprint', 174, 1)) AS t(sel, num, sub)
            ON t.sel = s.name AND t.num = e.number AND t.sub = e.sub
         WHERE e.tenant_id = 'a7b072e4-89dd-473e-acc0-91a98a8bae7a'::uuid
           AND e.scope_id  = '0845a29c-9b55-4405-a445-7849416731b8'::uuid
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
          JOIN (VALUES ('sprint', 173, 0), ('sprint', 173, 1), ('sprint', 174, 0), ('sprint', 174, 1)) AS t(sel, num, sub)
            ON t.sel = s.name AND t.num = e.number AND t.sub = e.sub
         WHERE e.tenant_id = 'a7b072e4-89dd-473e-acc0-91a98a8bae7a'::uuid
           AND e.scope_id  = '0845a29c-9b55-4405-a445-7849416731b8'::uuid
           AND e.addendum_suffix IS NULL
         ORDER BY s.name, e.number, e.sub
    LOOP
        RAISE NOTICE '  address %/%.% carries a return: %', r.sel, r.number, r.sub, r.has_return;
    END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- Four postconditions. Each raises. They are stated over the 4
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
      JOIN (VALUES ('sprint', 173, 0), ('sprint', 173, 1), ('sprint', 174, 0), ('sprint', 174, 1)) AS t(sel, num, sub)
        ON t.sel = s.name AND t.num = e.number AND t.sub = e.sub
     WHERE e.tenant_id = 'a7b072e4-89dd-473e-acc0-91a98a8bae7a'::uuid
       AND e.scope_id  = '0845a29c-9b55-4405-a445-7849416731b8'::uuid
       AND e.addendum_suffix IS NULL;
    IF n <> 4 THEN
        RAISE EXCEPTION 'postcondition 1: expected 4 rows on the target addresses, found %', n;
    END IF;

    -- 2. every row past draft carries a send time. The data-level constraint
    --    ck_sent_when_past_draft says the same thing; asserting it here is
    --    what makes a violation name the import rather than the schema.
    SELECT count(*) INTO n
      FROM dispatch.exchange e
      JOIN dispatch.selector s ON s.id = e.selector_id
      JOIN (VALUES ('sprint', 173, 0), ('sprint', 173, 1), ('sprint', 174, 0), ('sprint', 174, 1)) AS t(sel, num, sub)
        ON t.sel = s.name AND t.num = e.number AND t.sub = e.sub
     WHERE e.tenant_id = 'a7b072e4-89dd-473e-acc0-91a98a8bae7a'::uuid AND e.scope_id = '0845a29c-9b55-4405-a445-7849416731b8'::uuid
       AND e.addendum_suffix IS NULL
       AND e.status <> 'draft' AND e.sent_at IS NULL;
    IF n > 0 THEN RAISE EXCEPTION 'postcondition 2: % rows past draft without sent_at', n; END IF;

    -- 3. the backfilled stock is terminal throughout. Both brackets are
    --    closed work; a non-terminal status here would mean an exchange was
    --    backfilled as if it were still running.
    SELECT count(*) INTO n
      FROM dispatch.exchange e
      JOIN dispatch.selector s ON s.id = e.selector_id
      JOIN (VALUES ('sprint', 173, 0), ('sprint', 173, 1), ('sprint', 174, 0), ('sprint', 174, 1)) AS t(sel, num, sub)
        ON t.sel = s.name AND t.num = e.number AND t.sub = e.sub
     WHERE e.tenant_id = 'a7b072e4-89dd-473e-acc0-91a98a8bae7a'::uuid AND e.scope_id = '0845a29c-9b55-4405-a445-7849416731b8'::uuid
       AND e.addendum_suffix IS NULL
       AND e.status NOT IN ('closed', 'consumed');
    IF n > 0 THEN RAISE EXCEPTION 'postcondition 3: % backfilled rows are not terminal', n; END IF;

    -- 4. an answer without a ratification time would be a half-migrated
    --    return: the body carried over, the fact that it was frozen lost.
    SELECT count(*) INTO n
      FROM dispatch.exchange e
      JOIN dispatch.selector s ON s.id = e.selector_id
      JOIN (VALUES ('sprint', 173, 0), ('sprint', 173, 1), ('sprint', 174, 0), ('sprint', 174, 1)) AS t(sel, num, sub)
        ON t.sel = s.name AND t.num = e.number AND t.sub = e.sub
     WHERE e.tenant_id = 'a7b072e4-89dd-473e-acc0-91a98a8bae7a'::uuid AND e.scope_id = '0845a29c-9b55-4405-a445-7849416731b8'::uuid
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
