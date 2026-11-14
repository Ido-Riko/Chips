# Sleep history and Beat Saber HR reports

Implemented 14 November 2026; local-first and independent of server enrollment.

## Sleep setup

1. Pair/sync the Smart Band in Gadgetbridge.
2. In Gadgetbridge → Settings → External Integrations → Health Connect, enable export, select the band, select the initial history range, and enable sync after device sync. Allow Gadgetbridge to write sleep.
3. In CHIPS, open Today → Sleep history / import or History → Sleep. Choose Connect / import sleep and allow sleep reading. On supported providers, optional past-data permission enables older history.
4. Repeat Import after syncing the band. This is an explicit foreground import, not a background poll.

Health Connect must be available (Android 9+; built into Android 14+, provider app on supported older releases). The UI handles unavailable providers, installation/update, denied permissions and failed reads. Imports page through all readable sleep sessions and retain origin, record ID, session times, wake offset and stages. They include all permitted sleep sources, with visible attribution, rather than silently assuming every record is from Gadgetbridge. Overlapping sources remain separate and are never summed. Session duration is not a fabricated sleep score or time asleep.

Without history access, the query covers the previous 30 days. With history access, it requests all available history. Only a fully successful read replaces records in that query's start-time window, so retries deduplicate, source corrections update, and source deletions inside the readable window disappear. Previously imported records outside the readable window remain offline; their source deletions cannot be reconciled until that window is readable again. Removing the local copy does not delete Gadgetbridge or Health Connect data. Android backup follows the app's existing device backup rules. No sleep data is sent to the CHIPS server.

## Per-play report

Open Heart rate → a saved capture → Beat Saber + HR report. It combines the current local BeatLeader score import with that capture's lossless readings. Refresh/import BeatLeader scores first, then reload the report. Linked session saving and HR/activity server sync continue to work as before.

New score imports retain BeatLeader `timeset` (the replay's play-start timestamp), separate from `timepost` (server upload time). Play windows use recorded start plus duration, adjusted for SS/FS/SF speed. Old cached scores without start timestamps fall back to posted time minus duration, with an explicit warning. Refreshing scores improves those windows. Pause duration is unavailable in the score summary and is flagged. Time adjustment from −3600 to +3600 seconds is persisted per capture; positive moves windows later. Upload delays and inaccurate device clocks still need inspection. Missing duration never produces matched HR statistics. Failed, unuploaded and unretained attempts are absent; the report cannot reconstruct them from score history.

Each distinct score ID shows song, difficulty, accuracy, stars when available, estimated window, received-sample mean/peak, sample count, observed coverage and an HR trace. Samples use half-open windows to avoid sharing exact boundaries between consecutive plays. Coverage and trace connect only adjacent samples no more than 15 seconds apart with consistent monotonic timestamps; reconnect gaps and clock jumps are not filled. HR is clipped to the capture, while coverage uses the full estimated play duration. Reports are derived on demand from saved source data and remain usable offline; deleting either source removes its availability.

## Song details fix

Map/BPM enrichment invalidates asynchronous chart preparation. Details now resolve their selected key against the source scores independently of that temporary preparation state. The modal stays mounted while details change, instead of disappearing and replaying its entrance animation.

## References and device checks

- [Gadgetbridge export setup](https://gadgetbridge.org/basics/integrations/health-connect/)
- [Health Connect reading and history permissions](https://developer.android.com/health-and-fitness/health-connect/read-data)
- [BeatLeader score timestamp assignment](https://github.com/BeatLeader/beatleader-server/blob/master/Utils/ReplayUtils.cs)
- [Replay timestamp definition](https://github.com/BeatLeader/BS-Open-Replay)

Real band → Gadgetbridge export, Health Connect grant/revocation across supported OS versions, and a known Beat Saber play captured with the band still require end-to-end device acceptance. Synthetic tests cover storage reconciliation and report boundaries/gaps/speed/start time; the Compose regression checks sheet identity during enrichment.

## Local verification (14 November 2026)

`:app:assembleDebug`, `:app:testDebugUnitTest`, `:app:lintDebug` and `:app:compileDebugAndroidTestKotlin` passed. All 42 JVM tests passed, including five new correlation cases. Lint has zero errors and 57 warnings. Instrumentation sources compile, but the connected phone rejected the test APK: Gradle's automatic permission-grant install was denied, and a plain install returned `INSTALL_FAILED_USER_RESTRICTED`. No device test success is claimed.

Subsequent navigation fix: Back now restores previous screens/tabs and linked History/HR locations, with navigation history retained across recreation. Debug and test APK assembly, 42 JVM tests and lint pass. The three new navigation UI regressions compile; the isolated emulator crashed before execution.

Validation correction: the earlier failed connected test run's cleanup uninstalled CHIPS and deleted local phone data. The later successful owner-approved app install was fresh. Device tests must use an explicitly targeted separate emulator; no physical-device test success or data-preserving install is claimed for this work.
