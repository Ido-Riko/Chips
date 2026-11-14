package com.chips.beatsaber

import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Collection equality can itself scan 20,000 scores; preparation inputs use identity. */
internal class BeatInput(private val value: Any?) {
    override fun equals(other: Any?) = other is BeatInput && value === other.value
    override fun hashCode() = System.identityHashCode(value)
}

/** New inputs clear old results immediately; cancelled workers cannot publish stale axes/searches. */
@Composable
internal fun <T> rememberBeatPreparation(vararg inputs: Any?, prepare: () -> T): T? {
    val result = remember(*inputs) { mutableStateOf<T?>(null) }
    LaunchedEffect(result) { result.value = withContext(Dispatchers.Default) { prepare() } }
    return result.value
}

internal class BeatSongs(val points: List<BeatScore>, val ordered: List<BeatScore>) {
    val byKey = points.associateBy { it.key }
    val orderedIndex = ordered.withIndex().associate { it.value.key to it.index }
}

internal fun prepareBeatSongs(source: List<BeatScore>, search: String, sortBy: String, ascending: Boolean): BeatSongs {
    val points = if (search.isBlank()) source else source.filter {
        "${it.song} ${it.difficulty} ${it.mode} ${it.modifiers}".contains(search, true)
    }
    return BeatSongs(points, sortBeatScores(points, sortBy, ascending))
}

internal fun BeatScore.axis(key: String): Double? = if (key == "timestamp") timestamp.toDouble() else metric(key)

internal fun sortBeatScores(points: List<BeatScore>, metric: String, ascending: Boolean): List<BeatScore> =
    points.map { it to it.axis(metric) }.sortedWith(compareBy<Pair<BeatScore, Double?>> { it.second == null }
        .thenComparator { a, b ->
            val first = a.second; val second = b.second
            if (first == null || second == null) 0 else if (ascending) first.compareTo(second) else second.compareTo(first)
        }.thenBy { it.first.key }).map { it.first }

internal class BeatScatter(val plotted: List<Triple<BeatScore, Double, Double>>,
                           val xBounds: Pair<Double, Double>, val yBounds: Pair<Double, Double>)

internal fun prepareBeatScatter(points: List<BeatScore>, x: String, y: String): BeatScatter {
    val plotted = points.mapNotNull { p -> p.axis(x)?.let { a -> p.axis(y)?.let { b -> Triple(p, a, b) } } }
    fun bounds(axis: String, value: (Triple<BeatScore, Double, Double>) -> Double): Pair<Double, Double> {
        if (plotted.isEmpty()) return 0.0 to 1.0
        val low = plotted.minOf(value); val high = plotted.maxOf(value)
        val padding = if (axis == "timestamp") 3600.0 else maxOf(kotlin.math.abs(low) * 0.05, 0.5)
        return if (low == high) low - padding to high + padding else low to high
    }
    return BeatScatter(plotted, bounds(x) { it.second }, bounds(y) { it.third })
}

/** A selected song moves to the front without copying or sorting the entire list. */
internal fun promoteBeatScore(ordered: List<BeatScore>, selectedIndex: Int?): List<BeatScore> {
    if (selectedIndex == null || selectedIndex == 0) return ordered
    return object : AbstractList<BeatScore>() {
        override val size get() = ordered.size
        override fun get(index: Int): BeatScore = ordered[when {
            index == 0 -> selectedIndex
            index <= selectedIndex -> index - 1
            else -> index
        }]
    }
}
