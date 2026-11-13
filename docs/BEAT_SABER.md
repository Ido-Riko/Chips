# Beat Saber integration

Implemented 13 November 2026 as the first M4 integration selected by the owner. Updated to direct on-phone public API access after the owner confirmed local-first across M4. The Android feature does not require the CHIPS server. This does not complete M4 or the separate M3 voice service.

## User flow

Normal-mode Today → Beat Saber → Explore scores. Enter the BeatLeader player ID from the profile URL, then Save and import. The phone reads public BeatLeader/BeatSaver APIs directly, without an enrollment token or Tailscale. Refresh imports upstream data; Reload saved reads local SQLite only. Reopening/import removal works offline. Internet is needed for new imports and uncached map details. Activity synchronization settings do not gate this feature.

The explorer opens with a compact ranked/recent switch and scatter chart, followed by search and song cards. Profile setup, PP/ranks, import coverage and history change stay behind Profile. Tap a song or point to open a scrollable detail sheet. The Quick Log bar is hidden while exploring scores. Tap an axis name to choose stars, accuracy, PP, pass/accuracy/technical rating, BPM, NPS or date. Visible labels use metric names without X/Y prefixes; point counts and technical status text are omitted. Tap a point or list row for score/map details and BeatLeader/BeatSaver links. Song details show stars, pass/acc/tech ratings, song BPM and explicit unavailable values. Recent-score details also include mistakes, combo, pauses, average hand cut scores out of 115 and improvement deltas.

The chart supports pinch zoom (1×–16×), two-finger panning, +/− buttons, directional pan buttons and Reset while zoomed. Gesture guidance is available under the chart’s ? button. A single finger scrolls the page. Ticks track the visible range; points outside it are clipped and cannot be selected. Changing axes/data resets the view. The compact song-sort menu offers date, BPM, stars, accuracy or PP in either direction; missing values stay last.

Missing chart metrics do not remove scores from the list. Recent-score ratings with modifiers are withheld instead of claiming base-map ratings are adjusted; the ranked graph already has source-adjusted ratings. BPM sorting uses saved data without API requests. Recent scores already include song BPM; matching map hashes share that BPM with ranked portfolio entries, including older snapshots. Opening a map reuses the existing BeatSaver detail lookup/cache and saves any newly found BPM back to matching entries for offline sorting. Unknown BPM remains visible and sorts last; there is no bulk per-song fetch. BPM is the song metadata value, not adjusted for speed modifiers. Portfolio NPS may remain unavailable. Automatic activity logging, replay coaching and the live-HR report are not implemented by this increment.

## Online API review

Reviewed the live [BeatLeader schema](https://api.beatleader.com/swagger/blapi/swagger.json), [API UI](https://api.beatleader.com/swagger/index.html), [player-score controller](https://github.com/BeatLeader/beatleader-server/blob/master/Controllers/PlayerScoresController.cs), [score controller](https://github.com/BeatLeader/beatleader-server/blob/master/Controllers/ScoreController.cs), and [BeatSaver schema](https://api.beatsaver.com/docs/swagger.json). The owner's [prototype](https://github.com/Ido-Riko/beat_leader_maps) remains the interaction reference.

| Upstream API | Use |
| --- | --- |
| BeatLeader `GET /player/{id}` | Canonical profile, PP and ranks |
| `/player/{id}/accgraph?no_unranked_stars=true` | Ranked general-context portfolio; not all attempts |
| `/player/{id}/scores?sortBy=date&order=desc&count=100&page=N&includeIO=true&leaderboardContext=general` | Latest retained scores with mistake/combo/improvement data; bounded to three pages and deduplicated by score ID |
| `/player/{id}/history?count=30` | Daily profile statistics, not play-by-play history |
| BeatSaver `/maps/hash/{hash}` | Match exact hash/version, then characteristic and difficulty; extract map metrics/tags |

Also verified: BeatLeader `/score/statistic/{id}` redirects to signed statistics JSON containing hand timing/misses, swing/cut statistics and a score graph. This is a later detailed-coaching source; validate units, constrain redirects and bound downloads before adding it. BeatSaver map search is another documented opportunity for practice-map discovery. Neither is consumed by the current UI.

Live adapter check for the prototype's player: 337 ranked graph points, 300 recent scores out of 529 retained scores, 30 daily entries; real exact-version BeatSaver enrichment passed. These are point-in-time read-only checks. Full attempt coverage, failed/non-best plays, song start/end times and delayed uploads remain gates for the later HR report. Do not assume `timeset` or daily history establishes song boundaries.

## Local persistence and optional backend

Android uses a dedicated local SQLite namespace independent of service URL/token. Existing selected-enrollment snapshots can migrate once; a local removal marker prevents them from reappearing. Removing imports clears the selected profile/scores/maps locally, without contacting a server or deleting upstream scores/activity logs. Refresh failures preserve the prior complete snapshot. Changing profile clears the previous selection before importing. Requests are bounded/cancellable and retry busy providers once; a 60-second refresh cooldown avoids needless repeat calls. Direct public requests contain no enrollment credential.

The backend routes from the initial implementation remain available as separate infrastructure; the direct Android client does not call them. Optional score sync/backup is not yet wired into Android. These routes require device authentication/HTTPS and derive ownership from the credential:

| Route | Behavior |
| --- | --- |
| `GET /api/v1/beat-saber` | Owner's `{player_id, snapshot}`, or null values |
| `PUT /api/v1/beat-saber/connection` with `{player_id}` | Save selection; a changed player clears the snapshot and invalidates pending imports |
| `POST /api/v1/beat-saber/refresh` | Validate/import atomically; 60-second cooldown, bounded requests and retries |
| `DELETE /api/v1/beat-saber` | Remove owner's selection/snapshot; upstream scores/activity logs remain |
| `GET /api/v1/beat-saber/maps/{hash}?mode=Standard&difficulty=ExpertPlus` | Exact-version enrichment; public metadata cached for 24 hours |

Version-1 snapshots include fetch timestamp, canonical player ID, profile, graph, scores, history, total-score count and explicit coverage text. Accuracy is percent; accuracy improvement is percentage points; timestamps are Unix seconds, displayed locally; hand cut scores are out of 115.

Migration `002_beat_saber.sql` adds owner-scoped snapshots and a public map cache. The migrate command applies numbered SQL files in order, transactionally/repeatably. Both migrations are required by packaging/startup checks. Existing activity/voice data remains intact. Follow the deployment guide and backup process before upgrading the actual server; no deployment has occurred in this increment.

Migration/deployment of those backend routes is not required to use Beat Saber on Android. M2 activity synchronization and M3 inference have their own separate dependencies.

## Acceptance

Automated checks cover cache reopen, enrollment isolation/removal, malformed snapshot rejection, score selection during enrichment failure, HTTP framing, owner isolation, refresh preservation, bounded paging, exact-version enrichment, delete-during-refresh protection, upstream retry/redirect behavior and repeatable PostgreSQL migration over existing data. See the progress ledger for final execution counts.

Remaining acceptance: import/browse real data on the Poco and verify offline/restart/removal, map details and source links. Broader M4 integrations and the live-HR report remain separate. Server deployment is not an Android Beat Saber gate.
