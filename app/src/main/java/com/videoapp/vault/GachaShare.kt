package com.videoapp.vault

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class RemoteGachaVideo(
    val catalogId: String,
    val sourceDevice: String,
    val name: String,
    val fileName: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val mime: String?,
) {
    fun toVideoItem() = VideoItem(
        id = "remote:$catalogId",
        uri = Uri.EMPTY,
        name = name,
        sizeBytes = sizeBytes,
        durationMs = durationMs,
        addedAt = 0L,
        folder = "Máy $sourceDevice",
        path = null,
        mime = mime,
        fileName = fileName,
        remoteDevice = sourceDevice,
    )
}

object GachaCatalogStore {
    const val MIME_TYPE = "application/json"
    private const val FORMAT = "kho-video-gacha"
    private const val VERSION = 1
    private const val PREFS = "kho_video"
    private const val KEY_DEVICE_ID = "gacha_device_id"
    private const val KEY_REMOTE = "gacha_remote_catalog"

    fun exportCatalog(context: Context, items: List<VideoItem>, sourceName: String): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val sourceId = prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_DEVICE_ID, it).apply()
        }
        val videos = JSONArray()
        items.filter { it.remoteDevice == null }.forEach { item ->
            videos.put(
                JSONObject()
                    .put("id", item.id)
                    .put("name", item.name)
                    .put("fileName", item.fileName)
                    .put("durationMs", item.durationMs)
                    .put("sizeBytes", item.sizeBytes)
                    .put("mime", item.mime ?: ""),
            )
        }
        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("sourceId", sourceId)
            .put("sourceName", sourceName.take(80))
            .put("videos", videos)
            .toString()
    }

    fun load(context: Context): List<RemoteGachaVideo> =
        runCatching { decodeStored(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_REMOTE, "[]")) }
            .getOrDefault(emptyList())

    fun importCatalog(context: Context, json: String): Int {
        val root = JSONObject(json)
        require(root.optString("format") == FORMAT && root.optInt("version") == VERSION) {
            "Unsupported Gacha catalog"
        }
        val sourceId = root.getString("sourceId").take(100)
        val sourceName = root.optString("sourceName", "Thiết bị khác").take(80)
        val videos = root.optJSONArray("videos") ?: JSONArray()
        require(videos.length() <= 20_000) { "Catalog is too large" }

        val merged = load(context).associateBy { it.catalogId }.toMutableMap()
        var added = 0
        for (index in 0 until videos.length()) {
            val item = videos.optJSONObject(index) ?: continue
            val localId = item.optString("id").take(200)
            val name = item.optString("name").take(200)
            if (localId.isBlank() || name.isBlank()) continue
            val catalogId = "$sourceId:$localId"
            if (catalogId !in merged) added++
            merged[catalogId] = RemoteGachaVideo(
                catalogId = catalogId,
                sourceDevice = sourceName,
                name = name,
                fileName = item.optString("fileName", name).take(240),
                durationMs = item.optLong("durationMs").coerceAtLeast(0L),
                sizeBytes = item.optLong("sizeBytes").coerceAtLeast(0L),
                mime = item.optString("mime").takeIf { it.isNotBlank() && it != "null" },
            )
        }
        require(videos.length() == 0 || merged.isNotEmpty()) { "No valid videos" }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_REMOTE, JSONArray().apply {
                merged.values.forEach { item ->
                    put(
                        JSONObject()
                            .put("catalogId", item.catalogId)
                            .put("sourceDevice", item.sourceDevice)
                            .put("name", item.name)
                            .put("fileName", item.fileName)
                            .put("durationMs", item.durationMs)
                            .put("sizeBytes", item.sizeBytes)
                            .put("mime", item.mime ?: ""),
                    )
                }
            }.toString())
            .apply()
        return added
    }

    fun readIncoming(context: Context, intent: Intent?): String? {
        if (intent == null) return null
        val uri = when (intent.action) {
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            }
            Intent.ACTION_VIEW -> intent.data
            else -> null
        } ?: return null
        return context.contentResolver.openInputStream(uri)?.use { input ->
            val bytes = input.readBytes()
            if (bytes.size > 2_000_000) return@use null
            bytes.toString(Charsets.UTF_8)
        }
    }

    private fun decodeStored(raw: String?): List<RemoteGachaVideo> {
        val array = JSONArray(raw ?: "[]")
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("catalogId")
                val name = item.optString("name")
                if (id.isBlank() || name.isBlank()) continue
                add(
                    RemoteGachaVideo(
                        catalogId = id,
                        sourceDevice = item.optString("sourceDevice", "Thiết bị khác"),
                        name = name,
                        fileName = item.optString("fileName", name),
                        durationMs = item.optLong("durationMs").coerceAtLeast(0L),
                        sizeBytes = item.optLong("sizeBytes").coerceAtLeast(0L),
                        mime = item.optString("mime").takeIf { it.isNotBlank() && it != "null" },
                    ),
                )
            }
        }
    }
}
