package com.alinawaz.pixelclip

/** A final clip pick, snapped and sized, ready to show and to cut. Times in seconds. */
data class ClipPick(
    val start: Double,
    val end: Double,
    val title: String,
    val reason: String,
    val score: Int
)

object ClipPlanner {

    /**
     * Turns raw AI picks into the final list:
     * 1. snap start/end to real caption segment boundaries,
     * 2. pad 2s on both sides,
     * 3. grow (from the centre) or shrink to the chosen clip length,
     * 4. rank by score, drop overlaps, keep the top [count],
     * 5. return them in video order for display.
     */
    fun plan(
        raw: List<RawPick>,
        segments: List<Segment>,
        videoDurationSec: Double,
        count: Int,
        targetLengthSec: Double
    ): List<ClipPick> {
        if (raw.isEmpty() || segments.isEmpty()) return emptyList()
        val maxEnd = if (videoDurationSec > 0) videoDurationSec else segments.last().end

        val adjusted = raw.map { pick ->
            var start = snapStart(segments, pick.start)
            var end = snapEnd(segments, pick.end)
            start = maxOf(0.0, start - 2.0)
            end = minOf(maxEnd, end + 2.0)

            val dur = end - start
            if (dur < targetLengthSec) {
                val grow = (targetLengthSec - dur) / 2.0
                start = maxOf(0.0, start - grow)
                end = minOf(maxEnd, end + grow)
                // If one side hit a wall, give the rest to the other side.
                val missing = targetLengthSec - (end - start)
                if (missing > 0.5) {
                    if (start <= 0.0) end = minOf(maxEnd, end + missing)
                    if (end >= maxEnd) start = maxOf(0.0, start - missing)
                }
            } else if (dur > targetLengthSec) {
                end = start + targetLengthSec
            }
            ClipPick(start, end, pick.title, pick.reason, pick.score)
        }

        val ranked = adjusted.sortedByDescending { it.score }
        val kept = mutableListOf<ClipPick>()
        for (candidate in ranked) {
            if (kept.size >= count) break
            val overlaps = kept.any { candidate.start < it.end && candidate.end > it.start }
            if (!overlaps) kept.add(candidate)
        }
        return kept.sortedBy { it.start }
    }

    private fun snapStart(segments: List<Segment>, t: Double): Double {
        val inside = segments.firstOrNull { t >= it.start && t < it.end }
        if (inside != null) return inside.start
        val before = segments.lastOrNull { it.start <= t }
        return before?.start ?: segments.first().start
    }

    private fun snapEnd(segments: List<Segment>, t: Double): Double {
        val inside = segments.firstOrNull { t > it.start && t <= it.end }
        if (inside != null) return inside.end
        val after = segments.firstOrNull { it.end >= t }
        return after?.end ?: segments.last().end
    }
}
