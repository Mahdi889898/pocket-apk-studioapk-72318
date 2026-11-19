package com.example.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private val Context.studioDataStore by preferencesDataStore(name = "studio_apk_store")

class BuildRepository(private val context: Context) {

    companion object {
        private val KEY_BUILDS_JSON = stringPreferencesKey("build_records_json")
    }

    val buildRecordsFlow: Flow<List<BuildRecord>> = context.studioDataStore.data.map { prefs ->
        val raw = prefs[KEY_BUILDS_JSON] ?: "[]"
        parseRecords(raw).filter { File(it.apkPath).exists() }
    }

    suspend fun addBuildRecord(record: BuildRecord) {
        context.studioDataStore.edit { prefs ->
            val current = parseRecords(prefs[KEY_BUILDS_JSON] ?: "[]").toMutableList()
            current.removeAll { it.id == record.id }
            current.add(0, record)
            prefs[KEY_BUILDS_JSON] = serializeRecords(current.take(30))
        }
    }

    suspend fun deleteBuildRecord(record: BuildRecord) {
        File(record.apkPath).delete()
        File(record.previewDirPath).deleteRecursively()
        context.studioDataStore.edit { prefs ->
            val current = parseRecords(prefs[KEY_BUILDS_JSON] ?: "[]").toMutableList()
            current.removeAll { it.id == record.id }
            prefs[KEY_BUILDS_JSON] = serializeRecords(current)
        }
    }

    private fun parseRecords(raw: String): List<BuildRecord> {
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val enginesArr = o.optJSONArray("engines")
                    val engines = buildList {
                        if (enginesArr != null) {
                            for (j in 0 until enginesArr.length()) {
                                add(enginesArr.getString(j))
                            }
                        }
                    }
                    add(
                        BuildRecord(
                            id = o.getString("id"),
                            appName = o.getString("appName"),
                            packageName = o.getString("packageName"),
                            versionName = o.getString("versionName"),
                            versionCode = o.getInt("versionCode"),
                            apkPath = o.getString("apkPath"),
                            previewDirPath = o.getString("previewDirPath"),
                            entryPoint = o.getString("entryPoint"),
                            orientation = o.optString("orientation", "auto"),
                            fullscreen = o.optBoolean("fullscreen", true),
                            keepScreenOn = o.optBoolean("keepScreenOn", true),
                            apkSizeBytes = o.getLong("apkSizeBytes"),
                            engines = engines,
                            sha256Fingerprint = o.optString("sha256Fingerprint", ""),
                            createdAtMillis = o.getLong("createdAtMillis")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun serializeRecords(records: List<BuildRecord>): String {
        val arr = JSONArray()
        for (r in records) {
            val o = JSONObject().apply {
                put("id", r.id)
                put("appName", r.appName)
                put("packageName", r.packageName)
                put("versionName", r.versionName)
                put("versionCode", r.versionCode)
                put("apkPath", r.apkPath)
                put("previewDirPath", r.previewDirPath)
                put("entryPoint", r.entryPoint)
                put("orientation", r.orientation)
                put("fullscreen", r.fullscreen)
                put("keepScreenOn", r.keepScreenOn)
                put("apkSizeBytes", r.apkSizeBytes)
                put("engines", JSONArray(r.engines))
                put("sha256Fingerprint", r.sha256Fingerprint)
                put("createdAtMillis", r.createdAtMillis)
            }
            arr.put(o)
        }
        return arr.toString()
    }
}
