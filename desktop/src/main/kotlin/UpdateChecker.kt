/*
 * Copyright (c) 2026 Marco Morosi
 *
 * Licensed under the EUPL, Version 1.2 or – as soon they will be approved by
 * the European Commission - subsequent versions of the EUPL (the "Licence");
 * You may not use this work except in compliance with the Licence.
 * You may obtain a copy of the Licence at:
 *
 * https://joinup.ec.europa.eu/software/page/eupl
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Licence is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Licence for the specific language governing permissions and
 * limitations under the Licence.
 */

import java.net.HttpURLConnection
import java.net.URL

object DownloadLinks {
    const val GITHUB_RELEASES = "https://github.com/mu23XR/AudioBridge/releases"
    fun site(): String = GITHUB_RELEASES
}


object UpdateChecker {

    private const val REPO = "mu23XR/AudioBridge"
    val RELEASES_URL: String get() = if (BuildIdentity.preview) DownloadLinks.GITHUB_RELEASES else "${DownloadLinks.GITHUB_RELEASES}/latest"

    val currentVersion: String get() = Strings.appVersion

    sealed class Result {
        data class UpToDate(val current: String) : Result()
        data class Available(val current: String, val latest: String, val url: String) : Result()
        data class Failed(val reason: String) : Result()
        /** La versione installata e' piu' recente di quella pubblicata su GitHub. */
        data class Ahead(val current: String, val latest: String) : Result()
    }

    private val VERSION_RX = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$")

    data class Release(val version: String, val url: String)

    /** Startup only interrupts for an installable update; other results belong to a manual check. */
    fun automaticNotice(result: Result): Result.Available? = result as? Result.Available

    /** Ignore Android archives and releases belonging to the other channel. */
    fun selectRelease(json: String, preview: Boolean, platform: String): Release? {
        val list = ConfigManager.parseJson(json) as? List<*> ?: throw IllegalArgumentException("Expected a GitHub release list")
        return list.mapNotNull { entry ->
            val item = entry as? Map<*, *> ?: return@mapNotNull null
            if (item["draft"] == true) return@mapNotNull null
            val tag = item["tag_name"] as? String ?: return@mapNotNull null
            val version = normalize(tag)
            if (!VERSION_RX.matches(version)) return@mapNotNull null
            val test = version.contains("-test.")
            if (preview != test || (preview && item["prerelease"] != true) || (!preview && (item["prerelease"] == true || '-' in version))) return@mapNotNull null
            val assets = item["assets"] as? List<*> ?: return@mapNotNull null
            val compatible = assets.any { asset ->
                val name = (asset as? Map<*, *>)?.get("name") as? String ?: return@any false
                name.startsWith("AudioBridge-") && name.contains("-$version-$platform.") &&
                    (name.endsWith(".zip") || name.endsWith(".tar.gz"))
            }
            if (!compatible) return@mapNotNull null
            Release(version, "${DownloadLinks.GITHUB_RELEASES}/tag/$tag")
        }.maxWithOrNull { a, b -> compareVersions(a.version, b.version) }
    }

    fun check(timeoutMs: Int = 5000): Result {
        return try {
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
                if (code != 200) return Result.Failed("HTTP $code")
                val bytes = conn.inputStream.use { it.readNBytes(8 * 1024 * 1024 + 1) }
                require(bytes.size <= 8 * 1024 * 1024) { "Release response too large" }
                bytes.toString(Charsets.UTF_8)
            } finally { conn.disconnect() }
            val os = when (ConfigPaths.os) {
                HostOs.WINDOWS -> "windows"
                HostOs.MACOS -> "macos"
                HostOs.LINUX -> "linux"
                else -> return Result.Failed("Unsupported platform")
            }
            val arch = System.getProperty("os.arch").lowercase().let { if (it in listOf("aarch64", "arm64")) "arm64" else "x86_64" }
            val release = selectRelease(body, BuildIdentity.preview, "$os-$arch")
                ?: return Result.Failed(Strings.get("update_no_channel"))
            val latest = release.version
            val current = normalize(currentVersion)
            val cmp = compareVersions(latest, current)
            when {
                cmp > 0 -> Result.Available(current, latest, release.url)
                cmp < 0 -> Result.Ahead(current, latest)
                else    -> Result.UpToDate(current)
            }
        } catch (e: Exception) {
            Result.Failed(e.message ?: "network error")
        }
    }

    fun normalize(tag: String): String =
        tag.trim().removePrefix("v").removePrefix("V").trim()

    fun compareVersions(a: String, b: String): Int {
        val pa = VERSION_RX.matchEntire(normalize(a)) ?: error("Invalid version: $a")
        val pb = VERSION_RX.matchEntire(normalize(b)) ?: error("Invalid version: $b")
        for (i in 1..3) {
            val cmp = pa.groupValues[i].toBigInteger().compareTo(pb.groupValues[i].toBigInteger())
            if (cmp != 0) return cmp
        }
        val preA = pa.groupValues[4]
        val preB = pb.groupValues[4]
        if (preA.isEmpty() || preB.isEmpty()) return when {
            preA == preB -> 0
            preA.isEmpty() -> 1
            else -> -1
        }
        val partsA = preA.split('.')
        val partsB = preB.split('.')
        for (i in 0 until minOf(partsA.size, partsB.size)) {
            val x = partsA[i]; val y = partsB[i]
            val nx = x.toBigIntegerOrNull(); val ny = y.toBigIntegerOrNull()
            val cmp = when {
                nx != null && ny != null -> nx.compareTo(ny)
                nx != null -> -1
                ny != null -> 1
                else -> x.compareTo(y)
            }
            if (cmp != 0) return cmp
        }
        return partsA.size.compareTo(partsB.size)
    }
}
