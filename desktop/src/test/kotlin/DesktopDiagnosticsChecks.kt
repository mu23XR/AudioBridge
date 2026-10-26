import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

object DesktopDiagnosticsChecks {
    @JvmStatic fun runChecks() {
        val directory = Files.createTempDirectory("audiobridge-diagnostic-check-").toFile()
        try {
            val journalDirectory = directory.resolve("journal")
            val journal = DesktopDiagnosticJournal(journalDirectory, limitBytes = 256, archives = 2)
            for (index in 0..18) journal.append("marker=$index authKey=check-secret-$index " + "x".repeat(110))
            val snapshot = journal.snapshot()
            check(snapshot.size <= 3 && snapshot.sumOf { it.second.size } <= 768)
            check(snapshot.flatMap { it.second.toList() }.isNotEmpty())
            check(snapshot.none { it.second.toString(Charsets.UTF_8).contains("check-secret-") })
            val reopened = DesktopDiagnosticJournal(journalDirectory, limitBytes = 256, archives = 2)
            check(reopened.snapshot().any { it.second.toString(Charsets.UTF_8).contains("marker=18") })
            val expired = journalDirectory.resolve("runtime.2.log")
            expired.writeText("expired")
            check(expired.setLastModified(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(8)))
            DesktopDiagnosticJournal(journalDirectory, limitBytes = 256, archives = 2)
            check(!expired.exists())
            val redacted = DesktopDiagnosticRedactor.clean("""{"manualAuthKey":"quoted secret with spaces","token":"abc123"} Bearer ABCD-1234 https://user:pass@example.invalid/?password=test-password audiobridge://pair?key=pair-secret""")
            for (secret in listOf("quoted secret", "abc123", "ABCD-1234", "user:pass", "test-password", "pair-secret")) check(secret !in redacted)
            check("truncated secret" !in DesktopDiagnosticRedactor.clean("password=\"truncated secret"))
            check(DesktopDiagnostics.isLegacySummary("Output failed: unavailable"))
            check(!DesktopDiagnostics.isLegacySummary("unknown msg (#1): 'failure raw audio'"))

            DesktopDiagnostics.initialize(directory.resolve("runtime"))
            DesktopDiagnostics.event("RESTART_MARKER", "password=never-export-this")
            val settings = ConfigManager.DEFAULTS.copy(app = AppSettings(authKey = "not-in-report", manualAuthKey = "also-not-in-report",
                receiverSources = listOf("not-a-metadata-export")))
            val destination = directory.resolve("support.zip")
            DesktopDiagnostics.exportZip(destination, settings)
            val originalBytes = destination.readBytes()
            check(runCatching { DesktopDiagnostics.exportZip(destination, settings) }.isFailure)
            check(destination.readBytes().contentEquals(originalBytes)) { "Export overwrote an existing file without permission" }
            ZipFile(destination).use { zip ->
                val entries = zip.entries().toList()
                check(entries.all { it.name == "report.txt" || it.name.matches(Regex("runtime(?:\\.[1-7])?\\.log")) })
                val text = entries.joinToString("\n") { zip.getInputStream(it).use { stream -> stream.readBytes().toString(Charsets.UTF_8) } }
                check("RESTART_MARKER" in text)
                check(listOf("never-export-this", "not-in-report", "also-not-in-report", "not-a-metadata-export").none { it in text })
            }
            DesktopDiagnostics.shutdown()
        } finally {
            DesktopDiagnostics.shutdown()
            check(directory.deleteRecursively())
        }
        println("Desktop bounded diagnostic retention, restart history, redaction and allowlisted ZIP checks passed")
    }
}
