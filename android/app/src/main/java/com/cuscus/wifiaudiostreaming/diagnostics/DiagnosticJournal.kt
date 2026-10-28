package com.cuscus.wifiaudiostreaming.diagnostics

import java.io.File

/** Byte-bounded journal; fixed filenames prevent exported paths from coming from log input. */
internal class DiagnosticJournal(private val directory: File,
                                 private val limitBytes: Long = 1024 * 1024,
                                 private val archives: Int = 7) {
    init {
        require(limitBytes > 0 && archives in 1..16)
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create diagnostic directory" }
        val expiry = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        (listOf("runtime.log") + (1..archives).map { "runtime.$it.log" }).forEach { name ->
            val file = File(directory, name)
            if (file.isFile && file.lastModified() < expiry) {
                check(file.delete()) { "Cannot expire diagnostic archive" }
            }
        }
    }

    @Synchronized fun append(entry: String) {
        val line = (DiagnosticRedactor.clean(entry).take(6000).replace('\n', ' ').replace('\r', ' ') + "\n")
            .toByteArray(Charsets.UTF_8)
        require(line.size <= limitBytes) { "Diagnostic record exceeds journal capacity" }
        val current = File(directory, "runtime.log")
        if (current.length() + line.size > limitBytes) {
            val oldest = File(directory, "runtime.$archives.log")
            check(!oldest.exists() || oldest.delete()) { "Cannot remove oldest diagnostic archive" }
            for (i in archives - 1 downTo 1) {
                val from = File(directory, "runtime.$i.log")
                if (from.exists()) check(from.renameTo(File(directory, "runtime.${i + 1}.log"))) {
                    "Cannot rotate diagnostic archive $i"
                }
            }
            if (current.exists()) check(current.renameTo(File(directory, "runtime.1.log"))) {
                "Cannot rotate current diagnostic log"
            }
        }
        current.appendBytes(line)
    }

    @Synchronized fun snapshot(): List<Pair<String, ByteArray>> =
        (archives downTo 1).map { File(directory, "runtime.$it.log") }
            .plus(File(directory, "runtime.log")).filter { it.isFile }
            .map { it.name to it.readBytes() }
}

internal object DiagnosticRedactor {
    private val pairing = Regex("(?i)audiobridge://pair[^\\s]+")
    private val secrets = Regex("(?i)((?:auth[_-]?key|preshared[_-]?key|shared[_-]?key|manualAuthKey|token|password|secret|SIGNING_KEY_BASE64|key)[\"']?\\s*[=:]\\s*)(?:\"[^\"]*\"|'[^']*'|[^\\s,;&]+)")
    private val bearer = Regex("(?i)((?:Bearer|Basic)\\s+)[A-Za-z0-9._~+/=-]+")
    fun clean(text: String): String = bearer.replace(secrets.replace(pairing.replace(text, "[pairing-redacted]")) {
        it.groupValues[1] + "[redacted]"
    }, "$1[redacted]")
}
