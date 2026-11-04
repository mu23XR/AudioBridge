import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object DesktopDiagnosticRedactor {
    private val pairing = Regex("(?i)audiobridge://pair[^\\s]+")
    private val secrets = Regex("(?i)((?:auth[_-]?key|preshared[_-]?key|shared[_-]?key|manualAuthKey|token|password|secret|SIGNING_KEY_BASE64|key)[\"']?\\s*[=:]\\s*)(?:\"[^\"]*(?:\"|$)|'[^']*(?:'|$)|[^\\s,;&]+)")
    private val bearer = Regex("(?i)((?:Bearer|Basic)\\s+)[A-Za-z0-9._~+/=-]+")
    private val urlCredentials = Regex("(https?://)[^/\\s:@]+:[^/\\s@]+@", RegexOption.IGNORE_CASE)
    fun clean(text: String): String = urlCredentials.replace(
        bearer.replace(secrets.replace(pairing.replace(text, "[pairing-redacted]")) {
            it.groupValues[1] + "[redacted]"
        }, "$1[redacted]"), "$1[redacted]@")
}

/** Fixed names and byte bounds make snapshot export independent of log input. */
internal class DesktopDiagnosticJournal(
    private val directory: File,
    private val limitBytes: Long = 1024 * 1024,
    private val archives: Int = 7
) {
    init {
        require(limitBytes >= 256 && archives in 1..16)
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create diagnostic directory" }
        val expiry = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
        files().filter { it.isFile && it.lastModified() < expiry }.forEach {
            check(it.delete()) { "Cannot expire diagnostic archive ${it.name}" }
        }
    }

    private fun files() = (archives downTo 1).map { File(directory, "runtime.$it.log") }
        .plus(File(directory, "runtime.log"))

    @Synchronized fun append(text: String) {
        val record = (DesktopDiagnosticRedactor.clean(text).take(6000)
            .replace('\n', ' ').replace('\r', ' ') + "\n").toByteArray(Charsets.UTF_8)
        require(record.size <= limitBytes) { "Diagnostic record exceeds journal capacity" }
        val current = File(directory, "runtime.log")
        if (current.length() + record.size > limitBytes) {
            val oldest = File(directory, "runtime.$archives.log")
            check(!oldest.exists() || oldest.delete()) { "Cannot remove oldest diagnostic archive" }
            for (index in archives - 1 downTo 1) {
                val previous = File(directory, "runtime.$index.log")
                if (previous.exists()) check(previous.renameTo(File(directory, "runtime.${index + 1}.log"))) {
                    "Cannot rotate diagnostic archive $index"
                }
            }
            if (current.exists()) check(current.renameTo(File(directory, "runtime.1.log"))) {
                "Cannot rotate current diagnostic log"
            }
        }
        current.appendBytes(record)
    }

    @Synchronized fun snapshot(): List<Pair<String, ByteArray>> =
        files().filter { it.isFile }.map { it.name to it.readBytes() }
}

/** Audio threads enqueue bounded event summaries; they never wait on disk or export PCM. */
object DesktopDiagnostics {
    private val dropped = AtomicInteger()
    private val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(256)) { task ->
        Thread(task, "audiobridge-diagnostics").apply { isDaemon = true }
    }
    @Volatile private var journal: DesktopDiagnosticJournal? = null
    @Volatile var lastError: String = ""
        private set

    private val summary = Regex("(?i)error|failed|failure|timeout|stopped|started|restor|cleanup|recover|refused")
    private val rawPacket = Regex("(?i)unknown msg|unexpected reply|packet|audioPkt|pkt #|received from|rx '")

    internal fun isLegacySummary(message: String): Boolean = summary.containsMatchIn(message) && !rawPacket.containsMatchIn(message)

    fun recordLegacy(message: String) {
        // Old debug code includes raw received text. Persist selected lifecycle/error summaries only.
        if (isLegacySummary(message)) event("RUNTIME", message)
    }

    @Synchronized fun initialize(directory: File = File(ConfigPaths.configDir(), "diagnostics")) {
        if (journal != null) return
        try {
            journal = DesktopDiagnosticJournal(directory)
        } catch (failure: Exception) {
            reportFailure("initialize", failure)
            return
        }
        event("PROCESS_START", "version=${BuildIdentity.version} pid=${ProcessHandle.current().pid()} os=${ConfigPaths.osLabel}")
    }

    fun event(kind: String, detail: String = "") {
        val store = journal ?: return
        val record = "wall=${System.currentTimeMillis()} elapsed=${System.nanoTime() / 1_000_000} event=${kind.take(64)} ${detail.take(6000)}"
        try {
            executor.execute {
                try {
                    val lost = dropped.getAndSet(0)
                    if (lost > 0) store.append("LOGGER_QUEUE_DROP count=$lost")
                    store.append(record)
                } catch (failure: Exception) { reportFailure("write", failure) }
            }
        } catch (_: RejectedExecutionException) { dropped.incrementAndGet() }
    }

    private fun reportFailure(operation: String, failure: Exception) {
        lastError = "$operation: ${failure.javaClass.simpleName}"
        System.err.println("AudioBridge diagnostics $lastError")
    }

    fun flush() {
        executor.submit { }.get(10, TimeUnit.SECONDS)
    }

    @Synchronized fun shutdown() {
        flush()
        journal = null
    }

    /** Explicit metadata allowlist: do not serialize settings containing credentials or key references. */
    fun exportZip(destination: File, settings: AllSettings, replaceExisting: Boolean = false) {
        initialize()
        val store = checkNotNull(journal) { "Diagnostic storage unavailable: $lastError" }
        flush()
        require(replaceExisting || !destination.exists()) { "Diagnostic export already exists" }
        val parent = destination.absoluteFile.parentFile
        require(parent.isDirectory) { "Diagnostic export directory does not exist" }
        val report = """
            AudioBridge diagnostics
            version=${BuildIdentity.version} preview=${BuildIdentity.preview}
            os=${ConfigPaths.osLabel} java=${System.getProperty("java.version")}
            adaptive=${settings.audio.adaptiveLatency} latencyMs=${settings.audio.latencyMs}
            sampleRate=${settings.audio.sampleRate} channels=${settings.audio.channels} payload=${settings.audio.maxPayloadBytes}
            localPlayback=${!settings.app.muteRender} senderGain=${settings.app.senderGain} receiverGain=${settings.app.receiverGain}
            remoteVolumeSync=${settings.app.remoteVolumeSyncEnabled}
            securityMode=${settings.app.securityMode} encryption=${settings.app.encryptionEnabled}
            loggerError=$lastError queued=${executor.queue.size} queueDrops=${dropped.get()}
        """.trimIndent()
        val temporary = Files.createTempFile(parent.toPath(), ".audiobridge-diagnostics-", ".zip")
        try {
            ZipOutputStream(Files.newOutputStream(temporary)).use { zip ->
                zip.putNextEntry(ZipEntry("report.txt"))
                zip.write(DesktopDiagnosticRedactor.clean(report).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                store.snapshot().forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            // An atomic move can replace a target even without REPLACE_EXISTING on some providers.
            // Preserve the explicit no-overwrite contract for ordinary user exports.
            if (replaceExisting) {
                try { Files.move(temporary, destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary, destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } else Files.move(temporary, destination.toPath())
        } finally { Files.deleteIfExists(temporary) }
        event("EXPORT_COMPLETE")
    }
}
