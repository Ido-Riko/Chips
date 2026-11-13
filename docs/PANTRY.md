# Pantry — local inventory and server persistence

Owner direction, 13 November 2026: pantry is saved locally **and on the server**; the main CHIPS AI handles meal recommendations. No separate recipe generator or automatic stock consumption is introduced.

## Android

Today → Open pantry shows current quantities, package sizes, supplied expiry dates and low-stock warnings. Add groceries manually or scan a barcode with Google's Code Scanner. Scanning needs Google Play services and its downloaded scanner module; manual barcode entry works when unavailable. Lookup calls Open Food Facts directly with an identifying User-Agent and bounded response; known barcode matches are remembered locally. Unknown/offline lookup leaves manual naming available. Names and low-stock levels can be corrected with the stock total. Use records consumption; Correct total records the difference from current stock. A zero total removes the item from the visible pantry. Negative merged totals are flagged for correction rather than clamped or silently discarded.

Quantities use decimal arithmetic, up to six decimal places. Item count and package size remain separate: two 500 g packets are `2 item`, package `500 g`. Units are item/g/kg/ml/l. Expiry is entered explicitly. Names, unit, package and expiry determine local identity (barcode replaces name when available); different expiry batches remain separate. Receipt-only names do not automatically merge with barcode products: review existing stock and correct duplicate purchases explicitly. No guessed ingredient matching or unit conversion occurs.

Receipt entry accepts a photo through the Android photo picker, or editable text lines `name | quantity | unit`. Draft text and one resized JPEG persist locally across restarts. **Read photo on server** explicitly sends the photo to the configured vision runtime and returns an uncertain transcription, including Hebrew if supported by the selected model. No quantities change until **Add reviewed items**. Review item descriptions, unit/count versus package size, and any OCR mistakes; totals, taxes, discounts and prices must not become stock. Up to 100 lines per transaction. Exact original-photo hashes (or normalized manual text hashes without a photo) detect repeated imports and require an explicit repeat-purchase override. This cannot detect all rephotographed receipts or barcode/receipt overlap.

Confirming/discarding a draft deletes its local photo. CHIPS does not retain receipt photos on the server; provider retention is controlled by the configured runtime. No external product service receives receipt photos. Failed reading retains the local draft/photo for retry. There is no bundled multilingual offline OCR: offline manual entry/review works; photo transcription needs the server and a vision-capable model.

## Durable ledger and sync

`pantry.db` is an independent, additive SQLite database; existing activity/practice/voice databases are untouched. Immutable transaction UUIDs contain timestamp, source, optional receipt fingerprint and signed inventory movements. Stock is derived from this ledger. Additions, consumption and corrections retain history; removing an item with a zero-stock correction is **not** an erasure of its historical purchase data. Full ledger erasure is not exposed in this increment.

WorkManager pushes pending transactions, verifies acknowledgements against the sent content, then pulls owner transactions in UUID cursor pages of 200. Each sync starts its pull from the beginning, so late UUIDs are found on subsequent syncs. Unacknowledged writes remain pending; retrying the same UUID never changes quantities twice. Server copies can restore transactions onto a clean local pantry using the same enrolled identity. Enrollment binding prevents sending an existing pantry to a different endpoint/owner/device. Settings Disconnect pauses pantry sync and cancels scheduled pantry jobs; Reconnect/Save resumes it. Sync status shows queued changes, last success and recoverable errors. A connected network does not imply the server is reachable.

Multiple devices' independent movements add together; two offline consumers can overdraw stock. Correct the displayed negative quantity using an explicit adjustment. The ledger does not claim transactional reservation across devices.

## Backend / main assistant

Deploy the updated backend and run `python -m app.scripts.migrate`; additive migration **004_pantry.sql** creates owner-scoped PostgreSQL transaction storage. The release package and OpenRC checks require it. Include these rows in database backups.

Authenticated endpoints under `/api/v1/pantry`:

- `PUT /transactions/{uuid}`: validated immutable transaction, identical retries succeed; conflicting content returns 409.
- `GET /transactions?after={uuid}`: owner-scoped restoration/sync pages, ordered by UUID.
- `GET /inventory`: exact derived quantities with source metadata, low-stock flags and last movement timestamps; same-owner devices share inventory.
- `POST /receipt`: validated bounded JPEG → editable text draft. No inventory mutation or retained server receipt image. Reuses the explicitly configured `DRAWING_VISION_URL`/`MODEL`/optional `API_KEY` compatible vision runtime. Missing configuration is an actionable 503.

The existing backend assistant route includes **only the authenticated owner's inventory** as reference context, with freshness, unit/package and negative-stock cautions. Recommendations never decrement stock. The deferred M3-compatible voice service must preserve this owner-scoped context hookup; the old plain-text route is still incompatible with the Android M3 voice contract. This pantry increment does not make voice inference operational. No automatic paid/cloud model fallback is selected.

## Validation / remaining acceptance

Automated checks cover persistent decimal stock, consumption/correction/reopen, retry and acknowledgement conflicts, receipt draft-only behavior/duplicate review, enrollment isolation, owner isolation, invalid quantities/metadata, real PostgreSQL migration repeated over old data and concurrent purchase retries, plus assistant context ownership. Android UI testing adds groceries and confirms an edited receipt without a server. Debug/release builds, JVM tests, emulator instrumentation and lint are recorded in the progress ledger.

`chips-server` was offline at implementation time. The production migration and real receipt inference are **not deployed/accepted**. Physical barcode scans, Open Food Facts regional coverage, actual Hebrew receipt quality, phone offline/restart/reconnect/server restoration and overlapping barcode/receipt purchases remain device acceptance tasks.

## Upstream references checked 13 November 2026

- [Google Code Scanner setup](https://developers.google.com/ml-kit/vision/barcode-scanning/code-scanner): 16.1.0; permissionless scanner UI, Play-services module.
- [Open Food Facts v2 product API](https://openfoodfacts.github.io/documentation/docs/Product-Opener/v2/products/get-product-by-code/) and [data licensing](https://world.openfoodfacts.org/data): identifying User-Agent, exact barcode lookup, names/package metadata under ODbL. Attribution is accessible in the pantry screen. Public metadata is reference data, not guaranteed product identity or pantry quantity.
- [AndroidX Fragment releases](https://developer.android.com/jetpack/androidx/releases/fragment): explicitly selects compatible 1.8.9 to replace the scanner's transitive Fragment 1.0.0, which otherwise fails Activity Result lint checks.
