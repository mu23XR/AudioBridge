package com.cuscus.wifiaudiostreaming.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.cuscus.wifiaudiostreaming.BuildConfig
import com.cuscus.wifiaudiostreaming.data.SettingsDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.OutputStream
import java.io.FilterOutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object RuntimeDiagnostics {
    private val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(256))
    private val dropped = AtomicInteger()
    @Volatile private var journal: DiagnosticJournal? = null
    @Volatile private var lastError = ""

    @JvmStatic @Synchronized fun initialize(context: Context) {
        if (journal != null) return
        try {
            journal = DiagnosticJournal(java.io.File(context.filesDir, "diagnostics"))
        } catch (error: Exception) {
            lastError = "journal initialization: ${error.javaClass.simpleName}"
            Log.e("AB-DIAG", lastError, error)
            return
        }
        event("PROCESS_START", "package=${context.packageName} version=${BuildConfig.VERSION_NAME} code=${BuildConfig.VERSION_CODE} sdk=${Build.VERSION.SDK_INT} model=${Build.MODEL}", true)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            event("UNCAUGHT_EXCEPTION", "thread=${thread.name} ${Log.getStackTraceString(error)}", true)
            previous?.uncaughtException(thread, error) ?: run {
                Process.killProcess(Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
        if (Build.VERSION.SDK_INT >= 30) {
            submit {
                try {
                    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                    manager.getHistoricalProcessExitReasons(context.packageName, 0, 8).forEach {
                        write("PREVIOUS_EXIT", "time=${it.timestamp} pid=${it.pid} reason=${it.reason} importance=${it.importance} pssKb=${it.pss} rssKb=${it.rss} description=${it.description}")
                    }
                } catch (error: Exception) {
                    write("EXIT_QUERY_FAILED", error.javaClass.simpleName)
                }
            }
        }
    }

    @JvmStatic @JvmOverloads fun event(event: String, detail: String = "", critical: Boolean = false) {
        if (journal == null) {
            return
        }
        // Capture event time before queuing; wall and monotonic time are both needed.
        val wall = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        val pid = Process.myPid()
        val text = "wall=$wall elapsed=$elapsed pid=$pid event=$event $detail"
        if (critical) append(text) else submit { append(text) }
    }

    private fun write(kind: String, detail: String) = event(kind, detail, true)
    private fun append(text: String) {
        try {
            val lost = dropped.getAndSet(0)
            if (lost > 0) journal?.append("LOGGER_QUEUE_DROP count=$lost")
            journal?.append(text)
        } catch (error: Exception) {
            lastError = "journal write: ${error.javaClass.simpleName}"
            Log.e("AB-DIAG", lastError, error)
        }
    }

    private fun submit(task: () -> Unit) {
        try { executor.execute(task) }
        catch (_: RejectedExecutionException) { dropped.incrementAndGet() }
    }

    /** Called on an IO dispatcher after the user chooses a Storage Access Framework destination. */
    fun export(context: Context, destination: Uri) {
        val output = checkNotNull(context.contentResolver.openOutputStream(destination, "wt")) {
            "Cannot open diagnostic export destination"
        }
        output.use { exportToStream(context, it) }
    }

    /** Same redacted allowlist for SAF and shell exports; caller owns the stream. */
    fun exportToStream(context: Context, output: OutputStream) {
        initialize(context)
        val store = checkNotNull(journal) { "Diagnostic storage unavailable: $lastError" }
        val barrier = executor.submit { }
        barrier.get(10, TimeUnit.SECONDS)
        val settings = runBlocking { kotlinx.coroutines.withTimeout(5000) {
            SettingsDataStore(context).settingsFlow.first()
        } }
        // Explicit allowlist: never serialize settings objects containing authentication secrets.
        val metadata = """
            AudioBridge diagnostics
            created=${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT).format(Date())}
            package=${context.packageName}
            version=${BuildConfig.VERSION_NAME}/${BuildConfig.VERSION_CODE}
            model=${Build.MODEL} sdk=${Build.VERSION.SDK_INT}
            adaptive=${settings.adaptiveLatency} latencyMs=${settings.latencyMs}
            sampleRate=${settings.sampleRate} channels=${settings.channelConfig} payload=${settings.maxPayloadBytes}
            internalBackend=${settings.internalAudioBackend} localMuted=${settings.muteRender}
            senderPlaybackChoice=${settings.senderLocalPlayback}
            senderGain=${com.cuscus.wifiaudiostreaming.NetworkManager.serverVolume.value}
            receiverMasterGain=${com.cuscus.wifiaudiostreaming.NetworkManager.clientVolume.value}
            persistentSender=${settings.serverPersist} persistentReceiver=${settings.clientPersistentConnection}
            autoConnect=${settings.autoConnectEnabled} usb=${settings.usbModeEnabled}
            noiseReduction=${settings.noiseReductionEnabled} noiseStrength=${settings.noiseReductionStrength}
            securityMode=${settings.securityMode} encryption=${settings.encryptionEnabled}
            loggerError=$lastError queued=${executor.queue.size} queueDrops=${dropped.get()}
        """.trimIndent()
        // Closing the ZIP releases its native deflater without taking ownership
        // of the SAF/provider stream managed by the caller.
        ZipOutputStream(object : FilterOutputStream(output) {
            override fun close() { flush() }
        }).use { zip ->
            zip.putNextEntry(ZipEntry("report.txt"))
            zip.write(DiagnosticRedactor.clean(metadata).toByteArray())
            zip.closeEntry()
            store.snapshot().forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        event("EXPORT_COMPLETE")
    }
}
