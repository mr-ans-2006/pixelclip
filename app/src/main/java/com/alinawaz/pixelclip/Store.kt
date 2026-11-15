package com.alinawaz.pixelclip

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class SavedClip(
    val title: String,
    val uri: String,
    val srtUri: String,
    val fileName: String,
    val sizeBytes: Long,
    val dateMs: Long,
    val sourceUrl: String,
    val sourceTitle: String,
    val durationSec: Int
)

/** Keeps the list of finished clips in a small JSON file on the phone, like PixelDL. */
object Store {

    private fun storeFile(context: Context) = File(context.filesDir, "clips.json")

    fun load(context: Context): MutableList<SavedClip> {
        val f = storeFile(context)
        if (!f.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(f.readText())
            val list = mutableListOf<SavedClip>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    SavedClip(
                        title = o.optString("title"),
                        uri = o.optString("uri"),
                        srtUri = o.optString("srtUri"),
                        fileName = o.optString("fileName"),
                        sizeBytes = o.optLong("sizeBytes"),
                        dateMs = o.optLong("dateMs"),
                        sourceUrl = o.optString("sourceUrl"),
                        sourceTitle = o.optString("sourceTitle"),
                        durationSec = o.optInt("durationSec")
                    )
                )
            }
            list
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun add(context: Context, item: SavedClip) {
        val list = load(context)
        list.add(0, item)
        save(context, list)
    }

    fun remove(context: Context, item: SavedClip) {
        val list = load(context)
        list.removeAll { it.uri == item.uri && it.dateMs == item.dateMs }
        save(context, list)
    }

    private fun save(context: Context, list: List<SavedClip>) {
        val arr = JSONArray()
        list.forEach { item ->
            arr.put(
                JSONObject().apply {
                    put("title", item.title)
                    put("uri", item.uri)
                    put("srtUri", item.srtUri)
                    put("fileName", item.fileName)
                    put("sizeBytes", item.sizeBytes)
                    put("dateMs", item.dateMs)
                    put("sourceUrl", item.sourceUrl)
                    put("sourceTitle", item.sourceTitle)
                    put("durationSec", item.durationSec)
                }
            )
        }
        storeFile(context).writeText(arr.toString())
    }
}
