# M4 integration planning

Updated 13 November 2026. This is the saved M4 integration plan: owner-confirmed inputs and authorizations are identified below; technical implementation choices remain proposals until validated. It does not claim M4 implementation or acceptance. The milestone scope remains in [the roadmap](../ANDROID_ROADMAP.md).

## Agreed scope and delivery

| Domain | Agreed direction |
| --- | --- |
| Beat Saber | BeatLeader + BeatSaver, using the owner's `beat_leader_maps` prototype as the API/interaction reference |
| Sleep/history | Xiaomi Smart Band 10 through Gadgetbridge, preferably imported through Health Connect |
| Live HR/report | Adapt the owner's MiBand-Converter into CHIPS, retain its useful outputs, and combine stored HR with the day's Beat Saber scores |
| Meals/pantry | Scan product barcodes and shopping receipts to add pantry items; suggest meals from actual inventory/preferences |
| Japanese | Owner delegates design/implementation choices to the coding agent; daily kanji and a small persistent review system |
| Drawing | Image upload, retained thumbnail and feedback, next drill/focus and drawing history |

The owner selected **Beat Saber first** on 13 November, overriding the earlier proposed Japanese → drawing → Beat Saber order. The first import/explorer implementation and online API review are recorded in [BEAT_SABER.md](BEAT_SABER.md); deployment/Poco acceptance remains. Other integrations retain their scope; subsequent order follows owner priorities. Each integration needs local persistence/cache, visible freshness and recovery, deletion behavior and on-device acceptance. Link domain completions to M2 records without duplicating imported/manual sessions.

## Beat Saber — owner-provided reference

