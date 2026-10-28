package com.cuscus.wifiaudiostreaming

/** Published previews must have their own asset identity: old tests used the stable package. */
object ReleaseChannel {
    data class Published(val tag: String, val url: String, val prerelease: Boolean,
                         val draft: Boolean, val assets: List<String>)
    private val version = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)(?:-test\\.(\\d+))?$")

    fun select(releases: List<Published>, preview: Boolean): Published? = releases
        .filter { release ->
            val match = version.matchEntire(UpdateChecker.normalize(release.tag))
            !release.draft && match != null &&
                release.prerelease == preview &&
                match.groupValues[4].isNotEmpty() == preview &&
                release.assets.any { it.startsWith(if (preview) "AudioBridge-Android-Preview-" else "AudioBridge-Android-") && it.endsWith(".apk") }
        }
        .maxWithOrNull { a, b -> compare(a.tag, b.tag) }

    fun compare(a: String, b: String): Int {
        val left = version.matchEntire(UpdateChecker.normalize(a))
            ?: throw IllegalArgumentException("Unsupported version: $a")
        val right = version.matchEntire(UpdateChecker.normalize(b))
            ?: throw IllegalArgumentException("Unsupported version: $b")
        for (index in 1..3) {
            val comparison = left.groupValues[index].toLong().compareTo(right.groupValues[index].toLong())
            if (comparison != 0) return comparison
        }
        val l = left.groupValues[4].toLongOrNull()
        val r = right.groupValues[4].toLongOrNull()
        return when {
            l == null && r == null -> 0
            l == null -> 1
            r == null -> -1
            else -> l.compareTo(r)
        }
    }
}
