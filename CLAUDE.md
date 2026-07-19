# etzhayyim-project-calendar — Google Calendar v3 + Microsoft Graph compatible calendar API

**calendar.etzhayyim.com** — a calendar API whose external surface is byte-shaped like **both**
Google Calendar v3 and Microsoft Graph, over **one** canonical store on **kotoba datomic**
(graph `calendar-v1`). Reference vertical for the workspace-compat program
(drive/docs/sheets/calendar/mailer).

SSoT: `90-docs/adr/2606010500-workspace-compat-datomic-schema.md`

## 一行定義

Calendar ≝ CanonicalEvent[:cal/*] × KotobaDatomic[calendar-v1] × {GoogleSkin[/calendar/v3], MicrosoftSkin[/v1.0/me]}

## アーキテクチャ

```
Google SDK  → calendar.etzhayyim.com/calendar/v3/calendars/{cid}/events  ┐
MS Graph SDK→ calendar.etzhayyim.com/v1.0/me/events                       ┘ (two external skins)
  CF Worker calendar-compat (50-infra/cloudflare/workers/calendar-compat)
   ├─ /xrpc/ai.etzhayyim.apps.calendar.*  → pod (actor-worker passthrough)
   └─ provider REST → canonical XRPC → pod → reshape to provider JSON
  lg-calendar pod (60-apps/etzhayyim-project-calendar/lg, FastAPI server.py)
   → kotoba datomic transact/q/pull on graph calendar-v1
```

- **CF Worker = edge only** (ADR-2605111200): no HYPERDRIVE/RW. All persistence is pod→datomic.
- **Internal routing**: `atproto.etzhayyim.com/xrpc/ai.etzhayyim.apps.calendar.*` → catch-all `actor:true`
  (no BPMN binding) → `pipethroughActorWorker` → `calendar.etzhayyim.com/xrpc/...` (this worker) →
  `CALENDAR_POD_URL` (cloudflared tunnel for `lg-calendar.mitama-udf.svc.cluster.local:8000`).
- **Dedicated graph `calendar-v1`** sidesteps the datomic transact-scaling blocker
  (ADR-2605302130 Option 3). Requires `KOTOBA_DEFAULT_VISIBILITY=authenticated` on the kotoba pod.

## Canonical XRPC methods (ai.etzhayyim.apps.calendar.*)

| method | type | Google v3 | Microsoft Graph |
|---|---|---|---|
| `createEvent` | proc | events.insert | POST /me/events |
| `getEvent` | query | events.get | GET /me/events/{id} |
| `listEvents` | query | events.list | GET /me/events |
| `updateEvent` | proc | events.patch | PATCH /me/events/{id} |
| `deleteEvent` | proc | events.delete | DELETE /me/events/{id} |
| `rsvp` | proc | attendee responseStatus | accept/decline |
| `listCalendars` | query | calendarList.list | GET /me/calendars |

Lexicons: `00-contracts/lexicons/ai/etzhayyim/apps/calendar/` (`defs.json` = shared event/attendee/
reminder/calendar superset; `createEvent/getEvent/listEvents/updateEvent/deleteEvent/rsvp/
listCalendars`). Legacy `connectAccount/cronTick/syncFromGoogle` = the older ingest bridge (kept).

## 完全互換 parity (beyond body shape)
- **Pagination**: canonical `offset/limit/total` ↔ Google `nextPageToken` / MS `@odata.nextLink`.
- **Errors**: Google `{error:{code,message,errors[]}}` / MS `{error:{code,message,innerError}}`.
- **Discriminators/ETag**: Google `kind:"calendar#event"`; MS `@odata.context`/`@odata.etag`;
  ETag ← `:cal/sequence`, honored via `If-Match` on update/delete (412 on mismatch).
- **Auth (this increment)**: incoming provider bearer accepted, NOT validated against real
  Google/MS; edge forwards internal `x-api-key`/`x-internal-trust`. Real OAuth introspection = TODO.

## ファイル構成

```
lg/                             # clj twin is canonical (ADR-2606280030; python deleted)
├── bb.edn                      # scoped babashka project (langgraph-clj StateGraph deps)
├── run_tests.clj               # clojure.test runner (bb test)
├── src/lg_calendar/
│   ├── server.cljc             # XRPC surface (/xrpc/ai.etzhayyim.apps.calendar.*)
│   ├── handlers.cljc           # canonical method logic (SSoT for behavior)
│   ├── store.cljc              # KotobaCalendarStore + FakeCalendarStore (tests)
│   ├── mapping.cljc            # canonical event <-> :cal/* datoms
│   ├── kotoba_datomic.cljc     # datomic transact/q/pull wire client
│   ├── edn.cljc                # EDN encoder
│   ├── ids.cljc                # slug / DID / at-uri / iCalUid
│   └── graphs/health.cljc
└── test/lg_calendar/test_handlers.cljc   # deterministic CRUD/concurrency/pagination tests

(edge) 50-infra/cloudflare/workers/calendar-compat/  — Google + MS REST skins (Hono)
(infra) 50-infra/vultr/lg-calendar-pool/             — Helm chart (mirror lg-yatabase-pool)
```

## Test / deploy

```bash
# pod logic (no live kotoba needed) — clj twin (ADR-2606280030), python deleted
cd 60-apps/etzhayyim-project-calendar/lg && bb test
# compat skins (mappers + route integration)
cd 50-infra/cloudflare/workers/calendar-compat && node --test test/*.test.ts
# deploy (infra) — Helm chart only; the python Dockerfile was removed with the python twin
helm upgrade --install lg-calendar-pool 50-infra/vultr/lg-calendar-pool -n mitama-udf
cd 50-infra/cloudflare/workers/calendar-compat && npx wrangler deploy
# PREREQUISITE before XRPC routes: regenerate the lexicon bundle (PDS validator)
node 50-infra/cloudflare/workers/atproto/scripts/bundle-lexicons.mjs
node 70-tools/scripts/contract/gen-pds-lexicon-registry.mjs
```