The owner identified the private repository [Ido-Riko/beat_leader_maps](https://github.com/Ido-Riko/beat_leader_maps) as a rough working showcase of the APIs and intended functionality. Inspected its default `master` branch on 13 November 2026. Use its data flow and interactions as the reference, without requiring its Python/Matplotlib structure to be copied.

### Observed prototype

- `hit_api.py` uses `https://api.beatleader.xyz/` and `https://api.beatsaver.com/`.
- `beat_leader_data.py` requests `GET player/{player_id}/accgraph` and caches the response as JSON.
- `plot_graph.py` reads stars, accuracy (`acc`, multiplied by 100 for display), timestamp (`timeset`), song name, map hash, leaderboard ID, PP, pass/accuracy/technical ratings, mode and difficulty.
- `beat_saver_data.py` requests `GET maps/hash/{map_hash}` for map ID, BPM, duration, tags, and difficulty-specific NJS, NPS, notes, bombs and obstacles.
- The interactive scatter plot supports selectable axes for stars, accuracy, PP, pass/accuracy/technical ratings and BPM. Selecting a point opens its BeatLeader leaderboard or BeatSaver map; tooltips show song, stars, accuracy and PP.
- The prototype has an explicit saved-data testing mode. BeatSaver enrichment is fetched on demand or through a bulk action.

### Proposed CHIPS integration

1. Store the selected BeatLeader player ID as configuration. Have backend adapters fetch BeatLeader data and enrich maps through BeatSaver; persist normalized records in PostgreSQL and return them through authenticated CHIPS endpoints. Android retains an offline SQLite cache.
2. Preserve source identity and timestamps. Join map enrichment by exact hash, characteristic/mode and difficulty. Match the returned version to the requested hash rather than assuming `versions[0]` is correct. Cache map metadata and fetch missing enrichment with bounded concurrency and retries.
3. Start with the prototype's accuracy-versus-stars chart, point details, source links and selectable metrics. Keep a compact summary on the dashboard and the detailed explorer behind it. Missing enrichment must leave the original score visible.
4. Verify current upstream contracts, availability, pagination, limits and identity semantics before implementation. The paths above are observed prototype usage, not a current API compatibility guarantee.
5. Establish what `accgraph` includes before calling its points complete play/session history. Verify a separate play/score-history API if needed for recent sessions, repeat attempts, automatic activity logging or longitudinal progress. A missing point must not be treated as proof that the owner did not play.
6. Keep source ratings distinct from inferred coaching. BPM, NPS and technical rating do not alone establish a specific hand-timing or pattern weakness. Detailed coaching may require replay or per-note evidence beyond this prototype.

### Initial acceptance proposal

Load the owner's real data, compare representative points with the reference/source pages, verify exact-version difficulty enrichment, preserve charts offline, and demonstrate repeated refresh without duplicated records. Demonstrate partial enrichment failure without losing scores. Accept session-based activity logging separately after history coverage and deduplication are established.

## Sleep and heart rate — Xiaomi Smart Band 10 / Gadgetbridge

Owner confirmed on 13 November 2026: their wearable is the **Xiaomi Smart Band 10**, and **Gadgetbridge** is the intended companion/data source. The owner also requested combining live heart-rate data with the day's Beat Saber scores into a report.

### Verified documentation and proposed transport

- [Gadgetbridge's Xiaomi device list](https://gadgetbridge.org/gadgets/wearables/xiaomi/#mi-band-10) lists the band as mostly supported and requiring a Xiaomi pairing token obtained through initial vendor-app pairing. Actual firmware/pairing and sleep/HR behavior still need device acceptance.
- Prefer `band → Gadgetbridge → Health Connect → CHIPS` for historical sleep and HR. [Gadgetbridge documents Health Connect export](https://gadgetbridge.org/basics/integrations/health-connect/) for sleep sessions, HR and other device-recorded measurements, including optional sync after device sync. CHIPS would request the relevant read permissions, retain source IDs and timestamps, and cache imported data locally. Validate availability on the installed versions and actual band; the general supported-type list does not prove this model records every type.
- [Automatic SQLite database/FIT exports](https://gadgetbridge.org/internals/automations/auto-export/) provide a fallback for fields not available through Health Connect. Use user-granted document access to an export location, not another app's private database. Schema/version handling and usable export paths require validation.
- For **live HR**, adapt MiBand-Converter as detailed below. Historical Health Connect sync and periodic exports do not establish live delivery. Prove sample cadence, timestamps, reconnect behavior, background capture and Gadgetbridge coexistence on the Poco.

### Combined Beat Saber daily/session report

Proposed report: HR timeline with song markers; song accuracy, stars and PP; session average/peak observed HR and sample coverage; comparable-score progression through the session; optionally prior-night sleep context. Live capture supports the timeline while playing; score data may arrive later, so reconcile it after upload and update the report.

Join by actual play timestamps and trustworthy duration/start/end information, accounting for UTC, local-day display, clock offsets, delayed uploads and missing samples. If timing is approximate, mark it as such; do not silently assume `timeset` is song start. Confirm full score-history coverage separately from `accgraph`, especially repeat attempts and non-personal-best plays. Preserve chart gaps and distinguish missing data from low HR. Present observed associations without claiming sleep or HR caused score changes. Compute report statistics from records; optional generated prose must reference those computed results.

Acceptance: prove overnight sleep import, historical HR import and live HR capture separately; then match a real Beat Saber session's plays to its HR timeline and verify report results, late-arriving scores, disconnect gaps, repeat imports and local-midnight handling.

### Band-specific evidence — Gadgetbridge issue #5323

Read [the issue and all 41 comments](https://codeberg.org/Freeyourgadget/Gadgetbridge/issues/5323) on 13 November 2026 via Codeberg's public API. The [20 July contributor summary](https://codeberg.org/Freeyourgadget/Gadgetbridge/issues/5323#issuecomment-19737167) supersedes several original-report limitations: sleep, HR, steps, daily/workout calories and workout detail parsing are reported working. A [22 July report](https://codeberg.org/Freeyourgadget/Gadgetbridge/issues/5323#issuecomment-19849417) nevertheless describes missing workout HR charts on 0.92.2. Sleep-score availability is unproven; VO2max/respiration were pending in July. These are version-specific reports, not Poco acceptance. Prefer direct captured HR for the combined report, and validate sleep import on the installed versions.

### Live HR implementation reference — MiBand-Converter

The owner explicitly authorized copying/adapting code from [Ido-Riko/MiBand-Converter](https://github.com/Ido-Riko/MiBand-Converter) and replacing that standalone app with CHIPS if useful. Inspected `main` at `a472d64a6743454db0b63c902684be36426f2ff9` on 13 November 2026. Interpret the replacement target as MiBand-Converter; Gadgetbridge remains the planned sleep/history companion.

This provides a concrete live-HR transport and supersedes the earlier open-ended transport search above: use the band's Share HR mode and adapt its BLE HR reader into CHIPS. `BleConnectionManager.kt` subscribes to service `0x180D` / measurement characteristic `0x2A37` and emits `onHeartRateReceived(hr)`. `HrForegroundService.kt` orchestrates the reader, notification, optional BLE peripheral rebroadcast and OSC output; the manifest declares a `connectedDevice` foreground service. Preserve the optional BLE rebroadcast and OSC capabilities as part of replacement planning, rather than silently dropping the converter's other functions.

New CHIPS work: timestamp and persist valid received samples, group capture into explicit sessions, retain connection gaps, provide stop/reconnect controls, and sync stored samples through the owned backend for score correlation. Treat the converter's disconnect sentinel (`-1`) as connection state, never a measurement. Review/adapt the existing parser and service lifecycle against CHIPS's supported Android versions and permission flow. Repository code and README are implementation references, not evidence that the port or Gadgetbridge coexistence has passed on the Poco. The README explicitly says HR is not logged/stored; persistence and reporting must be added.

Important adaptation: the existing BLE reader suppresses unchanged HR values and throttles callbacks. For reporting, retain timestamped valid received samples before presentation/output filtering, so stable HR does not appear as a disconnect gap and coverage/averages are not biased toward changing values. Track reception time separately from any actual sensor timestamp; the observed HR payload does not establish a sensor timestamp.

Proposed paths:

- Sleep/history: `Smart Band 10 → Gadgetbridge → Health Connect → CHIPS`.
- Live workout HR: `Smart Band 10 Share HR → CHIPS BLE foreground service → local sample storage → backend report`, with optional BLE/OSC outputs retained from the converter.
- Scores/maps: `BeatLeader + BeatSaver → CHIPS backend → report`.

Acceptance before retiring the standalone converter: capture with screen off, verify timestamps and restart/reconnect behavior, test actual coexistence with Gadgetbridge, and verify whichever BLE/OSC outputs the owner uses. Preserve source provenance for adapted code. No app uninstall or implementation is performed by this planning update.

Upstream contribution of the live-HR integration to Gadgetbridge is an optional later project, explicitly lower priority than CHIPS. It is not an M4 dependency or acceptance gate; no publication is requested now.

## Meals and pantry — barcode and receipt input

Owner confirmed barcode scanning of products and scanning shopping receipts as the intended ways to add pantry inventory. Both must feed the same editable pantry ledger rather than separate inventories.

### Proposed implementation

1. Barcode path: camera scanner → barcode/product lookup adapter → product preview → quantity/unit confirmation → durable pantry addition. Store the barcode and cache resolved product metadata. Product lookup provider and regional coverage must be checked during implementation; no external provider has been selected. Unknown codes support manual naming and become reusable local matches. A barcode identifies a product, not how many units were purchased or current stock.
2. Receipt path: camera/photo import → OCR → candidate line items → review/correction → one confirmed pantry transaction. Parse item descriptions, quantities and units where present; keep prices/discounts separate from quantities. Receipt abbreviations need editable product matching; do not assume receipts include barcodes. Verify relevant receipt languages and actual shop formats during acceptance.
3. Persist scan drafts and uncertain matches for recovery. Repeated camera frames must not repeatedly add the same item. Flag likely duplicate receipt imports; allow an intentional repeat purchase. Retrying a confirmed transaction must not add its quantities twice.
4. Save additions/consumption/corrections as inventory movements with stable IDs, timestamps and provenance. Sync through authenticated owned backend routes, preserving offline edits and deletion. Keep packaged size separate from item count; record expiry only when supplied, not inferred from the barcode.
5. Pantry supports manual edits, consumption and low-stock thresholds. Recipes/suggestions use actual available quantities and preferences and show missing ingredients. Confirm ingredient consumption before decrementing stock. OCR/product matches and optional generated recipes remain editable.
6. Prefer on-device scanning/OCR where practical; select and verify the runtime during implementation. Any configured backend image processing follows the existing provider approach. Define receipt-image retention/deletion and avoid retaining unrelated receipt details when parsed pantry entries suffice.

Acceptance: real product barcode and receipt imports, unknown codes, OCR errors, quantity corrections, offline drafts, duplicate detection, repeated synchronization, consumption, low-stock behavior and deletion. The same purchase entered by barcode and receipt must be reviewable without silently double-counting stock.

## Japanese — delegated implementation

Owner explicitly delegates this integration's design and implementation choices to the coding agent; no external study-service integration is required by the current plan.

Proposed first version: a local kanji/content repository with readings, meanings and examples; daily item selection; a persisted due-review queue; answer reveal and self-rated recall; a small documented spaced-review schedule. Choose and verify a redistributable licensed content source before bundling it, preserve attribution, and avoid inventing dictionary entries with an LLM. Keep study/review state in SQLite, with owned backend sync for progress. A completed practice session links to Japanese activity without counting repeated sync as another session. The dashboard shows the daily item and due count; a practice screen handles reviews. Verify review scheduling, local-day changes, offline study, restart, progress sync and deletion. No recurring content/source decision needs to block implementation unless a material scope choice arises.

## Drawing — upload, feedback and next practice

Retain the recorded idea: pick/upload a drawing, retain its thumbnail and feedback, show the latest drill/focus, and browse previous work. No specific external drawing API/model was previously recorded.

Proposed path: Android photo picker → durable local image/draft → authenticated media upload → configured backend vision analysis → stored structured feedback and suggested next drill → dashboard/history. Include the owner's exercise or goal as context when supplied. Choose and verify a vision-capable provider/runtime during implementation; manual notes remain useful when analysis is unavailable and are labeled separately from generated feedback. Keep upload and analysis states distinct, retry safely without duplicating media or completed analysis, and preserve the image when analysis fails. Deleting a drawing removes owned media, thumbnails and associated feedback, including pending work. A completed drawing session links to M2 activity records independently of whether AI analysis succeeds.

Acceptance: actual image selection/upload and analysis (or clearly labeled manual feedback), thumbnail/history persistence, offline recovery, failed analysis/retry, next-drill display and complete media deletion.
