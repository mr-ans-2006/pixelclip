package com.alinawaz.pixelclip

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** A best-moment pick straight from the AI, before snapping/ranking. Times in seconds. */
data class RawPick(
    val start: Double,
    val end: Double,
    val title: String,
    val reason: String,
    val score: Int
)

object GeminiClient {

    private const val ENDPOINT =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent"

    /**
     * Asks Gemini for the best moments in ONE transcript window for [mode].
     * Uses the user's own API key - PixelClip ships with no key of its own.
     * Output is parsed strictly as a JSON array; anything else is an error.
     */
    suspend fun findMoments(apiKey: String, mode: String, window: List<Segment>): List<RawPick> =
        withContext(Dispatchers.IO) {
            val transcript = Transcript.windowText(window)
            val prompt = """
                You are the clip finder inside a YouTube clipping app.
                Below is one part of a video transcript. Every line starts with
                [start-end] timestamps in SECONDS, followed by the spoken text.

                Find the best moments for making short clips in this mode: $mode.
                - Funny: the funniest lines, jokes, reactions, surprises.
                - Study: the clearest explanations, definitions, key points, examples.
                - Motivational: the strongest, most inspiring, most emotional lines.
                - Viral: hooks, bold claims, shocking or highly shareable moments.

                Rules:
                - Return ONLY a JSON array. No markdown, no explanation, no other text.
                - Each item: {"start": <seconds>, "end": <seconds>, "title": "<short clip title>", "reason": "<why this moment works, one line>", "score": <1-10, 10 = strongest>}
                - start and end MUST be copied from the timestamps in the transcript.
                - Each moment should be one complete thought, roughly 20-90 seconds.
                - Pick at most 5 moments from this part. If nothing is good, return [].
                - Never invent timestamps or text that is not in the transcript.

                TRANSCRIPT:
                $transcript
            """.trimIndent()

            val body = JSONObject().apply {
                put(
                    "contents",
                    JSONArray().put(
                        JSONObject().put(
                            "parts",
                            JSONArray().put(JSONObject().put("text", prompt))
                        )
                    )
                )
                put(
                    "generationConfig",
                    JSONObject()
                        .put("temperature", 0.4)
                        .put("responseMimeType", "application/json")
                )
            }

            val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-goog-api-key", apiKey)
                connectTimeout = 30_000
                readTimeout = 120_000
                doOutput = true
            }
            try {
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val responseText = try {
                    (if (code in 200..299) conn.inputStream else conn.errorStream)
                        ?.bufferedReader()?.readText() ?: ""
                } catch (e: Exception) {
                    ""
                }
                if (code !in 200..299) {
                    throw RuntimeException(apiErrorMessage(code, responseText))
                }
                parsePicks(responseText)
            } finally {
                conn.disconnect()
            }
        }

    private fun apiErrorMessage(code: Int, responseText: String): String {
        val apiMsg = try {
            JSONObject(responseText).getJSONObject("error").optString("message")
        } catch (e: Exception) {
            ""
        }
        return when {
            code == 400 && apiMsg.contains("API key", ignoreCase = true) ->
                "Gemini rejected the API key. Check it in the SETUP tab."
            code == 400 -> "Gemini error: ${apiMsg.take(120).ifBlank { "bad request" }}"
            code == 403 -> "Gemini rejected the API key. Check it in the SETUP tab."
            code == 429 -> "Gemini free limit hit. Wait a bit and try again."
            else -> "Gemini failed (HTTP $code). ${apiMsg.take(100)}"
        }
    }

    private fun parsePicks(responseText: String): List<RawPick> {
        val root = JSONObject(responseText)
        val text = root.getJSONArray("candidates")
            .getJSONObject(0)
            .getJSONObject("content")
            .getJSONArray("parts")
            .getJSONObject(0)
            .getString("text")
        val jsonText = text.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        val arr = JSONArray(jsonText)
        val picks = mutableListOf<RawPick>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val start = o.optDouble("start", -1.0)
            val end = o.optDouble("end", -1.0)
            if (start < 0 || end <= start) continue
            picks.add(
                RawPick(
                    start = start,
                    end = end,
                    title = o.optString("title").ifBlank { "Clip" },
                    reason = o.optString("reason"),
                    score = o.optInt("score", 5).coerceIn(1, 10)
                )
            )
        }
        return picks
    }
}
