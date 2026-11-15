package com.alinawaz.pixelclip

import java.io.File

/** One cleaned caption line-range from the video, times in seconds. */
data class Segment(
    val start: Double,
    val end: Double,
    val text: String
)

object Transcript {

    private val timeRegex = Regex("""(?:(\d+):)?(\d{1,2}):(\d{2})[.,](\d{3})""")

    private fun parseTime(s: String): Double? {
        val m = timeRegex.find(s.trim()) ?: return null
        val h = m.groupValues[1].toDoubleOrNull() ?: 0.0
        val min = m.groupValues[2].toDouble()
        val sec = m.groupValues[3].toDouble()
        val ms = m.groupValues[4].toDouble()
        return h * 3600 + min * 60 + sec + ms / 1000.0
    }

    private fun cleanText(raw: String): String =
        raw.replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&#39;", "'")
            .replace("&quot;", "\"")
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * Parses a YouTube VTT file into segments. YouTube auto-captions "roll":
     * every cue repeats the previous cue's last line before adding a new one.
     * That duplication is stripped so the AI (and the .srt sidecars) see each
     * spoken line once.
     */
    fun parseVtt(file: File): List<Segment> {
        val lines = file.readLines()
        val segments = mutableListOf<Segment>()
        var prevLines: List<String> = emptyList()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (!line.contains("-->")) {
                i++
                continue
            }
            val parts = line.split("-->")
            val start = parseTime(parts.getOrNull(0) ?: "")
            val end = parseTime(parts.getOrNull(1) ?: "")
            i++
            val textLines = mutableListOf<String>()
            while (i < lines.size && lines[i].isNotBlank()) {
                val t = cleanText(lines[i])
                if (t.isNotEmpty()) textLines.add(t)
                i++
            }
            if (start == null || end == null || textLines.isEmpty()) {
                prevLines = textLines
                continue
            }
            // Drop leading lines that repeat the previous cue (rolling captions).
            var fresh: List<String> = textLines
            while (fresh.isNotEmpty() && prevLines.contains(fresh.first())) {
                fresh = fresh.drop(1)
            }
            prevLines = textLines
            val text = fresh.joinToString(" ").trim()
            if (text.isEmpty()) continue
            // Same line re-timed by the caption track - keep the first copy.
            if (segments.isNotEmpty() && segments.last().text == text) continue
            segments.add(Segment(start, end, text))
        }
        return segments
    }

    /** Groups segments into ~[windowSec]-second windows for the AI step. */
    fun windows(segments: List<Segment>, windowSec: Double = 600.0): List<List<Segment>> {
        if (segments.isEmpty()) return emptyList()
        val result = mutableListOf<MutableList<Segment>>()
        var current = mutableListOf<Segment>()
        var windowStart = segments.first().start
        for (seg in segments) {
            if (current.isNotEmpty() && seg.start - windowStart >= windowSec) {
                result.add(current)
                current = mutableListOf()
                windowStart = seg.start
            }
            current.add(seg)
        }
        if (current.isNotEmpty()) result.add(current)
        return result
    }

    /** The transcript of one window in the "[start-end] text" form the AI prompt uses. Times are whole seconds. */
    fun windowText(window: List<Segment>): String =
        window.joinToString("\n") { seg ->
            "[${seg.start.toInt()}-${seg.end.toInt()}] ${seg.text}"
        }

    /**
     * Builds an .srt sidecar for one clip: the segments inside [fromSec]-[toSec],
     * re-timed so the clip starts at 0.
     */
    fun buildSrt(segments: List<Segment>, fromSec: Double, toSec: Double): String {
        val inside = segments.filter { it.end > fromSec && it.start < toSec }
        if (inside.isEmpty()) return ""
        val sb = StringBuilder()
        inside.forEachIndexed { idx, seg ->
            val s = maxOf(0.0, seg.start - fromSec)
            val e = maxOf(s + 0.1, minOf(seg.end, toSec) - fromSec)
            sb.append(idx + 1).append('\n')
            sb.append(srtTime(s)).append(" --> ").append(srtTime(e)).append('\n')
            sb.append(seg.text).append("\n\n")
        }
        return sb.toString()
    }

    private fun srtTime(sec: Double): String {
        val totalMs = (sec * 1000).toLong()
        val h = totalMs / 3_600_000
        val m = (totalMs % 3_600_000) / 60_000
        val s = (totalMs % 60_000) / 1000
        val ms = totalMs % 1000
        return "%02d:%02d:%02d,%03d".format(h, m, s, ms)
    }
}
