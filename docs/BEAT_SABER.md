# Beat Saber integration

Implemented 13 November 2026 as the first M4 integration selected by the owner. Backend deployment and physical Poco acceptance remain separate. This does not complete M4 or the deferred M3 voice service.

## User flow

Normal-mode Today → Beat Saber → Explore scores. Enroll the phone with the CHIPS HTTPS service in Settings first. Enter the BeatLeader player ID from the profile URL, then Save and import. Refresh imports upstream data; Reload saved reads the service's existing snapshot. Paused synchronization permits offline browsing; reconnect before importing/refreshing.

The explorer shows profile PP/ranks, a daily-history PP change summary, ranked portfolio and recent-score views, search and an interactive scatter chart. Axes offer stars, accuracy, PP, pass/accuracy/technical rating, BPM, NPS and date where available. Tap a point or list row for score/map details and BeatLeader/BeatSaver links. Recent-score details include mistakes, combo, pauses, average hand cut scores out of 115 and improvement deltas.

Missing chart metrics do not remove scores from the list. Recent-score ratings with modifiers are withheld instead of claiming base-map ratings are adjusted; the ranked graph already has source-adjusted ratings. BeatSaver enrichment is on demand, so portfolio BPM/NPS may be unavailable, while recent scores include them when supplied. Automatic activity logging, replay coaching and the live-HR report are not implemented by this increment.

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

## CHIPS contract and persistence

All routes below require device authentication/HTTPS and derive ownership from the credential. Device tokens are never sent upstream.

| Route | Behavior |
| --- | --- |
| `GET /api/v1/beat-saber` | Owner's `{player_id, snapshot}`, or null values |
| `PUT /api/v1/beat-saber/connection` with `{player_id}` | Save selection; a changed player clears the snapshot and invalidates pending imports |
| `POST /api/v1/beat-saber/refresh` | Validate/import atomically; 60-second cooldown, bounded requests and retries |
| `DELETE /api/v1/beat-saber` | Remove owner's selection/snapshot; upstream scores/activity logs remain |
| `GET /api/v1/beat-saber/maps/{hash}?mode=Standard&difficulty=ExpertPlus` | Exact-version enrichment; public metadata cached for 24 hours |

Version-1 snapshots include fetch timestamp, canonical player ID, profile, graph, scores, history, total-score count and explicit coverage text. Accuracy is percent; accuracy improvement is percentage points; timestamps are Unix seconds, displayed locally; hand cut scores are out of 115.

Migration `002_beat_saber.sql` adds owner-scoped snapshots and a public map cache. The migrate command applies numbered SQL files in order, transactionally/repeatably. Both migrations are required by packaging/startup checks. Existing activity/voice data remains intact. Follow the deployment guide and backup process before upgrading the actual server; no deployment has occurred in this increment.

Android uses a separate SQLite cache keyed by a hash of service URL + credential, isolating enrollment changes. Token rotation starts a new local cache. Map entries share that namespace. Removal clears this enrollment's local snapshot/maps after server confirmation. Import failures preserve the prior complete snapshot; changing player deliberately clears the previous selection even if the new import fails.

## Acceptance

Automated checks cover cache reopen, enrollment isolation/removal, malformed snapshot rejection, score selection during enrichment failure, HTTP framing, owner isolation, refresh preservation, bounded paging, exact-version enrichment, delete-during-refresh protection, upstream retry/redirect behavior and repeatable PostgreSQL migration over existing data. See the progress ledger for final execution counts.

Remaining: deploy the additive backend update; import/browse real data on the Poco; verify offline/restart/removal, map details and links on-device. Broader M4 integrations and the live-HR report remain separate.
