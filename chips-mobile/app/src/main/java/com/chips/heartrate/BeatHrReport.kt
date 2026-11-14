package com.chips.heartrate

/** BeatLeader provides posted scores, not a full replay timeline. Windows are estimates. */
data class BeatPlay(val id: Long, val song: String, val difficulty: String, val end: Long,
                    val durationSeconds: Double?, val modifiers: String, val pauses: Int?,
                    val accuracy: Double, val stars: Double?, val started: Long? = null)
data class PlayHr(val play: BeatPlay, val start: Long?, val end: Long, val samples: List<HrReading>,
                  val coverage: Double, val warning: String?) {
    val average: Double? get() = samples.takeIf { it.isNotEmpty() }?.map { it.bpm }?.average()
    val peak: Int? get() = samples.maxOfOrNull { it.bpm }
}

fun correlateBeatHr(plays: List<BeatPlay>, readings: List<HrReading>, sessionStart: Long,
                    sessionEnd: Long, offsetSeconds: Int = 0): List<PlayHr> {
    require(offsetSeconds in -3600..3600)
    val ordered = readings.sortedBy { it.received }
    return plays.distinctBy { it.id }.mapNotNull { play ->
        val postedEnd = play.end + offsetSeconds * 1000L
        val modifiers = play.modifiers.split(',').map { it.trim() }.toSet()
        val speed = when { "SF" in modifiers -> 1.5; "FS" in modifiers -> 1.2; "SS" in modifiers -> 0.85; else -> 1.0 }
        val duration = play.durationSeconds?.takeIf { it.isFinite() && it > 0 && it <= 86400 }
        val start = play.started?.plus(offsetSeconds * 1000L) ?: duration?.let { postedEnd - (it / speed * 1000).toLong() }
        val end = if(play.started != null && duration != null) start!! + (duration / speed * 1000).toLong() else postedEnd
        if(end <= sessionStart || (start ?: end) >= sessionEnd) return@mapNotNull null
        val warning = when {
            duration == null -> "Missing song duration; HR cannot be assigned to this play."
            play.pauses == null -> "Pause timing unavailable; window may be inaccurate."
            play.pauses > 0 -> "Paused play; pause duration unavailable. Window may be inaccurate."
            play.started == null -> "Using upload time; refresh scores for recorded play start. Upload delay may shift this window."
            else -> null
        }
        val samples = if(start == null || duration == null) emptyList() else ordered.filter { it.received >= maxOf(start, sessionStart) && it.received < minOf(end, sessionEnd) }
        // Count only observed intervals, with no extrapolation across disconnects or wall-clock changes.
        val covered = samples.zipWithNext().sumOf { (a,b) ->
            val wall = b.received - a.received
            val monotonic = b.elapsed - a.elapsed
            if(wall in 1..15000 && monotonic in 1..15000 && kotlin.math.abs(wall-monotonic) <= 1000) wall else 0L
        }
        PlayHr(play,start,end,samples,if(start == null || end <= start) 0.0 else (covered.toDouble()/(end-start)).coerceIn(0.0,1.0),warning)
    }.sortedBy { it.end }
}
