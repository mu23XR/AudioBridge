package com.cuscus.wifiaudiostreaming

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray

object UpdateChecker {

    private const val REPO = "mu23XR/AudioBridge"
    const val RELEASES_URL = "https://github.com/mu23XR/AudioBridge/releases/latest"

    sealed class Result {
        data class UpToDate(val current: String) : Result()
        data class Available(val current: String, val latest: String, val url: String) : Result()
        data class Failed(val reason: String) : Result()
        /** La versione installata e' piu' recente di quella pubblicata su GitHub. */
        data class Ahead(val current: String, val latest: String) : Result()
    }

    fun currentVersion(context: Context): String =
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "0"

    suspend fun check(context: Context, timeoutMs: Int = 5000): Result = withContext(Dispatchers.IO) {
        try {
            val preview = context.packageName.endsWith(".test") || context.packageName.endsWith(".debug")
            val conn = (URL("https://api.github.com/repos/$REPO/releases?per_page=100")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "WFAS-UpdateChecker")
            }
            val body = try {
                val code = conn.responseCode
                if (code != 200) {
                    return@withContext Result.Failed("HTTP $code")
                }
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
            val entries = JSONArray(body)
            val releases = (0 until entries.length()).map { index ->
                val entry = entries.getJSONObject(index)
                val assets = entry.getJSONArray("assets")
                ReleaseChannel.Published(entry.getString("tag_name"), entry.getString("html_url"),
                    entry.getBoolean("prerelease"), entry.getBoolean("draft"),
                    (0 until assets.length()).map { assets.getJSONObject(it).getString("name") })
            }
            val release = ReleaseChannel.select(releases, preview)
                ?: return@withContext Result.Failed("No published release for this channel")
            val latest = normalize(release.tag)
            val current = normalize(currentVersion(context)).removeSuffix("-localdebug")
            val cmp = compareVersions(latest, current)
            when {
                cmp > 0  -> Result.Available(current, latest, release.url)
                cmp < 0  -> Result.Ahead(current, latest)
                else     -> Result.UpToDate(current)
            }
        } catch (e: Exception) {
            Result.Failed(e.message ?: "network error")
        }
    }

    fun normalize(tag: String): String =
        tag.trim().removePrefix("v").removePrefix("V").trim()

    fun compareVersions(a: String, b: String): Int {
        return ReleaseChannel.compare(a, b)
    }
}
