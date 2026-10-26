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

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Minimize
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.compose.runtime.collectAsState
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import javax.sound.sampled.*
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Maximize
import androidx.compose.material.icons.filled.Restore
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.WindowState
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.FileDialog
import java.awt.Frame
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import androidx.compose.ui.graphics.Color

// ─────────────────────────────────────────────────────────────────────────────
// Theme
// ─────────────────────────────────────────────────────────────────────────────

object ThemeEngine {
    fun pickImageAndExtractColor(): Long? {
        return try {
            val dialog = FileDialog(null as Frame?, "Select a Wallpaper", FileDialog.LOAD)
            dialog.file = "*.jpg;*.png;*.jpeg"
            dialog.isVisible = true

            if (dialog.directory == null || dialog.file == null) return null
            val file = File(dialog.directory, dialog.file)
            if (!file.exists()) return null

            val img = ImageIO.read(file) ?: return null
            val img1x1 = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)
            val graphics = img1x1.createGraphics()
            graphics.drawImage(img.getScaledInstance(1, 1, java.awt.Image.SCALE_FAST), 0, 0, null)
            graphics.dispose()

            val rgb = img1x1.getRGB(0, 0)
            val awtColor = java.awt.Color(rgb)
            Color(awtColor.red, awtColor.green, awtColor.blue).value.toLong()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

object MaterialYouGenerator {
    fun generateDynamicColorScheme(seedColor: Color, isDark: Boolean): ColorScheme {
        val hsb = FloatArray(3)
        java.awt.Color.RGBtoHSB(
            (seedColor.red * 255).toInt(),
            (seedColor.green * 255).toInt(),
            (seedColor.blue * 255).toInt(),
            hsb
        )
        val h       = hsb[0]
        val s       = hsb[1].coerceAtLeast(0.15f)
        val b       = hsb[2]
        val bFactor  = b.coerceIn(0.5f, 1.0f)
        val bgFactor = b.coerceIn(0.85f, 1.0f)
        val tH = (h + 0.15f) % 1f

        fun fromHsb(hue: Float, sat: Float, bright: Float, factor: Float = 1f): Color =
            Color(java.awt.Color.HSBtoRGB(hue % 1f, sat.coerceIn(0f, 1f), (bright * factor).coerceIn(0f, 1f)))

        return if (!isDark) lightColorScheme(
            primary                = fromHsb(h,  s * 0.9f,  0.4f,  bFactor),
            onPrimary              = Color.White,
            primaryContainer       = fromHsb(h,  s * 0.4f,  0.9f,  bFactor),
            onPrimaryContainer     = fromHsb(h,  s * 1.0f,  0.15f),
            secondary              = fromHsb(h,  s * 0.3f,  0.45f, bFactor),
            onSecondary            = Color.White,
            secondaryContainer     = fromHsb(h,  s * 0.2f,  0.92f, bFactor),
            onSecondaryContainer   = fromHsb(h,  s * 0.5f,  0.15f),
            tertiary               = fromHsb(tH, s * 0.4f,  0.45f, bFactor),
            onTertiary             = Color.White,
            tertiaryContainer      = fromHsb(tH, s * 0.25f, 0.92f, bFactor),
            onTertiaryContainer    = fromHsb(tH, s * 0.6f,  0.15f),
            background             = fromHsb(h,  s * 0.05f, 0.98f, bgFactor),
            onBackground           = fromHsb(h,  s * 0.1f,  0.1f),
            surface                = fromHsb(h,  s * 0.05f, 0.98f, bgFactor),
            onSurface              = fromHsb(h,  s * 0.1f,  0.1f),
            surfaceVariant         = fromHsb(h,  s * 0.1f,  0.9f,  bgFactor),
            onSurfaceVariant       = fromHsb(h,  s * 0.15f, 0.3f),
            surfaceContainerLowest = fromHsb(h,  s * 0.02f, 1.0f,  bgFactor),
            surfaceContainerLow    = fromHsb(h,  s * 0.05f, 0.96f, bgFactor),
            surfaceContainer       = fromHsb(h,  s * 0.08f, 0.94f, bgFactor),
            surfaceContainerHigh   = fromHsb(h,  s * 0.1f,  0.92f, bgFactor),
            surfaceContainerHighest= fromHsb(h,  s * 0.12f, 0.90f, bgFactor),
            outline                = fromHsb(h,  s * 0.1f,  0.5f,  bFactor),
            outlineVariant         = fromHsb(h,  s * 0.1f,  0.8f,  bFactor)
        ) else darkColorScheme(
            primary                = fromHsb(h,  s * 0.7f,  0.85f, bFactor),
            onPrimary              = fromHsb(h,  s * 1.0f,  0.2f),
            primaryContainer       = fromHsb(h,  s * 0.8f,  0.3f,  bFactor),
            onPrimaryContainer     = fromHsb(h,  s * 0.4f,  0.9f),
            secondary              = fromHsb(h,  s * 0.4f,  0.8f,  bFactor),
            onSecondary            = fromHsb(h,  s * 0.6f,  0.2f),
            secondaryContainer     = fromHsb(h,  s * 0.4f,  0.3f,  bFactor),
            onSecondaryContainer   = fromHsb(h,  s * 0.2f,  0.9f),
            tertiary               = fromHsb(tH, s * 0.5f,  0.8f,  bFactor),
            onTertiary             = fromHsb(tH, s * 0.7f,  0.2f),
            tertiaryContainer      = fromHsb(tH, s * 0.5f,  0.3f,  bFactor),
            onTertiaryContainer    = fromHsb(tH, s * 0.2f,  0.9f),
            background             = fromHsb(h,  s * 0.15f, 0.10f, bFactor),
            onBackground           = fromHsb(h,  s * 0.1f,  0.9f),
            surface                = fromHsb(h,  s * 0.15f, 0.10f, bFactor),
            onSurface              = fromHsb(h,  s * 0.1f,  0.9f),
            surfaceVariant         = fromHsb(h,  s * 0.20f, 0.25f, bFactor),
            onSurfaceVariant       = fromHsb(h,  s * 0.15f, 0.8f),
            surfaceContainerLowest = fromHsb(h,  s * 0.15f, 0.05f, bFactor),
            surfaceContainerLow    = fromHsb(h,  s * 0.15f, 0.12f, bFactor),
            surfaceContainer       = fromHsb(h,  s * 0.15f, 0.15f, bFactor),
            surfaceContainerHigh   = fromHsb(h,  s * 0.15f, 0.18f, bFactor),
            surfaceContainerHighest= fromHsb(h,  s * 0.15f, 0.22f, bFactor),
            outline                = fromHsb(h,  s * 0.15f, 0.6f,  bFactor),
            outlineVariant         = fromHsb(h,  s * 0.15f, 0.3f,  bFactor)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Data models
// ─────────────────────────────────────────────────────────────────────────────

enum class Theme { Light, Dark, System }

/**
 * I protocolli che il server può offrire in parallelo.
 * WFAS è sempre attivo (è il protocollo nativo).
 * RTP e HTTP sono opzionali e configurabili nelle impostazioni.
 */
enum class StreamingProtocol { WFAS, RTP, HTTP }

/**
 * Capacità dichiarate dal server nel beacon di discovery.
 * Il client usa questa struttura per scegliere il protocollo migliore.
 */
data class ServerCapabilities(
    val protocols: Set<StreamingProtocol>,
    val httpPort: Int?,
    val safariMode: Boolean = false,
    val securityMode: String? = null,
    val encrypted: Boolean = false,
    val serverSendsMic: Boolean = false,
    val serverWantsMic: Boolean = false
) {
    val acceptsClientMic: Boolean get() = serverWantsMic
}

/**
 * Informazioni di un server scoperto via discovery.
 * [capabilities] è null per server legacy che supportano solo WFAS.
 */
data class ServerInfo(
    val ip: String,
    val isMulticast: Boolean,
    val port: Int,
    val capabilities: ServerCapabilities? = null,
    val lastSeen: Long = System.currentTimeMillis(),
    val serverAudioSettings: AudioSettings_V1? = null,
    val viaUsb: Boolean = false
)

data class AudioSettings_V1(
    val sampleRate: Float,
    val bitDepth: Int,
    val channels: Int,
    val bufferSize: Int,
    val latencyMs: Int = 20,
    val maxPayloadBytes: Int = 1390,
    val adaptiveLatency: Boolean = true
) {
    fun toAudioFormat(): AudioFormat = AudioFormat(sampleRate, bitDepth, channels, true, false)
}

enum class PeerRole { SENDER, RECEIVER }

data class ProtocolMismatch(
    val localVersion: Int,
    val remoteVersion: Int,
    val remoteRole: PeerRole
) {
    val localIsOutdated: Boolean get() = remoteVersion > localVersion
}

object WfasStats {
    enum class Cat { AUDIO, SILENCE, PING, BYE, HELLO, PROBE, OTHER }

    @Volatile var active = false
        private set
    @Volatile var sending = true
        private set
    @Volatile var peer: String = "-"
    @Volatile private var startNanos = 0L

    private val nCat = Cat.entries.size
    private val pkts = java.util.concurrent.atomic.AtomicLongArray(nCat)
    private val byts = java.util.concurrent.atomic.AtomicLongArray(nCat)

    fun begin(sending: Boolean, peer: String) {
        this.sending = sending
        this.peer = peer
        for (i in 0 until nCat) { pkts.set(i, 0); byts.set(i, 0) }
        startNanos = System.nanoTime()
        active = true
    }

    fun stop() { active = false }

    fun add(cat: Cat, bytes: Int) {
        if (!active) return
        pkts.incrementAndGet(cat.ordinal)
        if (bytes > 0) byts.addAndGet(cat.ordinal, bytes.toLong())
    }

    fun elapsedSeconds(): Double {
        val s = startNanos
        return if (s == 0L) 0.0 else (System.nanoTime() - s) / 1e9
    }

    fun pkts(cat: Cat): Long = pkts.get(cat.ordinal)
    fun bytes(cat: Cat): Long = byts.get(cat.ordinal)
    fun totalPkts(): Long { var s = 0L; for (i in 0 until nCat) s += pkts.get(i); return s }
    fun totalBytes(): Long { var s = 0L; for (i in 0 until nCat) s += byts.get(i); return s }
}

object MicStats {
    enum class Dir { OFF, SENDING, RECEIVING }

    @Volatile var dir: Dir = Dir.OFF
        private set
    @Volatile var detail: String = "-"
        private set

    private val audioPkts   = java.util.concurrent.atomic.AtomicLong(0)
    private val audioBytes  = java.util.concurrent.atomic.AtomicLong(0)
    private val silentPkts  = java.util.concurrent.atomic.AtomicLong(0)
    private val silentBytes = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile private var lastNanos = 0L

    fun begin(dir: Dir, detail: String) {
        audioPkts.set(0); audioBytes.set(0); silentPkts.set(0); silentBytes.set(0)
        this.detail = detail
        this.dir = dir
        lastNanos = System.nanoTime()
    }

    fun off() { dir = Dir.OFF }

    fun addAudio(bytes: Int) {
        if (dir == Dir.OFF) return
        audioPkts.incrementAndGet()
        if (bytes > 0) audioBytes.addAndGet(bytes.toLong())
    }

    fun addSilence(bytes: Int) {
        if (dir == Dir.OFF) return
        silentPkts.incrementAndGet()
        if (bytes > 0) silentBytes.addAndGet(bytes.toLong())
    }

    fun audioPkts(): Long  = audioPkts.get()
    fun audioBytes(): Long = audioBytes.get()
    fun silentPkts(): Long  = silentPkts.get()
    fun silentBytes(): Long = silentBytes.get()
    fun totalPkts(): Long  = audioPkts.get() + silentPkts.get()
    fun totalBytes(): Long = audioBytes.get() + silentBytes.get()
    fun secondsSinceStart(): Double {
        val s = lastNanos
        return if (s == 0L) 0.0 else (System.nanoTime() - s) / 1e9
    }
}

sealed class VirtualDriverStatus {
    object Ok : VirtualDriverStatus()
    data class Missing(val driverName: String, val downloadUrl: String) : VirtualDriverStatus()
    data class LinuxActionRequired(val message: String, val commands: String) : VirtualDriverStatus()
}

object AutostartManager {
    fun getExecutablePath(): String {
        val os = System.getProperty("os.name").lowercase()

        if (os.contains("linux")) {
            val debPath = File("/opt/wifi-audio-streaming/bin/wifi-audio-streaming")
            if (debPath.exists() && debPath.canExecute()) {
                return debPath.absolutePath
            }
        }

        val jpackagePath = System.getProperty("jpackage.app-path")
        if (!jpackagePath.isNullOrEmpty()) return jpackagePath

        val processPath = ProcessHandle.current().info().command().orElse("")
        if (processPath.isNotEmpty() && !processPath.contains("java.exe") && !processPath.contains("javaw.exe") && !processPath.endsWith("java")) {
            return processPath
        }

        return try {
            val uri = AutostartManager::class.java.protectionDomain.codeSource.location.toURI()
            val path = File(uri).absolutePath
            val isWin = os.contains("win")

            if (path.endsWith(".jar")) {
                val javaCmd = if (isWin) "javaw" else "java"
                "$javaCmd -jar \"$path\""
            } else {
                path
            }
        } catch (e: Exception) {
            ""
        }
    }

    fun toggleAutostart(enable: Boolean): String? {
        val os = System.getProperty("os.name").lowercase()
        val exePath = getExecutablePath()
        if (exePath.isEmpty()) return "Error: Executable path not found."

        val appName = if (BuildIdentity.preview) "AudioBridge-Test" else "AudioBridge"
        // Older builds registered the Run value as "WiFiAudioStreamer". Disabling
        // autostart deletes that name too, otherwise a machine set up before the
        // rename would keep launching on boot from a value the new code never
        // touches. reg delete returns non-zero when the value is absent, which is
        // the normal case, so its failure is not reported.
        val legacyAppName = "WiFiAudioStreamer"

        return try {
            if (os.contains("win")) {
                val regKey = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
                if (enable) {
                    val process = ProcessBuilder(
                        "reg", "add", regKey, "/v", appName, "/t", "REG_SZ", "/d", "\"$exePath\"", "/f"
                    ).redirectErrorStream(true).start()
                    val output = process.inputStream.bufferedReader().readText()
                    if (!BuildIdentity.preview) runCatching {
                        ProcessBuilder("reg", "delete", regKey, "/v", legacyAppName, "/f")
                            .redirectErrorStream(true).start().waitFor()
                    }
                    if (process.waitFor() != 0) "Registry Error: $output" else null
                } else {
                    val process = ProcessBuilder("reg", "delete", regKey, "/v", appName, "/f")
                        .redirectErrorStream(true).start()
                    val output = process.inputStream.bufferedReader().readText()
                    process.waitFor()
                    if (!BuildIdentity.preview) runCatching {
                        ProcessBuilder("reg", "delete", regKey, "/v", legacyAppName, "/f")
                            .redirectErrorStream(true).start().waitFor()
                    }
                    if (output.contains("ERROR", ignoreCase = true) &&
                        !output.contains("unable to find", ignoreCase = true)
                    ) "Registry Error: $output" else null
                }
            } else if (os.contains("mac")) {
                val plist = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n<plist version=\"1.0\">\n<dict>\n<key>Label</key>\n<string>${if (BuildIdentity.preview) "io.github.mu23xr.audiobridge.test" else "com.wifiaudiostreaming"}</string>\n<key>ProgramArguments</key>\n<array><string>$exePath</string></array>\n<key>RunAtLoad</key>\n<true/>\n</dict>\n</plist>"
                val dir = File(System.getProperty("user.home"), "Library/LaunchAgents")
                val file = File(dir, if (BuildIdentity.preview) "io.github.mu23xr.audiobridge.test.plist" else "com.wifiaudiostreaming.plist")
                if (enable) {
                    dir.mkdirs()
                    file.writeText(plist)
                } else {
                    if (file.exists()) file.delete()
                }
                null
            } else {
                val desktopContent = "[Desktop Entry]\nType=Application\nExec=\"$exePath\"\nHidden=false\nNoDisplay=false\nTerminal=false\nX-GNOME-Autostart-enabled=true\nName=AudioBridge\nComment=Start AudioBridge on login"
                val dir = File(System.getProperty("user.home"), ".config/autostart")
                val file = File(dir, if (BuildIdentity.preview) "audiobridge-test.desktop" else "wifiaudiostreaming.desktop")
                if (enable) {
                    dir.mkdirs()
                    file.writeText(desktopContent)
                    "Autostart ENABLED (Linux). Path: $exePath"
                } else {
                    if (file.exists()) file.delete()
                    "Autostart DISABLED (Linux)."
                }
            }
        } catch (e: Exception) {
            "Exception: ${e.message}"
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// NetworkHandler
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Interruttore globale del denoiser: il player viene creato a ogni connessione,
 * ma i controlli devono agire sul flusso gia' in riproduzione. Sta a livello di
 * file perche' lo leggono sia JitterAudioPlayer (dentro NetworkHandler_v1) sia
 * i composable dell'interfaccia.
 */
object NoiseReductionControl {
    @Volatile var enabled: Boolean = false
    @Volatile var strength: Int = 50
    fun apply(on: Boolean, strengthPercent: Int) {
        enabled = on
        strength = strengthPercent.coerceIn(0, 100)
    }
}

/**
 * Quanto si aspetta un indirizzo locale prima di rinunciare all'avvio
 * automatico del server. Oltre questo tempo la rete non sta arrivando: meglio
 * dirlo che restare in attesa senza fine con la finestra che sembra bloccata.
 */
private const val AUTO_START_NETWORK_TIMEOUT_MS = 60_000L

object NetworkHandler_v1 {

    // ── Constants ──────────────────────────────────────────────────────────────
    private const val DISCOVERY_PORT       = 9091
    private const val CLIENT_HELLO_MESSAGE = "HELLO_FROM_CLIENT"
    const val MULTICAST_GROUP_IP           = "239.255.0.1"
    private const val DISCOVERY_MESSAGE    = "WIFI_AUDIO_STREAMER_DISCOVERY"
    private const val DISCOVERY_VERSION    = 2

    const val WFAS_PROTOCOL_VERSION = 2
    // How long an issued auth challenge stays answerable. Long enough for a
    // round trip on a bad link, short enough that an unanswered one does not
    // linger as something to answer later.
    private const val CHALLENGE_TTL_MS = 15_000L
    // Receivers that answer PING with PONG are evicted on silence, not on a
    // single lost datagram. Matches the Android server's heartbeat window.
    private const val PONG_HEARTBEAT_TIMEOUT_MS = 8_000L
    private const val INCOMPATIBLE_PREFIX = "WFAS_INCOMPATIBLE"
    private const val HELLO_ACK_PREFIX    = "HELLO_ACK"
    private const val PENDING_MESSAGE       = "WFAS_PENDING"
    private const val AUTH_REQUIRED_PREFIX  = "WFAS_AUTH_REQUIRED"
    private const val BUSY_MESSAGE = "WFAS_BUSY"
    private const val UNAUTHORIZED_MESSAGE  = "WFAS_UNAUTHORIZED"

    @Volatile var securityMode: String = "OFF"
    @Volatile var authKey: String = ""
    @Volatile var encryptionEnabled: Boolean = false
    @Volatile var securityConfigured: Boolean = false
    fun configureSecurity(mode: String, key: String, encrypt: Boolean = false) {
        securityMode = mode; authKey = key; encryptionEnabled = encrypt
        securityConfigured = true
    }

    class McastSession {
        @Volatile var dir: WfasCrypto.Dir? = null
        @Volatile var beacon: ByteArray? = null
        @Volatile var epoch: Long = 0L
        @Volatile var key: String = ""
        @Volatile var beaconUrgent: Boolean = false
    }

    data class McastSnapshot(val epoch: Long, val key: String, val encrypted: Boolean)

    val mcastSession = MutableStateFlow<McastSnapshot?>(null)
    @Volatile private var mcastRekey: ((String) -> Unit)? = null

    fun rekeyMulticast(newKey: String): Boolean {
        val fn = mcastRekey ?: return false
        fn(newKey)
        return true
    }

    val unicastPeerConnected = MutableStateFlow(false)
    val sessionEncryptedLive = MutableStateFlow(false)

    val pendingEpochMismatch = MutableStateFlow(false)
    fun clearEpochMismatch() { pendingEpochMismatch.value = false }

    val pendingInviteRejected = MutableStateFlow(false)
    fun clearInviteRejected() { pendingInviteRejected.value = false }

    @Volatile var clientKeyFromInvite: Boolean = false
    @Volatile var expectedMcastEpoch: Long? = null
    // Pre-shared key used only when acting as a CLIENT (e.g. CLI --auth-key). The
    // GUI client leaves this empty: it gets the key from the on-connect dialog.
    @Volatile var clientPresharedKey: String = ""
    /**
     * Se false, un client che si trova davanti alla richiesta della chiave
     * rinuncia invece di aprire una finestra. Serve alla connessione automatica:
     * una procedura che deve girare da sola non puo' fermarsi su una modale che
     * magari nessuno vedra' mai.
     */
    @Volatile var clientKeyPromptAllowed: Boolean = true
    // Mic-stream session keys (separate long-lived jobs): server decrypts incoming
    // mic with micRecvDir; client encrypts outgoing mic with micSendDir.
    @Volatile var micRecvDir: WfasCrypto.Dir? = null
    @Volatile var micRecvWin: WfasCrypto.ReplayWindow = WfasCrypto.ReplayWindow()
    @Volatile var micSendDir: WfasCrypto.Dir? = null
    var onAuthRequest: (suspend (peer: String) -> Boolean)? = null
    var onKeyRequest: (suspend (wrong: Boolean) -> String?)? = null

    private fun clientHelloMessage(): String = "$CLIENT_HELLO_MESSAGE;v=$WFAS_PROTOCOL_VERSION"
    private fun helloAckMessage():    String = "$HELLO_ACK_PREFIX;v=$WFAS_PROTOCOL_VERSION"
    private fun incompatibleMessage(): String = "$INCOMPATIBLE_PREFIX;v=$WFAS_PROTOCOL_VERSION"

    private fun parseProtocolVersion(message: String): Int =
        message.split(";").firstOrNull { it.startsWith("v=") }
            ?.removePrefix("v=")?.trim()?.toIntOrNull() ?: 0

    val protocolMismatch = MutableStateFlow<ProtocolMismatch?>(null)

    private fun signalProtocolMismatch(remoteVersion: Int, remoteRole: PeerRole) {
        if (protocolMismatch.value == null) {
            protocolMismatch.value =
                ProtocolMismatch(WFAS_PROTOCOL_VERSION, remoteVersion, remoteRole)
        }
    }

    private const val SILENT_PEER_TIMEOUT_MS = 6000L

    fun clearProtocolMismatch() { protocolMismatch.value = null }

    val unresponsiveServer = MutableStateFlow<String?>(null)

    fun clearUnresponsiveServer() { unresponsiveServer.value = null }

    // ── Network helpers ────────────────────────────────────────────────────────
    fun getLocalIpAddress(): String {
        UsbLink.state.localAddress?.let { if (UsbLink.isReady()) return it }
        return NetAddr.bestLocalAddress()
    }

    fun getLocalIpAddresses(): List<String> {
        val usb = if (UsbLink.isReady()) UsbLink.state.localAddress else null
        val rest = NetAddr.localAddresses().map { it.host }
        return (listOfNotNull(usb) + rest).distinct()
    }

    fun snapcastHostName(): String {
        val fromEnv = System.getenv("COMPUTERNAME") ?: System.getenv("HOSTNAME")
        val resolved = fromEnv?.trim()?.takeIf { it.isNotEmpty() }
            ?: runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()
            ?: "wfas"
        return resolved.substringBefore('.').ifBlank { "wfas" }
    }

    /**
     * Identita' stabile di questo computer come client Snapcast.
     *
     * Il server ricorda volume, nome e latenza per ID: se cambiasse a ogni
     * avvio, ogni riconnessione comparirebbe come un client nuovo e le
     * regolazioni dell'utente andrebbero perse. Si usa il MAC della prima
     * interfaccia vera, con ripiego stabile sul nome host.
     */
    fun localClientId(): String = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { !it.isLoopback && it.isUp }
            .mapNotNull { it.hardwareAddress }
            .firstOrNull { it.size == 6 }
            ?.joinToString(":") { b -> "%02x".format(b) }
            ?: ("wfas-" + java.net.InetAddress.getLocalHost().hostName.lowercase())
    }.getOrDefault("wfas-desktop")

    fun getActiveNetworkInterface(preferredName: String = "Auto"): NetworkInterface? = try {
        val usb = if (preferredName == "Auto") UsbLink.activeInterface() else null
        val allInterfaces = NetworkInterface.getNetworkInterfaces().toList()

        if (usb != null) usb
        else if (preferredName != "Auto") {
            allInterfaces.firstOrNull { it.displayName == preferredName || it.name == preferredName }
        } else {
            val localIp = getLocalIpAddress()
            val matchByIp = allInterfaces.firstOrNull { iface ->
                iface.isUp && !iface.isLoopback &&
                        iface.inetAddresses.toList().any { addr ->
                            (addr.hostAddress ?: "").substringBefore('%') == localIp.substringBefore('%')
                        }
            }
            matchByIp ?: allInterfaces.firstOrNull { iface ->
                iface.isUp && !iface.isLoopback &&
                        !iface.displayName.contains("Virtual",  ignoreCase = true) &&
                        !iface.displayName.contains("VMware",   ignoreCase = true) &&
                        !iface.displayName.contains("Hyper-V",  ignoreCase = true) &&
                        !iface.displayName.contains("WSL",      ignoreCase = true) &&
                        iface.inetAddresses.toList().any { !it.isLoopbackAddress }
            }
        }
    } catch (_: Exception) { null }

    suspend fun probeIsMulticast(ip: String, port: Int): Boolean {
        require(port in 1..65535) { "Source port must be between 1 and 65535" }
        // Discovery/pairing supplies an explicit mode. With only an address,
        // absence of a probe reply is never evidence for multicast.
        return withContext(Dispatchers.IO) {
            java.net.InetAddress.getByName(NetAddr.normalize(ip)).isMulticastAddress
        }
    }

    suspend fun pingServerUnicast(ip: String, port: Int): Boolean {
        require(port in 1..65535) { "Source port must be between 1 and 65535" }
        return try {
            var online = false
            withTimeoutOrNull(1000) {
                aSocket(selectorManager).udp().bind(InetSocketAddress(NetAddr.wildcardFor(ip), 0)).use { sock ->
                    val host = withContext(Dispatchers.IO) { InetAddress.getByName(NetAddr.normalize(ip)).hostAddress }
                    val remoteAddress = InetSocketAddress(host, port)
                    sock.send(Datagram(buildPacket { writeText("MODE_PROBE") }, remoteAddress))
                    while (!online) {
                        val ack = sock.receive()
                        if (ack.address == remoteAddress) {
                            val reply = ack.packet.readText().trim()
                            online = reply == "UNICAST" || reply == BUSY_MESSAGE
                        }
                    }
                }
                online
            } ?: false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            false
        }
    }

    @Volatile var activePeerIp: String? = null

    fun sessionUsesUsb(): Boolean = UsbLink.isUsbPeer(activePeerIp)

    private fun peerHostOf(address: io.ktor.network.sockets.SocketAddress): String? =
        NetAddr.literalHost(address)
            ?: NetAddr.literalHostOfText(address.toString())
            ?: WfasPolicy.hostOf(address.toString())


    @Volatile var currentServerVolume: Float = 1.0f
        private set
    val captureGain = MutableStateFlow(1f)

    @Volatile var currentClientVolume: Float = 1.0f
        private set

    fun setServerVolume(volume: Float) {
        require(volume.isFinite()) { "Sender gain must be finite" }
        currentServerVolume = volume.coerceIn(0f, 2f)
        captureGain.value = currentServerVolume
    }

    fun setClientVolume(volume: Float) {
        require(volume.isFinite()) { "Receiver gain must be finite" }
        currentClientVolume = volume.coerceIn(0f, 2f)
    }

    fun setPlaybackOrCaptureVolume(volume: Float) {
        require(volume.isFinite()) { "Audio gain must be finite" }
        val v = volume.coerceIn(0f, 2f)
        setServerVolume(v)
        setClientVolume(v)
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val selectorManager = SelectorManager(Dispatchers.IO)

    val isMicMuted = MutableStateFlow(false)
    val playbackTargetMs = MutableStateFlow<Int?>(null)

    private var streamingJob:    Job? = null
    private var listeningJob:    Job? = null
    private var broadcastingJob: Job? = null
    private var micReceiverJob:  Job? = null
    private var localMicMixJob:  Job? = null
    private var httpServerJob:   Job? = null
    private var rtpJob:          Job? = null

    private val lifecycleMutex = Mutex()
    private val lifecycleGeneration = java.util.concurrent.atomic.AtomicLong(0)

    // ── AudioEngine (motore nativo JNI) ───────────────────────────────────────
    @Volatile private var serverEngine: AudioEngine? = null

    private val softwareMicMixer = SoftwareMicMixer()

    // ── FFmpeg grabber (motore legacy) ────────────────────────────────────────
    @Volatile private var serverGrabber: org.bytedeco.javacv.FFmpegFrameGrabber? = null

    // ── Protocollo audio: header 10 byte preposto a ogni pacchetto PCM ────────
    // Layout (Big-Endian): [magic:2=0x5746][version:1=0x02][flags:1][seqNum:2][samplePos:4]
    // flags bit0=silence. I pacchetti control (PING/BYE/HELLO) iniziano con ASCII
    // e non matchano mai il magic 0x57 0x46, quindi la distinzione è inequivoca.
    private val AUDIO_MAGIC_0: Byte = 0x57   // 'W'
    private val AUDIO_MAGIC_1: Byte = 0x46   // 'F'
    private val AUDIO_VERSION: Byte = WFAS_PROTOCOL_VERSION.toByte()
    private val AUDIO_HEADER_SIZE = 10

    private fun writeMicHeader(dst: ByteArray, seq: Int, silence: Boolean) {
        dst[0] = AUDIO_MAGIC_0
        dst[1] = AUDIO_MAGIC_1
        dst[2] = AUDIO_VERSION
        dst[3] = if (silence) 0x01 else 0x00
        dst[4] = ((seq shr 8) and 0xFF).toByte()
        dst[5] = (seq and 0xFF).toByte()
        dst[6] = 0; dst[7] = 0; dst[8] = 0; dst[9] = 0
    }


    // ── Sidecar PCM queues ─────────────────────────────────────────────────────
    // Riempite dal loop del grabber, svuotate dagli encoder. offer() non-bloccante:
    // se la coda è piena il frame viene scartato per non accumulare latenza.
    @Volatile private var aacPcmQueue:  java.util.concurrent.ArrayBlockingQueue<ByteArray>? = null
    @Volatile private var opusPcmQueue: java.util.concurrent.ArrayBlockingQueue<ByteArray>? = null
    @Volatile private var rtpPcmQueue:  java.util.concurrent.ArrayBlockingQueue<ByteArray>? = null

    /** Byte di audio che la coda RTP non ha accettato: il timestamp li salta. */
    private val rtpSkippedBytes = java.util.concurrent.atomic.AtomicLong(0L)

    /** Mezzo secondo di ritardo e il ritmo si rifa' da adesso, invece di rincorrere. */
    private val rtpPaceResyncNs = 500_000_000L
    @Volatile private var dlnaManager: DlnaSessionManager? = null
    @Volatile private var snapcastManager: SnapcastSessionManager? = null

    val dlnaTargets: kotlinx.coroutines.flow.StateFlow<List<DlnaTargetState>>
        get() = DlnaStatus.targets

    fun dlnaClientCount(): Int = dlnaManager?.activeClientCount() ?: 0

    fun dlnaActive(): Boolean = dlnaManager != null

    val snapcastSession: kotlinx.coroutines.flow.StateFlow<SnapcastSessionState>
        get() = SnapcastStatus.session

    fun snapcastClientCount(): Int = snapcastManager?.activeClientCount() ?: 0

    fun snapcastActive(): Boolean = snapcastManager != null

    // ── Linux audio routing ────────────────────────────────────────────────────
    private var originalLinuxSink:   String? = null
    private var originalLinuxSource: String? = null

    // ── macOS volume mute-on-server ───────────────────────────────────────────
    @Volatile private var macOriginalVolume: Float = -1f

    private fun getPactlDefault(type: String): String? {
        try {
            val info = ProcessBuilder("pactl", "info").start()
            val out  = info.inputStream.bufferedReader().readText()
            if (info.waitFor() == 0) {
                val prefix = if (type == "sink") "Default Sink: " else "Default Source: "
                val result = out.lines().find { it.trimStart().startsWith(prefix) }
                    ?.substringAfter(prefix)?.trim()
                if (!result.isNullOrEmpty()) return result
            }
        } catch (_: Exception) {}
        return try {
            val p = ProcessBuilder("pactl", "get-default-$type").start()
            val o = p.inputStream.bufferedReader().readText().trim()
            if (p.waitFor() == 0 && o.isNotEmpty()) o else null
        } catch (_: Exception) { null }
    }

    private fun routeLinuxAudioToVirtualCable() {
        if (!System.getProperty("os.name").lowercase().contains("linux")) return
        getPactlDefault("sink").takeIf { it != null && !it.contains("VirtualCable", true) }
            ?.also { originalLinuxSink = it }
        getPactlDefault("source").takeIf { it != null && !it.contains("VirtualCable", true) }
            ?.also { originalLinuxSource = it }
        AppDebug.log("---Linux: saved sink='$originalLinuxSink' source='$originalLinuxSource' ---")
        runCatching { ProcessBuilder("pactl", "set-default-sink",   "VirtualCable").start().waitFor() }
        runCatching { ProcessBuilder("pactl", "set-default-source", "VirtualCable.monitor").start().waitFor() }
        AppDebug.log("---Linux: audio routed to VirtualCable ---")
    }

    fun restoreLinuxAudioRouting() {
        if (!System.getProperty("os.name").lowercase().contains("linux")) return
        val sink   = originalLinuxSink   ?: run { AppDebug.log("---Linux: nothing to restore ---"); return }
        val source = originalLinuxSource
        runCatching { ProcessBuilder("pactl", "set-default-sink", sink).start().waitFor() }
        source?.let { runCatching { ProcessBuilder("pactl", "set-default-source", it).start().waitFor() } }
        AppDebug.log("---Linux: audio restored to sink='$sink' source='$source' ---")
        originalLinuxSink   = null
        originalLinuxSource = null
    }

    fun setupLinuxVirtualCable(useNativeEngine: Boolean) {
        if (!System.getProperty("os.name").lowercase().contains("linux")) return
        if (useNativeEngine) return
        try {
            if (ProcessBuilder("which", "pactl").start().waitFor() != 0) {
                AppDebug.log("'pactl' not found - cannot create virtual cable automatically.")
                return
            }
            getPactlDefault("sink").takeIf { it != null && !it.contains("VirtualCable", true) }
                ?.also { originalLinuxSink = it }
            getPactlDefault("source").takeIf { it != null && !it.contains("VirtualCable", true) }
                ?.also { originalLinuxSource = it }
            AppDebug.log("---Linux: real sink saved at startup: '$originalLinuxSink' ---")

            val check = ProcessBuilder("sh", "-c", "pactl list short sinks | grep VirtualCable").start()
            if (check.waitFor() == 0) { AppDebug.log("---Linux: VirtualCable already active ---"); return }

            AppDebug.log("---Linux: creating VirtualCable... ---")
            val create = ProcessBuilder(
                "pactl", "load-module", "module-null-sink",
                "sink_name=VirtualCable",
                "sink_properties=device.description=VirtualCable"
            ).start()
            if (create.waitFor() == 0) AppDebug.log("---Linux: VirtualCable created ---")
            else AppDebug.log("---Linux: failed to create VirtualCable ---")
        } catch (e: Exception) {
            AppDebug.log("Linux audio setup error: ${e.message}")
        }
    }

    // ── Device enumeration ────────────────────────────────────────────────────
    fun allMixers(): List<Mixer.Info> =
        runCatching { AudioSystem.getMixerInfo().toList() }.getOrDefault(emptyList())

    fun isPortMixer(info: Mixer.Info): Boolean = info.name.startsWith("Port", ignoreCase = true)

    fun supportsLine(info: Mixer.Info, lineClass: Class<*>): Boolean = runCatching {
        AudioSystem.getMixer(info).isLineSupported(Line.Info(lineClass))
    }.getOrDefault(false)

    private fun mixersFor(lineClass: Class<*>): List<Mixer.Info> {
        val candidates = allMixers().filterNot { isPortMixer(it) }
        if (candidates.isEmpty()) return emptyList()

        val outputs = candidates.filter { supportsLine(it, SourceDataLine::class.java) }
        val inputs  = candidates.filter { supportsLine(it, TargetDataLine::class.java) }
        val probed  = if (lineClass == SourceDataLine::class.java) outputs else inputs
        if (probed.isNotEmpty()) return probed

        val probeIsBlind = outputs.isEmpty() && inputs.isEmpty()
        return if (probeIsBlind) candidates else emptyList()
    }

    fun probeIsBlind(): Boolean {
        val candidates = allMixers().filterNot { isPortMixer(it) }
        return candidates.isNotEmpty() &&
                candidates.none {
                    supportsLine(it, SourceDataLine::class.java) || supportsLine(it, TargetDataLine::class.java)
                }
    }

    fun findAvailableOutputMixers(): List<Mixer.Info> = mixersFor(SourceDataLine::class.java)

    fun findAvailableInputMixers(): List<Mixer.Info> = mixersFor(TargetDataLine::class.java)

    fun checkVirtualDriverStatus(useNativeEngine: Boolean = true): VirtualDriverStatus {
        val os = System.getProperty("os.name").lowercase()

        if (useNativeEngine) {
            val loadErr = AudioEngine.getLoadError()
            if (loadErr != null) {
                return when {
                    os.contains("linux") -> VirtualDriverStatus.LinuxActionRequired(
                        "Cannot load native audio engine.\n$loadErr",
                        "Rebuild with: ./gradlew copyNativeLib"
                    )
                    else -> VirtualDriverStatus.Missing(
                        "Native audio engine",
                        "Rebuild with: ./gradlew copyNativeLib — $loadErr"
                    )
                }
            }
            if (os.contains("mac")) {
                val macVersion = System.getProperty("os.version") ?: "0.0"
                val major = macVersion.split(".").firstOrNull()?.toIntOrNull() ?: 0
                if (major < 13) {
                    return VirtualDriverStatus.LinuxActionRequired(
                        "ScreenCaptureKit richiede macOS 12.3 o superiore (versione corrente: $macVersion). " +
                                "Aggiorna macOS oppure installa BlackHole 2ch come fallback.",
                        ""
                    )
                }
            }
            return VirtualDriverStatus.Ok
        }

        return when {
            os.contains("win") -> {
                val ok = AudioSystem.getMixerInfo().any { it.name.contains("CABLE Output", ignoreCase = true) }
                if (ok) VirtualDriverStatus.Ok
                else VirtualDriverStatus.Missing("VB-Audio Virtual Cable", "https://vb-audio.com/Cable/")
            }
            os.contains("mac") -> {
                val ok = AudioSystem.getMixerInfo().any { it.name.contains("BlackHole", ignoreCase = true) }
                if (ok) VirtualDriverStatus.Ok
                else VirtualDriverStatus.Missing("BlackHole 2ch", "https://existential.audio/blackhole/")
            }
            else -> try {
                if (ProcessBuilder("which", "pactl").start().waitFor() != 0)
                    return VirtualDriverStatus.LinuxActionRequired(
                        "PulseAudio utilities are missing.",
                        "sudo apt update && sudo apt install pulseaudio-utils"
                    )
                val s = ProcessBuilder("sh", "-c", "pactl list short sinks | grep VirtualCable").start()
                if (s.waitFor() != 0)
                    ProcessBuilder("pactl", "load-module", "module-null-sink",
                        "sink_name=VirtualCable",
                        "sink_properties=device.description=VirtualCable").start().waitFor()
                VirtualDriverStatus.Ok
            } catch (_: Exception) { VirtualDriverStatus.Ok }
        }
    }

    val localIpFlow = MutableStateFlow(getLocalIpAddress())
    val networkRevision = MutableStateFlow(0)
    private var networkWatchJob: Job? = null

    fun startNetworkWatch() {
        if (networkWatchJob?.isActive == true) return
        networkWatchJob = scope.launch {
            while (isActive) {
                delay(2500)
                val ip = runCatching { getLocalIpAddress() }.getOrDefault(localIpFlow.value)
                if (ip != localIpFlow.value) {
                    AppDebug.log("[NET] indirizzo locale cambiato: ${localIpFlow.value} -> $ip")
                    localIpFlow.value = ip
                    networkRevision.value++
                    restartAnnounceOnNetworkChange()
                }
            }
        }
    }

    fun stopNetworkWatch() {
        networkWatchJob?.cancel()
        networkWatchJob = null
    }

    suspend fun restartDeviceDiscovery(onDeviceFound: (hostname: String, serverInfo: ServerInfo) -> Unit) {
        val job = listeningJob
        listeningJob = null
        if (job != null) runCatching { job.cancelAndJoin() }
        beginDeviceDiscovery(onDeviceFound)
    }

    // ── Discovery ──────────────────────────────────────────────────────────────
    private val discoveryBestAddress = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()

    fun beginDeviceDiscovery(onDeviceFound: (hostname: String, serverInfo: ServerInfo) -> Unit) {
        if (listeningJob?.isActive == true) return
        discoveryBestAddress.clear()
        listeningJob = scope.launch {
            var localIps = NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .flatMap { a -> (a.hostAddress ?: "").let { listOf(it, it.substringBefore('%')) } }.toSet()
            var lastIpRefresh = System.currentTimeMillis()
            val groupAddress = InetAddress.getByName(MULTICAST_GROUP_IP)
            var socket: MulticastSocket? = null
            try {
                socket = MulticastSocket(DISCOVERY_PORT).apply {
                    reuseAddress = true
                    getActiveNetworkInterface()?.let { networkInterface = it }
                    MulticastNet.joinAllGroups(this)
                    soTimeout = 5000
                }
                val buffer = ByteArray(1024)
                val packet = DatagramPacket(buffer, buffer.size)
                while (isActive) {
                    try {
                        if (System.currentTimeMillis() - lastIpRefresh > 5000) {
                            lastIpRefresh = System.currentTimeMillis()
                            localIps = runCatching {
                                NetworkInterface.getNetworkInterfaces().toList()
                                    .flatMap { it.inetAddresses.toList() }
                                    .flatMap { a -> (a.hostAddress ?: "").let { listOf(it, it.substringBefore('%')) } }.toSet()
                            }.getOrDefault(localIps)
                        }
                        packet.length = buffer.size
                        socket.receive(packet)
                        val remoteIp = packet.address.hostAddress
                        val message  = String(packet.data, 0, packet.length).trim()

                        if (remoteIp in localIps || !message.startsWith(DISCOVERY_MESSAGE)) continue

                        AppDebug.log("[DISCOVERY] Ricevuto da $remoteIp: $message")
                        val parts = message.split(";")
                        if (parts.size < 4) { AppDebug.log("[DISCOVERY] pacchetto malformato (parti=${parts.size}): $message"); continue }
                        val hostname = parts[1]

                        if (message.contains("BYE")) {
                            AppDebug.log("[DISCOVERY] BYE da $hostname ($remoteIp), rimuovo dalla lista")
                            discoveryBestAddress.remove(hostname)
                            onDeviceFound(hostname, ServerInfo("", false, 0, null, 0L))
                            continue
                        }

                        val isMulticast = parts[2].equals("MULTICAST", ignoreCase = true)
                        val port        = parts[3].toIntOrNull() ?: continue
                        AppDebug.log("[DISCOVERY] Server found: hostname=$hostname ip=$remoteIp isMulticast=$isMulticast port=$port")

                        val capabilities = if (parts.size >= 5) {
                            val protoStr = parts.firstOrNull { it.startsWith("protocols=") }
                                ?.removePrefix("protocols=") ?: "WFAS"
                            val protocols = protoStr.split(",").mapNotNull { token ->
                                runCatching { StreamingProtocol.valueOf(token.trim()) }.getOrNull()
                            }.toSet().ifEmpty { setOf(StreamingProtocol.WFAS) }
                            val httpPort = parts.firstOrNull { it.startsWith("http_port=") }
                                ?.removePrefix("http_port=")?.toIntOrNull()
                            val authMode = parts.firstOrNull { it.startsWith("auth=") }
                                ?.removePrefix("auth=")?.uppercase()
                            val encrypted = parts.firstOrNull { it.startsWith("enc=") }
                                ?.removePrefix("enc=") == "1"
                            val micTok = parts.firstOrNull { it.startsWith("mic=") }?.removePrefix("mic=")
                            ServerCapabilities(
                                protocols, httpPort, securityMode = authMode, encrypted = encrypted,
                                serverSendsMic = micTok?.contains("tx") == true,
                                serverWantsMic = micTok?.contains("rx") == true
                            )
                        } else {
                            ServerCapabilities(setOf(StreamingProtocol.WFAS), null)
                        }
                        val discoveredSr = parts.firstOrNull { it.startsWith("sr=") }?.removePrefix("sr=")?.toFloatOrNull()
                        val discoveredCh = parts.firstOrNull { it.startsWith("ch=") }?.removePrefix("ch=")?.toIntOrNull()
                        val discoveredBd = parts.firstOrNull { it.startsWith("bd=") }?.removePrefix("bd=")?.toIntOrNull()
                        val discoveredAudio = if (discoveredSr != null && discoveredCh != null && discoveredBd != null)
                            AudioSettings_V1(discoveredSr, discoveredBd, discoveredCh, 6400)
                        else null
                        AppDebug.log("[DISCOVERY] caps auth=${capabilities.securityMode} enc=${capabilities.encrypted} micTx=${capabilities.serverSendsMic} micRx=${capabilities.serverWantsMic}")

                        val nowMs = System.currentTimeMillis()
                        val known = discoveryBestAddress[hostname]
                        if (!MulticastNet.shouldAdoptPeer(known?.first, known?.second ?: 0L, remoteIp, nowMs)) {
                            AppDebug.log("[DISCOVERY] $hostname: keeping ${known?.first} over $remoteIp")
                            continue
                        }
                        discoveryBestAddress[hostname] = remoteIp to nowMs
                        val viaUsb = UsbLink.isUsbPeerDetected(remoteIp)
                        AppDebug.log("[DISCOVERY] $hostname @ $remoteIp transport=${if (viaUsb) "USB" else "WIFI"} " +
                                "usbIface=${UsbLink.detectedInterface()?.name ?: "-"} usbEnabled=${UsbLink.enabled}")
                        onDeviceFound(hostname, ServerInfo(remoteIp, isMulticast, port, capabilities, nowMs, discoveredAudio, viaUsb))
                    } catch (_: java.net.SocketTimeoutException) { continue }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) AppDebug.log("Discovery listening error: ${e.message}")
            } finally {
                runCatching { socket?.let { MulticastNet.leaveAllGroups(it) } }
                socket?.close()
            }
        }
    }

    fun endDeviceDiscovery() { listeningJob?.cancel(); discoveryBestAddress.clear() }

    fun forgetDiscoveredAddresses() { discoveryBestAddress.clear() }


    private data class AnnounceParams(
        val isMulticast: Boolean,
        val port: Int,
        val capabilities: ServerCapabilities,
        val audioSettings: AudioSettings_V1?
    )
    private var lastAnnounceParams: AnnounceParams? = null

    private fun restartAnnounceOnNetworkChange() {
        val p = lastAnnounceParams ?: return
        if (broadcastingJob?.isActive != true) return
        scope.launch {
            val job = broadcastingJob
            broadcastingJob = null
            if (job != null) runCatching { job.cancelAndJoin() }
            AppDebug.log("[NET] rete cambiata: beacon riavviato sull'interfaccia nuova")
            startAnnouncingPresence(p.isMulticast, p.port, p.capabilities, p.audioSettings)
        }
    }

    fun startAnnouncingPresence(isMulticast: Boolean, port: Int, capabilities: ServerCapabilities, audioSettings: AudioSettings_V1? = null) {
        lastAnnounceParams = AnnounceParams(isMulticast, port, capabilities, audioSettings)
        broadcastingJob?.cancel()
        broadcastingJob = scope.launch {
            val hostname     = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("Desktop-PC")
            val mode         = if (isMulticast) "MULTICAST" else "UNICAST"
            val protocolsStr = capabilities.protocols.joinToString(",") { it.name }
            val httpPortStr  = capabilities.httpPort?.let { ";http_port=$it" } ?: ""
            val audioStr     = if (audioSettings != null) ";sr=${audioSettings.sampleRate.toInt()};ch=${audioSettings.channels};bd=${audioSettings.bitDepth}" else ""
            val micCode      = "${if (capabilities.serverSendsMic) "tx" else ""}${if (capabilities.serverWantsMic) "rx" else ""}"
            val micStr       = if (micCode.isNotEmpty()) ";mic=$micCode" else ""
            val staticPrefix = "$DISCOVERY_MESSAGE;$hostname;$mode;$port;protocols=$protocolsStr$httpPortStr$audioStr"
            val groupAddress = InetAddress.getByName(MULTICAST_GROUP_IP)
            MulticastSocket().use { socket ->
                socket.timeToLive = 4
                val preferred = getActiveNetworkInterface()
                preferred?.let { socket.networkInterface = it }
                while (isActive) {
                    val encOn   = encryptionEnabled && SecurityMode.requiresKey(securityMode)
                    val announcedAuth = SecurityMode.fromStringSafe(securityMode).name
                    val secStr  = ";auth=$announcedAuth;enc=${if (encOn) 1 else 0}"
                    val message = "$staticPrefix$secStr$micStr"
                    runCatching {
                        val bytes = message.toByteArray()
                        val packet = DatagramPacket(bytes, bytes.size, groupAddress, DISCOVERY_PORT)
                        MulticastNet.sendAll(socket, bytes, DISCOVERY_PORT, preferred) { WfasPolicy.enabledOn(it) }
                    }
                    delay(3000)
                }
            }
        }
    }

    fun stopAnnouncingPresence() {
        val job = broadcastingJob
        broadcastingJob = null
        scope.launch {
            job?.cancelAndJoin()
            // Invia un ultimo pacchetto di discovery con flag BYE
            val hostname = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("Desktop-PC")
            val message = "$DISCOVERY_MESSAGE;$hostname;BYE;0"
            val groupAddress = InetAddress.getByName(MULTICAST_GROUP_IP)
            MulticastSocket().use { socket ->
                val bytes = message.toByteArray()
                val preferred = getActiveNetworkInterface()
                preferred?.let { socket.networkInterface = it }
                MulticastNet.sendAll(socket, bytes, DISCOVERY_PORT, preferred)
            }
        }
    }

    // ── Microphone receiver (server side) ─────────────────────────────────────
    private fun CoroutineScope.launchMicReceiver(
        audioSettings: AudioSettings_V1,
        isMulticast: Boolean,
        routingMode: MicRoutingMode,
        micOutputMixerInfo: Mixer.Info?,
        micPort: Int,
        micMixInputInfo: Mixer.Info? = null,
        onStatusUpdate: ((key: String, args: Array<out Any>) -> Unit)? = null
    ) = launch {
        if (routingMode == MicRoutingMode.OFF) return@launch

        fun reportMicError(msg: String) {
            AppDebug.log("[MicReceiver] $msg")
            onStatusUpdate?.invoke("status_mic_route_failed", arrayOf(msg))
        }

        val osName = System.getProperty("os.name").lowercase()
        val isLinux   = osName.contains("linux")
        val isWindows = osName.contains("win")

        var line: SourceDataLine? = null
        var nativeVirtualSinkActive = false
        var wasapiSinkEngine: AudioEngine? = null
        var mixActive = false
        var muteCollectorJob: Job? = null

        try {
            when (routingMode) {
                MicRoutingMode.VIRTUAL_MIC -> {
                    if (isLinux) {
                        val engine = AudioEngine(
                            sampleRate = audioSettings.sampleRate.toInt(),
                            channels   = audioSettings.channels,
                            bufferFrames = audioSettings.bufferSize / (audioSettings.channels * 2)
                        )
                        val created = engine.createVirtualSink(
                            audioSettings.sampleRate.toInt(),
                            audioSettings.channels
                        )
                        if (!created) {
                            reportMicError("createVirtualSink failed: ${engine.lastError}")
                            return@launch
                        }
                        activeVirtualSinkEngine = engine
                        nativeVirtualSinkActive = true
                        AppDebug.log("[MicReceiver] Virtual sink attivo: ${engine.virtualSinkName()}")
                    } else if (isWindows) {
                        val engine = AudioEngine(
                            sampleRate   = audioSettings.sampleRate.toInt(),
                            channels     = audioSettings.channels,
                            bufferFrames = audioSettings.bufferSize / (audioSettings.channels * 2)
                        )
                        val hint = micOutputMixerInfo?.name
                        val opened = engine.micSinkOpen(hint, audioSettings.sampleRate.toInt(), audioSettings.channels)
                        if (!opened) {
                            AppDebug.log("[MicReceiver] WASAPI micSinkOpen failed: ${engine.lastError}")
                            val outputs = runCatching { findAvailableOutputMixers() }.getOrDefault(emptyList())
                            val fallback = micOutputMixerInfo
                                ?: VirtualMicAutodetect.detectManualCable(outputs)?.mixerInfo
                                ?: run {
                                    reportMicError("No cable device (VB-Cable) selected or detected. Install VB-Cable and select it.")
                                    return@launch
                                }
                            val mixer = runCatching { AudioSystem.getMixer(fallback) }.getOrNull()
                                ?: run { reportMicError("Mixer '${fallback.name}' cannot be opened."); return@launch }
                            val format   = audioSettings.toAudioFormat()
                            val lineInfo = DataLine.Info(SourceDataLine::class.java, format)
                            if (!mixer.isLineSupported(lineInfo)) {
                                reportMicError("'${fallback.name}' does not support ${format.sampleRate.toInt()}Hz/${format.channels}ch.")
                                return@launch
                            }
                            line = (mixer.getLine(lineInfo) as SourceDataLine).also {
                                it.open(format, (audioSettings.sampleRate.toInt() * UsbLink.effectiveLatencyMs(audioSettings.latencyMs) / 1000) * audioSettings.channels * 2)
                                it.start()
                            }
                            AppDebug.log("[MicReceiver] Fallback SourceDataLine su '${fallback.name}'")
                        } else {
                            wasapiSinkEngine = engine
                            AppDebug.log("[MicReceiver] WASAPI sink attivo su '${engine.micSinkDeviceName()}'")
                        }
                    } else {
                        val outputs = runCatching { findAvailableOutputMixers() }.getOrDefault(emptyList())
                        val mixerInfo = micOutputMixerInfo
                            ?: VirtualMicAutodetect.detectManualCable(outputs)?.mixerInfo
                            ?: run {
                                reportMicError("No virtual device (BlackHole) selected or detected. Install BlackHole and select it.")
                                return@launch
                            }
                        AppDebug.log("[MicReceiver] Opening SourceDataLine to '${mixerInfo.name}'")
                        val mixer = runCatching { AudioSystem.getMixer(mixerInfo) }.getOrNull() ?: run {
                            reportMicError("Mixer '${mixerInfo.name}' cannot be opened.")
                            return@launch
                        }
                        val format   = audioSettings.toAudioFormat()
                        val lineInfo = DataLine.Info(SourceDataLine::class.java, format)
                        if (!mixer.isLineSupported(lineInfo)) {
                            reportMicError("'${mixerInfo.name}' does not support ${format.sampleRate.toInt()}Hz/${format.channels}ch/${format.sampleSizeInBits}bit.")
                            return@launch
                        }
                        line = (mixer.getLine(lineInfo) as SourceDataLine).also {
                            it.open(format, (audioSettings.sampleRate.toInt() * UsbLink.effectiveLatencyMs(audioSettings.latencyMs) / 1000) * audioSettings.channels * 2)
                            it.start()
                        }
                        AppDebug.log("[MicReceiver] SourceDataLine opened: buffer=${line?.bufferSize} bytes, format=$format")
                    }
                }
                MicRoutingMode.MIX_INTO_STREAM -> {
                    mixActive = true
                    softwareMicMixer.enable(true)
                    softwareMicMixer.volume = if (isMicMuted.value) 0f else 1.0f
                    launch {
                        var enabledOn: AudioEngine? = null
                        while (isActive) {
                            val cur = serverEngine
                            if (cur != null && cur !== enabledOn) {
                                runCatching { cur.setMicMixEnabled(true) }
                                runCatching { cur.setMicMixVolume(if (isMicMuted.value) 0f else 1.0f) }
                                enabledOn = cur
                            }
                            delay(200)
                        }
                    }
                    muteCollectorJob = launch {
                        isMicMuted.collect { muted ->
                            val v = if (muted) 0f else 1.0f
                            runCatching { serverEngine?.setMicMixVolume(v) }
                            softwareMicMixer.volume = v
                        }
                    }
                }
                MicRoutingMode.OFF -> return@launch
            }

            var socket: DatagramSocket? = null
            try {
                val buf    = ByteArray(65536)
                val packet = DatagramPacket(buf, buf.size)
                socket = DatagramSocket(micPort).apply {
                    soTimeout = 1000
                    receiveBufferSize = 1 shl 20
                }
                AppDebug.log("[MicReceiver] Listening on port $micPort (mode=$routingMode) rcvbuf=${socket.receiveBufferSize}")
                MicStats.begin(MicStats.Dir.RECEIVING, ":$micPort ($routingMode)")

                val reusableShorts = ShortArray(buf.size / 2)
                val lineOutBytes   = ByteArray(buf.size)
                var totalPackets = 0L
                var totalBytes   = 0L
                var micVersionChecked = false
                var lastPayloadShorts = 0

                while (isActive) {
                    try {
                        packet.setData(buf, 0, buf.size)
                        socket.receive(packet)
                        // Only the peer that owns this session may inject microphone
                        // audio. Without this check any host on the LAN can push PCM
                        // into the mic port whenever the session runs unencrypted;
                        // multicast has no single owner, so it stays keyed only.
                        val sessionPeer = activePeerIp
                        if (!unicastPeerConnected.value ||
                            !MicSourceFilter.accepts(sessionPeer, packet.address?.hostAddress)) {
                            AppDebug.log("[MicReceiver] dropped packet from ${packet.address?.hostAddress}, session peer is $sessionPeer")
                            continue
                        }
                        val rawLen = packet.length
                        if (rawLen < AUDIO_HEADER_SIZE || buf[0] != AUDIO_MAGIC_0 || buf[1] != AUDIO_MAGIC_1 ||
                            (buf[2].toInt() and 0xFF) != WFAS_PROTOCOL_VERSION) continue
                        if (rawLen >= AUDIO_HEADER_SIZE && buf[0] == AUDIO_MAGIC_0 && buf[1] == AUDIO_MAGIC_1) {
                            if ((buf[3].toInt() and WfasCrypto.FLAG_ENCRYPTED) != 0) {
                                val md = micRecvDir ?: continue
                                val r = WfasCrypto.decryptPacket(md, micRecvWin, buf, rawLen)
                                if (r !is WfasCrypto.Decrypted.Ok) continue
                                System.arraycopy(r.pcm, 0, buf, AUDIO_HEADER_SIZE, r.pcm.size)
                                packet.setData(buf, 0, AUDIO_HEADER_SIZE + r.pcm.size)
                            } else if (sessionEncryptedLive.value || micRecvDir != null) {
                                continue
                            }
                        }
                        val len = packet.length
                        if (len <= 0) continue

                        var dataOff = 0
                        var dataLen = len
                        var silence = false
                        if (len >= AUDIO_HEADER_SIZE && packet.data[0] == AUDIO_MAGIC_0 && packet.data[1] == AUDIO_MAGIC_1) {
                            if (!micVersionChecked) {
                                micVersionChecked = true
                                val ver = packet.data[2].toInt() and 0xFF
                                if (ver != WFAS_PROTOCOL_VERSION) {
                                    signalProtocolMismatch(ver, PeerRole.RECEIVER)
                                    onStatusUpdate?.invoke("status_protocol_incompatible", emptyArray())
                                    AppDebug.log("[MicReceiver] mic v=$ver incompatible (mine v=$WFAS_PROTOCOL_VERSION)")
                                    break
                                }
                            }
                            silence = (packet.data[3].toInt() and 0x01) != 0
                            dataOff = AUDIO_HEADER_SIZE
                            dataLen = len - AUDIO_HEADER_SIZE
                        }

                        val effSilence = silence || isMicMuted.value
                        if (effSilence) MicStats.addSilence(len) else MicStats.addAudio(len)

                        totalPackets++
                        totalBytes += len
                        if (totalPackets == 1L || totalPackets % 200L == 0L) {
                            AppDebug.log("[MicReceiver] pkt #$totalPackets from ${packet.address.hostAddress}:${packet.port} len=$len silence=$effSilence mode=$routingMode")
                        }

                        val numShorts: Int
                        if (effSilence) {
                            numShorts = (if (dataLen >= 2) dataLen / 2 else lastPayloadShorts).coerceAtMost(reusableShorts.size)
                            for (i in 0 until numShorts) reusableShorts[i] = 0
                        } else {
                            val evenLen = dataLen - (dataLen % 2)
                            numShorts = (evenLen / 2).coerceAtMost(reusableShorts.size)
                            val bb = java.nio.ByteBuffer.wrap(packet.data, dataOff, evenLen)
                                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            var i = 0
                            while (bb.remaining() >= 2 && i < numShorts) reusableShorts[i++] = bb.short
                            lastPayloadShorts = numShorts
                        }
                        if (numShorts <= 0) continue

                        when (routingMode) {
                            MicRoutingMode.VIRTUAL_MIC -> {
                                when {
                                    nativeVirtualSinkActive -> activeVirtualSinkEngine?.writeToVirtualSink(reusableShorts, numShorts)
                                    wasapiSinkEngine != null -> wasapiSinkEngine!!.micSinkWrite(reusableShorts, numShorts)
                                    line != null -> {
                                        val outBytes = numShorts * 2
                                        java.nio.ByteBuffer.wrap(lineOutBytes, 0, outBytes)
                                            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                            .asShortBuffer().put(reusableShorts, 0, numShorts)
                                        line!!.write(lineOutBytes, 0, outBytes)
                                    }
                                    else -> if (totalPackets == 1L) AppDebug.log("[MicReceiver] ERROR: no active mic destination")
                                }
                            }
                            MicRoutingMode.MIX_INTO_STREAM -> {
                                serverEngine?.pushMicPcm(reusableShorts, numShorts)
                                softwareMicMixer.pushPcm(reusableShorts, numShorts)
                            }
                            MicRoutingMode.OFF -> break
                        }
                    } catch (_: java.net.SocketTimeoutException) { continue }
                }
            } finally {
                socket?.close()
            }
        } catch (e: Exception) {
            if (e !is CancellationException) AppDebug.log("[MicReceiver] error: ${e.message}")
        } finally {
            MicStats.off()
            muteCollectorJob?.cancel()
            if (mixActive && localMicMixJob?.isActive != true) {
                serverEngine?.setMicMixEnabled(false)
                serverEngine?.setMicMixVolume(1.0f)
                softwareMicMixer.enable(false)
                softwareMicMixer.volume = 1.0f
            }
            if (nativeVirtualSinkActive) {
                activeVirtualSinkEngine?.destroyVirtualSink()
                activeVirtualSinkEngine = null
            }
            wasapiSinkEngine?.let {
                runCatching { it.micSinkClose() }
            }
            line?.let {
                runCatching { it.drain() }
                runCatching { it.stop() }
                runCatching { it.close() }
            }
        }
    }

    @Volatile private var activeVirtualSinkEngine: AudioEngine? = null

    // ── Local microphone mix-into-stream capture (server side) ────────────────
    private fun CoroutineScope.launchLocalMicMix(
        audioSettings: AudioSettings_V1,
        micMixInputInfo: Mixer.Info
    ) = launch(Dispatchers.IO) {
        var localMicLine: TargetDataLine? = null
        var muteCollectorJob: Job? = null
        var engineEnableJob: Job? = null
        var keepAliveJob: Job? = null
        var micMixCaptureId = 0L

        try {
            micMixCaptureId = CaptureMonitor.begin(
                CaptureMonitor.Kind.MICROPHONE,
                runCatching { micMixInputInfo.name }.getOrDefault("microphone"),
                Strings.get("capture_peer_local_mix")
            )
            val micGain = 1.0f

            softwareMicMixer.enable(true)
            softwareMicMixer.volume = if (isMicMuted.value) 0f else micGain

            engineEnableJob = launch {
                var enabledOn: AudioEngine? = null
                while (isActive) {
                    val cur = serverEngine
                    if (cur != null && cur !== enabledOn) {
                        runCatching { cur.setMicMixEnabled(true) }
                        runCatching { cur.setMicMixVolume(if (isMicMuted.value) 0f else micGain) }
                        enabledOn = cur
                    }
                    delay(200)
                }
            }

            muteCollectorJob = launch {
                isMicMuted.collect { muted ->
                    val v = if (muted) 0f else micGain
                    runCatching { serverEngine?.setMicMixVolume(v) }
                    softwareMicMixer.volume = v
                }
            }

            keepAliveJob = launch {
                runCatching {
                    val fmt = AudioFormat(audioSettings.sampleRate, 16, 2, true, false)
                    val info = DataLine.Info(SourceDataLine::class.java, fmt)
                    if (AudioSystem.isLineSupported(info)) {
                        val line = AudioSystem.getLine(info) as SourceDataLine
                        line.open(fmt, 4096)
                        line.start()
                        val zeros = ByteArray(1024)
                        while (isActive) {
                            line.write(zeros, 0, zeros.size)
                        }
                        line.stop()
                        line.close()
                    }
                }
            }

            val mixer = runCatching { AudioSystem.getMixer(micMixInputInfo) }.getOrNull() ?: return@launch
            val streamSr  = audioSettings.sampleRate
            val streamBd  = 16
            val streamCh  = audioSettings.channels

            val candidates = mutableListOf<AudioFormat>()
            candidates += AudioFormat(streamSr, streamBd, streamCh, true, false)
            if (streamCh != 1) candidates += AudioFormat(streamSr, streamBd, 1, true, false)
            for (sr in floatArrayOf(48000f, 44100f, 32000f, 16000f)) {
                if (sr == streamSr) continue
                candidates += AudioFormat(sr, streamBd, streamCh, true, false)
                candidates += AudioFormat(sr, streamBd, 1, true, false)
            }

            var chosen: AudioFormat? = null
            for (fmt in candidates) {
                val info = DataLine.Info(TargetDataLine::class.java, fmt)
                if (!mixer.isLineSupported(info)) continue
                runCatching {
                    val bufSize = (fmt.sampleRate * fmt.frameSize * 0.1f).toInt()
                    val l = (mixer.getLine(info) as TargetDataLine).apply {
                        open(fmt, bufSize)
                        start()
                    }
                    localMicLine = l
                    chosen = fmt
                }
                if (chosen != null) break
            }
            if (localMicLine == null || chosen == null) return@launch

            val inSr      = chosen!!.sampleRate
            val inCh      = chosen!!.channels
            val needsUpmix   = inCh == 1 && streamCh == 2
            val needsDownmix = inCh == 2 && streamCh == 1
            val needsResample = inSr != streamSr

            val readBuf = ByteArray(16384)
            val maxInShorts = readBuf.size / 2
            val inShortsBuf = ShortArray(maxInShorts)
            val procShortsBuf = ShortArray(maxInShorts * 2)
            val ratio = streamSr.toDouble() / inSr.toDouble()
            val outShortsBuf = ShortArray(((maxInShorts * 2) * (if (needsResample) ratio else 1.0)).toInt() + 8)
            val byteOrder = if (chosen!!.isBigEndian) java.nio.ByteOrder.BIG_ENDIAN else java.nio.ByteOrder.LITTLE_ENDIAN

            while (isActive) {
                val n = localMicLine!!.read(readBuf, 0, readBuf.size)
                if (n <= 0) continue

                val numInShorts = n / 2
                val bb = java.nio.ByteBuffer.wrap(readBuf, 0, n).order(byteOrder)
                var idx = 0
                while (bb.remaining() >= 2 && idx < numInShorts) inShortsBuf[idx++] = bb.short

                var procShorts: ShortArray = inShortsBuf
                var procLen = numInShorts
                var procCh = inCh
                if (needsDownmix) {
                    var j = 0
                    var k = 0
                    while (k + 1 < numInShorts) {
                        procShortsBuf[j++] = ((inShortsBuf[k].toInt() + inShortsBuf[k + 1].toInt()) / 2).toShort()
                        k += 2
                    }
                    procShorts = procShortsBuf
                    procLen = j
                    procCh = 1
                } else if (needsUpmix) {
                    var j = 0
                    for (i in 0 until numInShorts) {
                        val s = inShortsBuf[i]
                        procShortsBuf[j++] = s
                        procShortsBuf[j++] = s
                    }
                    procShorts = procShortsBuf
                    procLen = j
                    procCh = 2
                }

                val outLen: Int
                val outShorts: ShortArray
                if (!needsResample) {
                    outShorts = procShorts
                    outLen = procLen
                } else {
                    val inFrames = procLen / procCh
                    var outPos = 0
                    var srcFrame = 0.0
                    val cap = outShortsBuf.size
                    while (srcFrame < inFrames - 1 && outPos + procCh <= cap) {
                        val base = srcFrame.toInt()
                        val frac = (srcFrame - base).toFloat()
                        for (c in 0 until procCh) {
                            val a = procShorts[base * procCh + c].toInt()
                            val b = procShorts[(base + 1) * procCh + c].toInt()
                            val v = (a + (b - a) * frac).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                            outShortsBuf[outPos++] = v.toShort()
                        }
                        srcFrame += 1.0 / ratio
                    }
                    outShorts = outShortsBuf
                    outLen = outPos
                }

                serverEngine?.pushMicPcm(outShorts, outLen)
                softwareMicMixer.pushPcm(outShorts, outLen)
            }
        } catch (_: CancellationException) {
        } catch (e: Exception) {
            AppDebug.log("[LocalMicMix] error: ${e.message}")
        } finally {
            if (micMixCaptureId != 0L) CaptureMonitor.end(micMixCaptureId)
            muteCollectorJob?.cancel()
            engineEnableJob?.cancel()
            keepAliveJob?.cancel()
            runCatching { localMicLine?.stop() }
            runCatching { localMicLine?.close() }
            if (micReceiverJob?.isActive != true) {
                runCatching { serverEngine?.setMicMixEnabled(false) }
                runCatching { serverEngine?.setMicMixVolume(1.0f) }
                softwareMicMixer.enable(false)
                softwareMicMixer.volume = 1.0f
            }
        }
    }

    // ── Microphone sender (client side) ───────────────────────────────────────
    private fun CoroutineScope.launchMicSender(
        audioSettings: AudioSettings_V1,
        serverInfo: ServerInfo,
        micInputMixerInfo: Mixer.Info,
        micPort: Int,
        sessionSendDirection: WfasCrypto.Dir? = null
    ) = launch {
        val mixer    = resolveMixer(micInputMixerInfo) ?: return@launch
        val format   = audioSettings.toAudioFormat()
        val lineInfo = DataLine.Info(TargetDataLine::class.java, format)
        if (!mixer.isLineSupported(lineInfo)) return@launch

        val line = (mixer.getLine(lineInfo) as TargetDataLine).also {
            it.open(format, audioSettings.bufferSize)
            it.start()
        }
        val closeOnCancel = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { line.stop(); line.close() }
        }
        var micCaptureId = 0L
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            val dest = if (serverInfo.isMulticast) InetAddress.getByName(MULTICAST_GROUP_IP)
            else InetAddress.getByName(serverInfo.ip)
            MicStats.begin(MicStats.Dir.SENDING, "${serverInfo.ip}:$micPort")
            micCaptureId = CaptureMonitor.begin(
                CaptureMonitor.Kind.MICROPHONE,
                runCatching { micInputMixerInfo.name }.getOrDefault("microphone"),
                "${serverInfo.ip}:$micPort"
            )
            val chunkBytes = audioSettings.maxPayloadBytes.coerceIn(256, 1400 - AUDIO_HEADER_SIZE - WfasCrypto.AEAD_OVERHEAD)
            val buf = ByteArray(audioSettings.bufferSize)
            val packetBuffer = ByteArray(AUDIO_HEADER_SIZE + chunkBytes)
            var seq = 0
            var lastMutedSent = false
            fun sendMicPacket(silence: Boolean, src: ByteArray?, off: Int, len: Int) {
                val md = sessionSendDirection
                if (md != null) {
                    val payload = if (!silence && src != null && len > 0) src.copyOfRange(off, off + len) else ByteArray(0)
                    val enc = WfasCrypto.encryptPacket(md, seq, 0, silence, payload)
                    runCatching { socket!!.send(DatagramPacket(enc, enc.size, dest, micPort)) }
                } else {
                    writeMicHeader(packetBuffer, seq, silence)
                    val plen = if (silence) AUDIO_HEADER_SIZE else AUDIO_HEADER_SIZE + len
                    if (!silence && src != null && len > 0) System.arraycopy(src, off, packetBuffer, AUDIO_HEADER_SIZE, len)
                    runCatching { socket!!.send(DatagramPacket(packetBuffer, plen, dest, micPort)) }
                }
                seq = (seq + 1) and 0xFFFF
            }
            while (isActive) {
                if (isMicMuted.value) {
                    if (!lastMutedSent) {
                        sendMicPacket(true, null, 0, 0)
                        MicStats.addSilence(AUDIO_HEADER_SIZE)
                        lastMutedSent = true
                    }
                    line.read(buf, 0, buf.size)
                    continue
                }
                lastMutedSent = false
                val n = line.read(buf, 0, buf.size)
                if (n > 0) {
                    var offset = 0
                    while (offset < n) {
                        val remaining = n - offset
                        var chunk = if (remaining > chunkBytes) chunkBytes else remaining
                        chunk -= chunk % 2
                        if (chunk <= 0) break
                        sendMicPacket(false, buf, offset, chunk)
                        MicStats.addAudio(AUDIO_HEADER_SIZE + chunk)
                        offset += chunk
                    }
                }
            }
        } catch (e: Exception) {
            if (e !is CancellationException) AppDebug.log("Mic sender error: ${e.message}")
        } finally {
            MicStats.off()
            if (micCaptureId != 0L) CaptureMonitor.end(micCaptureId)
            socket?.close()
            closeOnCancel.cancel()
            line.stop(); line.close()
        }
    }

    // ── AudioEngine factory ────────────────────────────────────────────────────
    private fun buildAndStartEngine(
        audioSettings: AudioSettings_V1,
        muteRender: Boolean = true
    ): AudioEngine? {
        val targetLatencyMs = 10
        val bufFrames = (audioSettings.sampleRate.toInt() * targetLatencyMs / 1000)
            .coerceAtLeast(256)
        val engine = AudioEngine(
            sampleRate   = audioSettings.sampleRate.toInt(),
            channels     = audioSettings.channels,
            bufferFrames = bufFrames,
            muteRender   = muteRender
        )
        return if (engine.start()) {
            engine
        } else {
            println("=== AudioEngine start failed: ${engine.lastError} ===")
            null
        }
    }

    // ── FFmpeg grabber factory (motore legacy) ─────────────────────────────────
    private fun buildAndStartGrabber(audioSettings: AudioSettings_V1): org.bytedeco.javacv.FFmpegFrameGrabber? {
        val os = System.getProperty("os.name").lowercase()
        val isLinux = !os.contains("win") && !os.contains("mac")

        if (isLinux) routeLinuxAudioToVirtualCable()

        if (isLinux) {
            // Prova prima pulse:VirtualCable.monitor — bypassa il plugin ALSA di PulseAudio
            // e permette di impostare fragment_size direttamente su PA, riducendo la latenza
            // da ~1s (alsa:default via plugin ALSA) a ~10ms.
            val fragSize = (audioSettings.sampleRate.toInt() * audioSettings.channels * 2 * 5 / 1000)
                .coerceAtLeast(256)
            try {
                return org.bytedeco.javacv.FFmpegFrameGrabber("VirtualCable.monitor").apply {
                    setFormat("pulse")
                    setOption("probesize",         "32")
                    setOption("analyzeduration",   "0")
                    setOption("fflags",            "nobuffer")
                    setOption("thread_queue_size", "4096")
                    setOption("fragment_size",     fragSize.toString())
                    sampleRate    = audioSettings.sampleRate.toInt()
                    audioChannels = audioSettings.channels
                    sampleFormat  = org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_S16
                    start()
                }
            } catch (e: Exception) {
                println("=== FFmpeg pulse grabber failed (${e.message}), falling back to alsa ===")
            }
        }

        val (grabberFormat, deviceName) = when {
            os.contains("win") -> "dshow"        to "audio=CABLE Output (VB-Audio Virtual Cable)"
            os.contains("mac") -> "avfoundation" to ":BlackHole 2ch"
            else               -> "alsa"         to "default"
        }
        return try {
            org.bytedeco.javacv.FFmpegFrameGrabber(deviceName).apply {
                setFormat(grabberFormat)
                setOption("probesize",         "32")
                setOption("analyzeduration",   "0")
                setOption("fflags",            "nobuffer")
                setOption("thread_queue_size", "4096")
                if (os.contains("win")) {
                    val rtbuf = (audioSettings.sampleRate.toInt() * audioSettings.channels * 2 * 2).toString()
                    setOption("rtbufsize", rtbuf)
                }
                if (os.contains("mac")) setOption("avioflags", "direct")
                sampleRate    = audioSettings.sampleRate.toInt()
                audioChannels = audioSettings.channels
                sampleFormat  = org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_S16
                start()
            }
        } catch (e: Exception) {
            println("=== FFmpeg grabber start failed: ${e.message} ===")
            null
        }
    }

    private suspend fun processGrabberFrame(
        frame: org.bytedeco.javacv.Frame,
        chunkArray: ShortArray,
        byteBuffer: java.nio.ByteBuffer,
        maxShortsPerPacket: Int,
        timeline: WfasPacketTimeline,
        onPcm: ((ShortArray) -> Unit)? = null,
        onChunk: suspend (bytesToSend: Int) -> Boolean
    ) {
        val samples = frame.samples ?: return
        val source = samples.getOrNull(0) as? java.nio.ShortBuffer ?: return

        val pcm = runCatching {
            source.position(0)
            val available = source.remaining()
            if (available <= 0) return
            ShortArray(available).also { source.get(it, 0, available) }
        }.getOrElse {
            AppDebug.log("[SERVER] frame del grabber non leggibile: ${it.message}")
            return
        }

        require(pcm.size % timeline.channels == 0) { "Grabber returned an incomplete PCM frame" }
        var offset = 0
        while (offset < pcm.size) {
            val shortsToRead = minOf(pcm.size - offset, maxShortsPerPacket)
            pcm.copyInto(chunkArray, 0, offset, offset + shortsToRead)
            offset += shortsToRead
            softwareMicMixer.mixInto(chunkArray, shortsToRead)
            DesktopGainLimiter.apply(chunkArray, shortsToRead, currentServerVolume)
            onPcm?.invoke(chunkArray.copyOf(shortsToRead))
            byteBuffer.clear()
            byteBuffer.asShortBuffer().put(chunkArray, 0, shortsToRead)
            // A packet the sender refused aborts the frame: its samples must not be re-sent as a
            // later packet with a new sequence number.
            if (!onChunk(shortsToRead * 2)) {
                timeline.skipPcm((pcm.size - offset) * 2)
                return
            }
        }
    }

    // ── HTTP server (AAC chunked + Opus WebSocket) ─────────────────────────────
    private fun CoroutineScope.launchHttpServer(
        audioSettings: AudioSettings_V1,
        httpPort: Int,
        safariMode: Boolean,
        onStatusUpdate: (key: String, args: Array<out Any>) -> Unit
    ) = launch {
        val aacQueue  = java.util.concurrent.ArrayBlockingQueue<ByteArray>(64)
        val opusQueue = java.util.concurrent.ArrayBlockingQueue<ByteArray>(64)
        aacPcmQueue  = aacQueue
        opusPcmQueue = opusQueue

        val aacClients  = java.util.concurrent.CopyOnWriteArrayList<java.io.OutputStream>()
        val opusClients = java.util.concurrent.CopyOnWriteArrayList<java.io.OutputStream>()

        // Init segment WebM (EBML header + Tracks element) — deve essere inviato a ogni
        // nuovo client prima di qualsiasi cluster, altrimenti MSE invalida il SourceBuffer.
        // Viene catturato dal readerJob riconoscendo il marker del primo Cluster (0x1F43B675).
        val webmInitSegment = java.util.concurrent.atomic.AtomicReference<ByteArray?>(null)        // Client che si connettono dopo che l'init segment è già stato emesso:
        // rimangono in questa lista finché non ricevono l'init segment, poi vengono
        // promossi in opusClients per ricevere i cluster normali.
        val pendingOpusClients = java.util.concurrent.CopyOnWriteArrayList<java.io.OutputStream>()

        // ── HTML player page ───────────────────────────────────────────────────
        val htmlPage = buildString {
            append("<!DOCTYPE html><html lang=\"en\"><head>")
            append("<meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
            append("<title>AudioBridge</title><style>")
            // Nuovo CSS unificato
            append(":root { --bg: #0f0f0f; --surface: #1e1e1e; --primary: #BB86FC; --text: #e0e0e0; --text-mut: #888; }")
            append("*{box-sizing:border-box;margin:0;padding:0}")
            append("body{min-height:100vh;display:flex;flex-direction:column;align-items:center;justify-content:center;background:var(--bg);font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;color:var(--text);padding:20px;}")
            append(".card{background:var(--surface);padding:32px;border:1px solid #2a2a2a;border-radius:28px;text-align:center;max-width:400px;width:100%;box-shadow:0 8px 32px rgba(0,0,0,0.5);}")
            append(".icon{font-size:48px;margin-bottom:12px}")
            append("h1{font-size:22px;font-weight:600;margin-bottom:8px;color:#fff}")
            append(".sub{font-size:14px;color:var(--text-mut);margin-bottom:24px}")
            append(".dot{display:inline-block;width:10px;height:10px;border-radius:50%;background:#22c55e;margin-right:8px;animation:pulse 1.5s ease-in-out infinite}")
            append("@keyframes pulse{0%,100%{opacity:1;transform:scale(1)}50%{opacity:.5;transform:scale(.85)}}")
            append(".status{font-size:14px;color:#22c55e;display:flex;align-items:center;justify-content:center;margin-bottom:16px;}")
            append(".warn{font-size:12px;color:#888;margin-top:12px} .error{color:#ef4444} audio{display:none;width:100%;border-radius:50px;}")
            append(".links{display:flex;flex-direction:column;gap:10px;margin-top:24px;}")
            append(".links a{text-decoration:none;color:var(--text);background:rgba(255,255,255,0.05);padding:14px;border-radius:16px;font-size:14px;transition:background 0.2s;border:1px solid rgba(255,255,255,0.05);font-weight:500;}")
            append(".links a:hover{background:rgba(255,255,255,0.1);}")
            append(".kofi{margin-top:24px;padding-top:20px;border-top:1px solid rgba(255,255,255,0.05);}")
            append(".kofi a{color:#FF5E5B;text-decoration:none;font-weight:bold;font-size:15px;}")
            append("</style></head><body>")

            // Struttura HTML
            append("<div class=\"card\">")
            append("<div class=\"icon\">🎧</div>")
            append("<h1>AudioBridge</h1>")
            append("<p class=\"sub\" id=\"codec-label\">Connecting&hellip;</p>")
            append("<p class=\"status\" id=\"st\"><span class=\"dot\"></span>Live</p>")

            // Link e Ko-fi
            append("<div class=\"links\">")
            append("<a href=\"https://github.com/mu23XR/AudioBridge\" target=\"_blank\">💻 Get Desktop App (GitHub)</a>")
            append("<a href=\"https://github.com/mu23XR/AudioBridge\" target=\"_blank\">📱 Get Android App (GitHub)</a>")
            append("<a href=\"https://github.com/mu23XR/AudioBridge/releases\" target=\"_blank\">📲 Get Android App (IzzyOnDroid)</a>")
            append("</div>")

            append("<div class=\"kofi\">")
            append("<a href=\"06\" target=\"_blank\">☕ Support me on Ko-fi</a>")
            append("</div>")

            append("</div>")

            // Elemento audio nascosto e inizio JS
            append("<audio id=\"a\" autoplay playsinline></audio>")
            append("<script>")
            // ── Costanti ──────────────────────────────────────────────────────
            // AAC ADTS funziona solo su Safari/iOS con MSE nativo.
            // Chrome/Firefox non supportano 'audio/aac' in MSE → usano sempre Opus WS.
            // La UA detection è sempre attiva indipendentemente da safariMode:
            // safariMode controlla solo discovery/advertising, non il routing del codec.
            append("const isSafari=/^((?!chrome|android).)*safari/i.test(navigator.userAgent)")
            append("  ||/iPad|iPhone|iPod/.test(navigator.userAgent);")
            append("const a=document.getElementById('a'),st=document.getElementById('st'),lbl=document.getElementById('codec-label');")

            // ── Helpers UI ────────────────────────────────────────────────────
            append("function setLive(){st.innerHTML='<span class=\"dot\"></span>Live';st.className='status';}")
            append("function setErr(msg){st.textContent=msg;st.className='status error';}")
            append("a.addEventListener('playing',setLive);")
            append("a.addEventListener('waiting',()=>{st.textContent='Buffering\u2026';});")
            append("a.addEventListener('error',()=>setErr('Playback error'));")

            // ── Funzione play con fallback tap-to-play (policy autoplay) ──────
            append("function tryPlay(){a.play().catch(()=>{")
            append("  if(document.getElementById('tapbtn'))return;")
            append("  const b=document.createElement('button');")
            append("  b.id='tapbtn';")
            append("  b.textContent='\u25b6\ufe0f Tap to listen';")
            append("  b.style.cssText='margin-top:20px;padding:12px 28px;border:none;border-radius:50px;background:#fff;color:#000;font-size:16px;cursor:pointer';")
            append("  b.onclick=()=>{a.play().then(()=>b.remove()).catch(()=>{});};")
            append("  document.querySelector('.card').appendChild(b);")
            append("});}")

            // ── MSE helper: appende chunk in coda evitando overlap ────────────
            // sourceBuffer.updating=true significa che non possiamo appendere ancora.
            // Usiamo una coda interna per serializzare gli append.
            append("function makeMSEAppender(sb){")
            append("  const q=[];let busy=false;")
            append("  sb.addEventListener('updateend',()=>{busy=false;drain();});")
            append("  function drain(){if(busy||!q.length)return;busy=true;sb.appendBuffer(q.shift());}")
            append("  return function(buf){q.push(buf);drain();};")
            append("}")

            // ── Chrome/Firefox/Edge: Opus WebM via WebSocket + MSE ───────────
            // WebM/Opus: Chrome Android richiede WebM (non OGG) per MSE.
            // Il server invia prima l'init segment (EBML+Tracks) poi i cluster.
            append("if(!isSafari){")
            append("  lbl.textContent='Opus \u2022 WebM \u2022';")
            append("  const MIME='audio/webm; codecs=opus';")
            // Se MSE non supporta WebM/Opus, fallback a decodeAudioData
            // (Firefox Android, browser obsoleti)
            append("  if(typeof MediaSource==='undefined'||!MediaSource.isTypeSupported(MIME)){")
            append("    lbl.textContent='Opus \u2022 fallback';")
            append("    const ac=new AudioContext();")
            append("    const ws2=new WebSocket('ws://'+location.host+'/stream/opus');")
            append("    ws2.binaryType='arraybuffer';")
            append("    ws2.onopen=()=>setLive();")
            // Accumula fino a 200KB poi decodifica — gestisce l'init segment automaticamente
            append("    let fbBuf=new Uint8Array(0);")
            append("    ws2.onmessage=async e=>{")
            append("      if(ac.state==='suspended')await ac.resume();")
            append("      const d=new Uint8Array(e.data);")
            append("      const t=new Uint8Array(fbBuf.length+d.length);")
            append("      t.set(fbBuf);t.set(d,fbBuf.length);fbBuf=t;")
            append("      if(fbBuf.length>8192){")
            append("        try{const b=await ac.decodeAudioData(fbBuf.buffer.slice(0));")
            append("          const s=ac.createBufferSource();s.buffer=b;s.connect(ac.destination);s.start();")
            append("        }catch(_){}fbBuf=new Uint8Array(0);}")
            append("    };")
            append("    ws2.onclose=()=>setErr('Disconnected');")
            append("  }else{")
            append("    const ms=new MediaSource();")
            append("    a.src=URL.createObjectURL(ms);")
            append("    ms.addEventListener('sourceopen',()=>{")
            append("      let sb;")
            append("      try{sb=ms.addSourceBuffer(MIME);}catch(e){setErr('MSE error: '+e.message);return;}")
            append("      sb.mode='sequence';")
            append("      const app=makeMSEAppender(sb);")
            // Gestione errori MSE: se il SourceBuffer viene rimosso, lo segnaliamo
            append("      sb.addEventListener('error',e=>setErr('Buffer error'));")
            append("      ms.addEventListener('error',e=>setErr('MediaSource error'));")
            append("      const ws=new WebSocket('ws://'+location.host+'/stream/opus');")
            append("      ws.binaryType='arraybuffer';")
            append("      ws.onopen=()=>setLive();")
            append("      ws.onmessage=e=>{if(ms.readyState==='open'&&sb.updating===false||true)app(e.data);};")
            append("      ws.onclose=()=>setErr('Disconnected');")
            append("      ws.onerror=()=>setErr('WS error');")
            append("      tryPlay();")
            append("    });")
            append("  }")

            // ── Safari/iOS: AAC via elemento <audio> ──────────────────────────
            // L'elemento <audio src> con AAC/ADTS è il metodo nativo più
            // affidabile su tutti i WebKit (iOS Safari, macOS Safari, Chrome iOS).
            // MSE con audio/aac è inaffidabile tra versioni diverse di Safari.
            append("}else{")
            append("  lbl.textContent='AAC \u2022 ADTS';")
            append("  a.src='/stream/aac';")
            append("  a.addEventListener('playing',()=>lbl.textContent='AAC \u2022 Live',{once:true});")
            append("  a.addEventListener('error',(e)=>setErr('AAC error: '+(a.error?a.error.code:'?')));")
            append("  a.load();")
            append("  tryPlay();")
            append("}")
            append("</script></body></html>")
        }
        val htmlBytes = htmlPage.toByteArray(Charsets.UTF_8)

        // ── WebSocket helpers ──────────────────────────────────────────────────
        fun sendWsFrame(out: java.io.OutputStream, payload: ByteArray) {
            val len = payload.size
            when {
                len < 126   -> out.write(byteArrayOf(0x82.toByte(), len.toByte()))
                len < 65536 -> out.write(byteArrayOf(0x82.toByte(), 126.toByte(),
                    (len shr 8).toByte(), (len and 0xFF).toByte()))
                else        -> out.write(byteArrayOf(0x82.toByte(), 127.toByte(),
                    0, 0, 0, 0,
                    (len shr 24).toByte(), (len shr 16).toByte(),
                    (len shr 8).toByte(), (len and 0xFF).toByte()))
            }
            out.write(payload)
            out.flush()
        }

        fun broadcastChunked(
            clients: java.util.concurrent.CopyOnWriteArrayList<java.io.OutputStream>,
            data: ByteArray, isWebSocket: Boolean
        ) {
            if (clients.isEmpty()) return
            val dead = mutableListOf<java.io.OutputStream>()
            for (out in clients) {
                try {
                    if (isWebSocket) {
                        sendWsFrame(out, data)
                    } else {
                        val header = "${data.size.toString(16)}\r\n".toByteArray()
                        out.write(header); out.write(data); out.write("\r\n".toByteArray())
                        out.flush()
                    }
                } catch (_: Exception) { dead.add(out) }
            }
            clients.removeAll(dead)
        }

        // ── HTTP acceptor ──────────────────────────────────────────────────────
        val acceptorJob = launch(Dispatchers.IO) {
            val serverSocket = java.net.ServerSocket().apply {
                reuseAddress = true
                bind(java.net.InetSocketAddress(httpPort))
                soTimeout = 500
            }
            // Chiusura sincrona nel finally — garantisce che la porta sia libera
            // prima che cancelAndJoin() ritorni al chiamante (stopCurrentStream).
            // invokeOnCompletion era asincrono e causava BindException al riavvio.
            try {
                AppDebug.log("---HTTP server on port $httpPort (safariMode=$safariMode) ---")
                while (isActive) {
                    val sock = try { serverSocket.accept() }
                    catch (_: java.net.SocketTimeoutException) { continue }
                    catch (e: Exception) {
                        if (e !is CancellationException && !serverSocket.isClosed)
                            AppDebug.log("HTTP accept: ${e.message}")
                        break
                    }
                    launch(Dispatchers.IO) {
                        try {
                            val br = sock.getInputStream().bufferedReader()
                            val requestLine = br.readLine() ?: return@launch
                            val headers = mutableMapOf<String, String>()
                            while (true) {
                                val line = br.readLine() ?: break
                                if (line.isEmpty()) break
                                val colon = line.indexOf(':')
                                if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] =
                                    line.substring(colon + 1).trim()
                            }
                            val path = requestLine.split(" ").getOrNull(1) ?: "/"
                            val out  = sock.getOutputStream()
                            when {
                                path == "/stream/aac" -> {
                                    out.write("HTTP/1.1 200 OK\r\nContent-Type: audio/aac\r\nTransfer-Encoding: chunked\r\nCache-Control: no-cache\r\nConnection: keep-alive\r\nAccess-Control-Allow-Origin: *\r\nAccept-Ranges: none\r\nX-Content-Type-Options: nosniff\r\n\r\n".toByteArray())
                                    out.flush()
                                    aacClients.add(out)
                                    AppDebug.log("---/stream/aac client: ${sock.inetAddress} ---")
                                    while (isActive && !sock.isClosed) delay(500)
                                }
                                path == "/stream/opus" && headers["upgrade"]?.lowercase() == "websocket" -> {
                                    val key    = headers["sec-websocket-key"] ?: return@launch
                                    val accept = java.util.Base64.getEncoder().encodeToString(
                                        java.security.MessageDigest.getInstance("SHA-1")
                                            .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray())
                                    )
                                    out.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
                                    out.flush()
                                    AppDebug.log("---/stream/opus WS client: ${sock.inetAddress} ---")
                                    // Se l'init segment WebM è già disponibile, invialo subito
                                    // al nuovo client e aggiungilo alla lista attiva.
                                    // Se non è ancora disponibile (encoder non ancora partito)
                                    // lo mettiamo in pending: il readerJob lo promuoverà.
                                    val initBytes = webmInitSegment.get()
                                    if (initBytes != null) {
                                        try { sendWsFrame(out, initBytes); opusClients.add(out) }
                                        catch (_: Exception) { /* client già morto */ }
                                    } else {
                                        pendingOpusClients.add(out)
                                    }
                                    sock.soTimeout = 100
                                    val inBuf = ByteArray(256)
                                    while (isActive && !sock.isClosed) {
                                        try { sock.getInputStream().read(inBuf) } catch (_: java.net.SocketTimeoutException) {}
                                        delay(500)
                                    }
                                }
                                else -> {
                                    out.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${htmlBytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                    out.write(htmlBytes)
                                    out.flush()
                                    sock.close()
                                }
                            }
                        } catch (e: Exception) {
                            if (e !is CancellationException) AppDebug.log("HTTP handler: ${e.message}")
                        } finally {
                            runCatching { sock.close() }
                        }
                    }
                }
            } finally {
                runCatching { serverSocket.close() }
            }
        }

        // ── AAC encoder ────────────────────────────────────────────────────────
        val aacEncoderJob = launch(Dispatchers.IO) {
            val codec = org.bytedeco.ffmpeg.global.avcodec.avcodec_find_encoder(
                org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_AAC
            ) ?: run { AppDebug.log("AAC codec not found"); return@launch }

            val ctx = org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3(codec)
            ctx.bit_rate(192_000)
            ctx.sample_rate(audioSettings.sampleRate.toInt())
            org.bytedeco.ffmpeg.global.avutil.av_channel_layout_default(ctx.ch_layout(), audioSettings.channels)
            ctx.sample_fmt(org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_FLTP)
            ctx.flags(ctx.flags() or org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_FLAG_LOW_DELAY)
            ctx.profile(1)
            org.bytedeco.ffmpeg.global.avcodec.avcodec_open2(ctx, codec, null as org.bytedeco.ffmpeg.avutil.AVDictionary?)

            val frameSize      = ctx.frame_size().let { if (it <= 0) 1024 else it }
            val nCh            = audioSettings.channels
            val samplesPerFrame = frameSize * nCh

            val avFrame = org.bytedeco.ffmpeg.global.avutil.av_frame_alloc().also {
                it.format(ctx.sample_fmt())
                it.ch_layout(ctx.ch_layout())
                it.sample_rate(ctx.sample_rate())
                it.nb_samples(frameSize)
                org.bytedeco.ffmpeg.global.avutil.av_frame_get_buffer(it, 0)
            }
            val pkt    = org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc()
            val accumS16 = java.nio.ShortBuffer.allocate(samplesPerFrame * 4)
            var pts    = 0L

            // ADTS header: 7 byte per frame — necessario perché avcodec produce
            // raw AAC senza framing; il browser ha bisogno del sync word 0xFFF.
            val adtsSampleRate = audioSettings.sampleRate.toInt()
            val adtsFreqIdx = when (adtsSampleRate) {
                96000 -> 0; 88200 -> 1; 64000 -> 2; 48000 -> 3; 44100 -> 4
                32000 -> 5; 24000 -> 6; 22050 -> 7; 16000 -> 8; 12000 -> 9
                11025 -> 10; 8000 -> 11; 7350 -> 12; else -> 3
            }
            val adtsProfile = 1 // AAC-LC (MPEG-4 Object Type 2, ADTS profile = objectType - 1)
            val adtsChanCfg = nCh

            fun wrapAdts(raw: ByteArray): ByteArray {
                val frameLen = 7 + raw.size
                val hdr = ByteArray(7)
                hdr[0] = 0xFF.toByte()
                hdr[1] = 0xF1.toByte() // MPEG-4, Layer 0, no CRC
                hdr[2] = ((adtsProfile shl 6) or (adtsFreqIdx shl 2) or (adtsChanCfg shr 2)).toByte()
                hdr[3] = (((adtsChanCfg and 3) shl 6) or (frameLen shr 11)).toByte()
                hdr[4] = ((frameLen shr 3) and 0xFF).toByte()
                hdr[5] = (((frameLen and 7) shl 5) or 0x1F).toByte()
                hdr[6] = 0xFC.toByte() // buffer fullness 0x7FF (VBR), 0 raw data blocks
                val out = ByteArray(frameLen)
                System.arraycopy(hdr, 0, out, 0, 7)
                System.arraycopy(raw, 0, out, 7, raw.size)
                return out
            }

            fun encodeAac() {
                org.bytedeco.ffmpeg.global.avutil.av_frame_make_writable(avFrame)
                accumS16.flip()
                for (ch in 0 until nCh) {
                    val planeBuf = avFrame.data(ch).limit(frameSize.toLong() * 4)
                        .asByteBuffer().order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                    val tmp = accumS16.duplicate().also { it.position(ch) }
                    for (i in 0 until frameSize) {
                        val s = if (tmp.hasRemaining()) tmp.get() else 0
                        if (nCh > 1 && tmp.hasRemaining()) tmp.get()
                        planeBuf.put(s / 32768f)
                    }
                }
                repeat(samplesPerFrame) { if (accumS16.hasRemaining()) accumS16.get() }
                accumS16.clear()
                avFrame.pts(pts); pts += frameSize.toLong()
                if (org.bytedeco.ffmpeg.global.avcodec.avcodec_send_frame(ctx, avFrame) >= 0) {
                    while (org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_packet(ctx, pkt) >= 0) {
                        val raw = ByteArray(pkt.size()).also { pkt.data().get(it) }
                        org.bytedeco.ffmpeg.global.avcodec.av_packet_unref(pkt)
                        broadcastChunked(aacClients, wrapAdts(raw), false)
                    }
                }
            }

            try {
                while (isActive) {
                    val pcm = aacQueue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                    if (aacClients.isEmpty()) continue
                    val inc = java.nio.ByteBuffer.wrap(pcm).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    while (inc.hasRemaining()) {
                        accumS16.put(inc.get())
                        if (accumS16.position() >= samplesPerFrame) encodeAac()
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) AppDebug.log("AAC encoder error: ${e.message}")
            } finally {
                runCatching { org.bytedeco.ffmpeg.global.avutil.av_frame_free(avFrame) }
                runCatching { org.bytedeco.ffmpeg.global.avcodec.av_packet_free(pkt) }
                runCatching { org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context(ctx) }
            }
        }

        // ── Opus/OGG encoder ───────────────────────────────────────────────────
        val opusEncoderJob = launch(Dispatchers.IO) {
            val opusSampleRate = audioSettings.sampleRate.toInt()
            val nCh            = audioSettings.channels

            val pipeOut = java.io.PipedOutputStream()
            val pipeIn  = java.io.PipedInputStream(pipeOut, 131072)

            // WebM/Opus: unico container accettato da Chrome Android in MSE.
            // OGG/Opus fallisce silenziosamente su Chrome mobile con MSE.
            val recorder = org.bytedeco.javacv.FFmpegFrameRecorder(pipeOut, nCh).apply {
                audioCodec   = org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_OPUS
                sampleRate   = opusSampleRate
                audioBitrate = 96_000
                audioQuality = 0.0
                setAudioOption("application",    "lowdelay")
                setAudioOption("frame_duration", "20")
                format = "webm"
                start()
            }

            val readerJob = launch(Dispatchers.IO) {
                // Accumulator per l'init segment WebM.
                // Un file WebM inizia con EBML header (0x1A45DFA3) seguito dall'elemento
                // Segment (0x18538067) che contiene SeekHead, Info e Tracks.
                // Il primo Cluster inizia con il tag 0x1F43B675 — tutto ciò che precede
                // il primo Cluster è l'init segment che MSE deve ricevere prima di qualsiasi dato.
                val initBuf = java.io.ByteArrayOutputStream()
                var initCaptured = false
                // Marker del Cluster WebM in big-endian (4 byte)
                val clusterMarker = byteArrayOf(0x1F.toByte(), 0x43.toByte(), 0xB6.toByte(), 0x75.toByte())

                fun ByteArray.indexOfSequence(seq: ByteArray, fromIndex: Int = 0): Int {
                    outer@ for (i in fromIndex..size - seq.size) {
                        for (j in seq.indices) if (this[i + j] != seq[j]) continue@outer
                        return i
                    }
                    return -1
                }

                val readBuf = ByteArray(8192)
                try {
                    while (isActive) {
                        val n = pipeIn.read(readBuf)
                        if (n <= 0) continue
                        val chunk = readBuf.copyOf(n)

                        if (!initCaptured) {
                            // Accumula finché non troviamo il primo Cluster
                            initBuf.write(chunk)
                            val accumulated = initBuf.toByteArray()
                            val clusterIdx = accumulated.indexOfSequence(clusterMarker)
                            if (clusterIdx >= 0) {
                                // Tutto ciò che precede il primo Cluster = init segment
                                val initSegment = accumulated.copyOf(clusterIdx)
                                webmInitSegment.set(initSegment)
                                initCaptured = true

                                // Promuovi i client pending: manda loro l'init segment
                                val toPromote = pendingOpusClients.toList()
                                pendingOpusClients.clear()
                                for (out in toPromote) {
                                    try { sendWsFrame(out, initSegment); opusClients.add(out) }
                                    catch (_: Exception) {}
                                }

                                // Il resto dell'accumulated (dal Cluster in poi) è il primo cluster
                                val firstCluster = accumulated.copyOfRange(clusterIdx, accumulated.size)
                                if (firstCluster.isNotEmpty())
                                    broadcastChunked(opusClients, firstCluster, true)

                                initBuf.reset() // libera memoria
                            }
                            // Se non abbiamo ancora trovato il Cluster, continuiamo ad accumulare
                        } else {
                            // Init segment già catturato: controlla se nuovi client pending
                            // si sono connessi nel frattempo (race window molto stretta)
                            val initBytes = webmInitSegment.get()
                            if (initBytes != null) {
                                val toPromote = pendingOpusClients.toList()
                                if (toPromote.isNotEmpty()) {
                                    pendingOpusClients.clear()
                                    for (out in toPromote) {
                                        try { sendWsFrame(out, initBytes); opusClients.add(out) }
                                        catch (_: Exception) {}
                                    }
                                }
                            }
                            broadcastChunked(opusClients, chunk, true)
                        }
                    }
                } catch (e: Exception) {
                    if (e !is CancellationException && e !is java.io.IOException)
                        AppDebug.log("Opus pipe reader error: ${e.message}")
                } finally {
                    runCatching { pipeIn.close() }
                }
            }

            try {
                while (isActive) {
                    val pcm = opusQueue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                    if (opusClients.isEmpty() && pendingOpusClients.isEmpty()) continue

                    // FIX: copia i campioni in uno ShortArray owned — swresample accede alla memoria
                    // nativa anche dopo che record() ritorna, quindi NON passare il ByteArray della coda.
                    val nShorts = pcm.size / 2
                    val shorts  = ShortArray(nShorts)
                    java.nio.ByteBuffer.wrap(pcm).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)

                    val frame = org.bytedeco.javacv.Frame().also {
                        it.sampleRate    = opusSampleRate
                        it.audioChannels = nCh
                        it.samples       = arrayOf(java.nio.ShortBuffer.wrap(shorts))
                    }
                    try { recorder.record(frame) } catch (e: Exception) {
                        if (e !is CancellationException) AppDebug.log("Opus record error: ${e.message}")
                        break
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) AppDebug.log("Opus encoder error: ${e.message}")
            } finally {
                // Order matters: stop recorder (flushes internal buffers into pipe) → close pipe
                // (unblocks reader) → cancel reader → release native resources.
                runCatching { recorder.stop() }
                runCatching { pipeOut.close() }
                readerJob.cancel(); readerJob.join()
                runCatching { recorder.release() }
            }
        }

        try { acceptorJob.join() } finally {
            acceptorJob.cancel()
            aacEncoderJob.cancel()
            opusEncoderJob.cancel()
            aacPcmQueue  = null
            opusPcmQueue = null
        }
    }

    // ── RTP sidecar ───────────────────────────────────────────────────────────
    private fun CoroutineScope.launchRtpSidecar(
        audioSettings: AudioSettings_V1,
        port: Int,
        isMulticast: Boolean
    ) = launch(Dispatchers.IO) {
        val queue = java.util.concurrent.ArrayBlockingQueue<ByteArray>(25)
        rtpPcmQueue = queue
        rtpSkippedBytes.set(0L)

        var sequenceNumber = (Math.random() * 65535).toInt()
        var rtpTimestamp   = (Math.random() * Int.MAX_VALUE).toLong()
        val ssrc           = (Math.random() * Int.MAX_VALUE).toLong()
        val rtpFrameBytes  = audioSettings.channels * 2
        val rtpClockRate   = audioSettings.sampleRate.toLong().coerceAtLeast(8000L)

        /** Quando tocca al prossimo pacchetto. 0 = non abbiamo ancora un ritmo. */
        var nextDueNs = 0L
        /** Da mettere sul primo pacchetto dopo un buco, come vuole RFC 3551. */
        var marker = false

        val socket: DatagramSocket = if (isMulticast) {
            MulticastSocket().apply {
                timeToLive = 4
                getActiveNetworkInterface()?.let { networkInterface = it }
            }
        } else DatagramSocket()

        val destAddress = if (isMulticast) InetAddress.getByName(MULTICAST_GROUP_IP)
        else InetAddress.getByName(getLocalIpAddress())

        AppDebug.log("---RTP sidecar started on port $port (multicast=$isMulticast) ---")
        try {
            while (isActive) {
                val pcmLeBytes = queue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue

                // Quel che la coda ha buttato via non e' audio che non e'
                // esistito: e' audio che manca. Il timestamp lo salta, cosi'
                // chi ascolta vede il buco e lo copre; senza, crederebbe che
                // il flusso sia continuo e si ritroverebbe in anticipo di quel
                // tanto per tutto il resto della sessione.
                val skippedBytes = rtpSkippedBytes.getAndSet(0L)
                if (skippedBytes >= rtpFrameBytes) {
                    val skippedFrames = skippedBytes / rtpFrameBytes
                    rtpTimestamp += skippedFrames
                    if (nextDueNs != 0L) {
                        nextDueNs += skippedFrames * 1_000_000_000L / rtpClockRate
                    }
                    marker = true
                }

                // Dimensione massima sicura per evitare la frammentazione IP (MTU 1500)
                val maxPayloadSize = 1400
                var offset = 0

                while (offset < pcmLeBytes.size) {
                    val chunkSize = minOf(maxPayloadSize, pcmLeBytes.size - offset)
                    val rtpPacket = ByteArray(12 + chunkSize)
                    val buf = java.nio.ByteBuffer.wrap(rtpPacket).order(java.nio.ByteOrder.BIG_ENDIAN)

                    // Header RTP
                    buf.put(0x80.toByte())
                    buf.put(if (marker) (96 or 0x80).toByte() else 96.toByte())
                    marker = false
                    buf.putShort((sequenceNumber and 0xFFFF).toShort())
                    buf.putInt((rtpTimestamp and 0xFFFFFFFFL).toInt())
                    buf.putInt((ssrc and 0xFFFFFFFFL).toInt())

                    // Convertiamo da Little Endian (FFmpeg) a Big Endian (Network standard)
                    val leBuf = java.nio.ByteBuffer.wrap(pcmLeBytes, offset, chunkSize).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val beBuf = buf.asShortBuffer()
                    while (leBuf.hasRemaining()) beBuf.put(leBuf.get())

                    val samplesInChunk = chunkSize / 2 / audioSettings.channels // 2 byte per sample (16-bit)

                    // Il ritmo: ogni pacchetto parte quando gli tocca.
                    //
                    // Il resto del server manda a raffica, e per WFAS va bene:
                    // il suo client sa a quale campione va suonato ogni
                    // pacchetto e ha una coda che assorbe le ondate. Un
                    // ricevitore RTP no -- RTP e' un flusso e basta, e chi lo
                    // ascolta si aspetta che arrivi al ritmo con cui si suona.
                    // Dieci pacchetti insieme e poi 66 ms di niente vuol dire
                    // una coda che si svuota fino in fondo a ogni giro.
                    //
                    // Il ritmo si conta sui campioni, non sull'orologio: cosi'
                    // e' esattamente quello con cui la scheda audio li produce
                    // e non deriva. Se restiamo indietro di mezzo secondo --
                    // l'audio si e' fermato, il thread e' stato via -- si
                    // riparte da adesso invece di rincorrere il passato.
                    val nowNs = System.nanoTime()
                    if (nextDueNs == 0L || nowNs - nextDueNs > rtpPaceResyncNs) {
                        nextDueNs = nowNs
                    } else if (nextDueNs - nowNs > 1_000_000L) {
                        delay((nextDueNs - nowNs) / 1_000_000L)
                    }
                    nextDueNs += samplesInChunk * 1_000_000_000L / rtpClockRate

                    runCatching { socket.send(DatagramPacket(rtpPacket, rtpPacket.size, destAddress, port)) }

                    // Aggiorniamo i contatori in modo millimetrico per il prossimo pacchetto
                    sequenceNumber = (sequenceNumber + 1) and 0xFFFF
                    rtpTimestamp += samplesInChunk
                    offset += chunkSize
                }
            }
        } catch (e: Exception) {
            if (e !is CancellationException) AppDebug.log("RTP sidecar error: ${e.message}")
        } finally {
            socket.close()
            rtpPcmQueue = null
            AppDebug.log("---RTP sidecar stopped ---")
        }
    }

    // ── Sidecar distribution ──────────────────────────────────────────────────
    // FIX: RTP ora riceve anch'esso una copia distinta del buffer, eliminando la
    // potenziale data race con il loop del grabber che potrebbe riusare byteBuffer.
    private fun distributeToSidecars(pcmBytes: ByteArray) {
        rtpPcmQueue?.let {
            if (it.remainingCapacity() <= 0 || !it.offer(pcmBytes.copyOf())) {
                rtpSkippedBytes.addAndGet(pcmBytes.size.toLong())
            }
        }
        aacPcmQueue?.let  { if (it.remainingCapacity() > 0) it.offer(pcmBytes.copyOf()) }
        opusPcmQueue?.let { if (it.remainingCapacity() > 0) it.offer(pcmBytes.copyOf()) }
        dlnaManager?.submitPcm(pcmBytes.copyOf())
        snapcastManager?.submitPcm(pcmBytes.copyOf())
    }

    private suspend fun processEngineFrame(
        samples: ShortArray,
        chunkArray: ShortArray,
        byteBuffer: java.nio.ByteBuffer,
        maxShortsPerPacket: Int,
        timeline: WfasPacketTimeline,
        silenceFrames: Int = 0,
        onChunk: suspend (bytesToSend: Int) -> Boolean
    ) {
        if (samples.isEmpty()) {
            byteBuffer.clear()
            timeline.stampSilence(byteBuffer.array(), silenceFrames)
            onChunk(AUDIO_HEADER_SIZE)
            return
        }
        val channels = timeline.channels
        require(samples.size % channels == 0) { "Capture returned an incomplete PCM frame" }
        var offset = 0
        while (offset < samples.size) {
            var shortsToRead = minOf(samples.size - offset, maxShortsPerPacket)
            shortsToRead -= (shortsToRead % channels)
            if (shortsToRead <= 0) break

            for (i in 0 until shortsToRead) chunkArray[i] = samples[offset + i]

            DesktopGainLimiter.apply(chunkArray, shortsToRead, currentServerVolume)

            byteBuffer.clear()
            timeline.stampPcm(byteBuffer.array(), shortsToRead * 2)

            val payloadBytes = byteBuffer.array()
            var bytePos = AUDIO_HEADER_SIZE
            for (i in 0 until shortsToRead) {
                val sample = chunkArray[i].toInt()
                payloadBytes[bytePos++] = (sample and 0xFF).toByte()
                payloadBytes[bytePos++] = ((sample shr 8) and 0xFF).toByte()
            }

            offset         += shortsToRead

            // Stop the frame when the peer link refused this packet: retrying later packets of the
            // same frame would send audio under sequence numbers that never carried it.
            if (!onChunk(AUDIO_HEADER_SIZE + (shortsToRead * 2))) {
                timeline.skipPcm((samples.size - offset) * 2)
                return
            }
        }
    }

    /**
     * Legacy (grabber) silence packet: magic, version, silence flag, 16-bit sequence and no
     * payload. The grabber returns null instead of silence when the source stalls, so the caller
     * emits this header to keep the peer's session alive without inventing audio.
     */
    private fun writeLegacySilenceHeader(packetArray: ByteArray, timeline: WfasPacketTimeline, elapsedFrames: Int) {
        timeline.stampSilence(packetArray, elapsedFrames)
    }

    // ── Server ─────────────────────────────────────────────────────────────────
    fun launchServerInstance(
        audioSettings: AudioSettings_V1,
        port: Int,
        isMulticast: Boolean,
        capabilities: ServerCapabilities,
        micRoutingMode: MicRoutingMode,
        micOutputMixerInfo: Mixer.Info?,
        micPort: Int,
        rtpPort: Int,
        useNativeEngine: Boolean = true,
        micMixInputInfo: Mixer.Info? = null,
        muteRender: Boolean = true,
        persist: Boolean = false,
        dlnaConfig: DlnaServerConfig? = null,
        snapcastConfig: SnapcastServerConfig? = null,
        onAudioFrame: ((ShortArray) -> Unit)? = null,
        onStatusUpdate: (key: String, args: Array<out Any>) -> Unit
    ) {
        val myGen = lifecycleGeneration.incrementAndGet()
        scope.launch {
            lifecycleMutex.withLock {
                if (myGen < lifecycleGeneration.get()) return@withLock
                stopCurrentStreamLocked()
                if (myGen < lifecycleGeneration.get()) return@withLock
                startServerInstanceLocked(
                    audioSettings, port, isMulticast, capabilities,
                    micRoutingMode, micOutputMixerInfo, micPort, rtpPort,
                    useNativeEngine, micMixInputInfo,
                    muteRender = muteRender,
                    persist = persist,
                    dlnaConfig = dlnaConfig,
                    snapcastConfig = snapcastConfig,
                    onAudioFrame = onAudioFrame,
                    onStatusUpdate = onStatusUpdate
                )
            }
        }
    }

    private fun startServerInstanceLocked(
        audioSettings: AudioSettings_V1,
        port: Int,
        isMulticast: Boolean,
        capabilities: ServerCapabilities,
        micRoutingMode: MicRoutingMode,
        micOutputMixerInfo: Mixer.Info?,
        micPort: Int,
        rtpPort: Int,
        useNativeEngine: Boolean = true,
        micMixInputInfo: Mixer.Info? = null,
        muteRender: Boolean = true,
        persist: Boolean = false,
        dlnaConfig: DlnaServerConfig? = null,
        snapcastConfig: SnapcastServerConfig? = null,
        onAudioFrame: ((ShortArray) -> Unit)? = null,
        onStatusUpdate: (key: String, args: Array<out Any>) -> Unit
    ) {
        CaptureMonitor.begin(
            CaptureMonitor.Kind.SYSTEM_AUDIO,
            Strings.get("capture_source_loopback"),
            if (isMulticast) "$MULTICAST_GROUP_IP:$port" else Strings.get("capture_peer_listeners", port)
        )
        val annCaps = capabilities.copy(serverWantsMic = micRoutingMode != MicRoutingMode.OFF)
        if (!WfasPolicy.canStartServer(
                StreamingProtocol.RTP in capabilities.protocols,
                StreamingProtocol.HTTP in capabilities.protocols,
                dlnaConfig?.enabled == true,
                snapcastConfig?.enabled == true
            )
        ) {
            AppDebug.log("[Server] refused: WFAS off and no other protocol or USB link available")
            onStatusUpdate("wfas_no_protocol_desc", emptyArray())
            return
        }
        if (micRoutingMode != MicRoutingMode.OFF) {
            micReceiverJob = scope.launchMicReceiver(
                audioSettings, isMulticast, micRoutingMode, micOutputMixerInfo, micPort, micMixInputInfo, onStatusUpdate
            )
        }

        // macOS non ha un mute di endpoint come Windows/Linux: si abbassa il volume
        // di sistema. E' la stessa scelta che muteRender governa altrove, quindi
        // --no-mute-render deve saltare anche questa.
        val isMacOS = System.getProperty("os.name").lowercase().let { it.contains("mac") || it.contains("darwin") }
        if (isMacOS && muteRender && AudioEngine.loadLibrary()) {
            val helperEngine = AudioEngine()
            val vol = helperEngine.getSystemVolume()
            if (vol >= 0f) {
                macOriginalVolume = vol
                helperEngine.setSystemVolume(0f)
                AppDebug.log("[Server] macOS: volume saved ($vol), lowered to 0")
            }
        }

        if (micRoutingMode == MicRoutingMode.MIX_INTO_STREAM && micMixInputInfo != null) {
            localMicMixJob = scope.launchLocalMicMix(audioSettings, micMixInputInfo)
        }

        if (StreamingProtocol.HTTP in capabilities.protocols && capabilities.httpPort != null) {
            httpServerJob = scope.launchHttpServer(audioSettings, capabilities.httpPort, capabilities.safariMode, onStatusUpdate)
        }

        if (StreamingProtocol.RTP in capabilities.protocols) {
            rtpJob = scope.launchRtpSidecar(audioSettings, rtpPort, isMulticast)
        }

        if (dlnaConfig != null && dlnaConfig.enabled) {
            val manager = DlnaSessionManager(
                scope = scope,
                sampleRate = audioSettings.sampleRate.toInt(),
                channels = audioSettings.channels,
                mediaPort = dlnaConfig.port,
                preference = dlnaConfig.preference,
                selectedUdns = dlnaConfig.selectedUdns,
                streamTitle = dlnaConfig.title,
                localAddressProvider = { getLocalIpAddress() },
                preferredInterfaceProvider = { getActiveNetworkInterface() }
            )
            dlnaManager = manager
            manager.start()
        }

        if (snapcastConfig != null && snapcastConfig.enabled) {
            val manager = SnapcastSessionManager(
                scope = scope,
                config = snapcastConfig,
                sampleRate = audioSettings.sampleRate.toInt(),
                channels = audioSettings.channels,
                bitDepth = audioSettings.bitDepth,
                hostName = snapcastHostName(),
                serverVersion = Strings.appVersion,
                persistenceFile = java.io.File(ConfigPaths.configDir(), "snapcast-state.json"),
                localAddressProvider = { getLocalIpAddress() },
                preferredInterfaceProvider = { getActiveNetworkInterface() },
                log = { AppDebug.log(it) }
            )
            snapcastManager = manager
            manager.start()
        }

        startAnnouncingPresence(isMulticast, port, annCaps, audioSettings)

        if (!WfasPolicy.enabledOnNetwork()) {
            val summary = ProtocolStatus.summary(
                wfas = false,
                rtp = StreamingProtocol.RTP in capabilities.protocols,
                http = StreamingProtocol.HTTP in capabilities.protocols,
                dlna = dlnaConfig?.enabled == true,
                conjunction = Strings.get("list_and"),
                snapcast = snapcastConfig?.enabled == true
            )
            if (summary.isNotEmpty()) onStatusUpdate("status_serving_protocols", arrayOf(summary))
        }

        val ownedMicReceiver = micReceiverJob
        streamingJob = scope.launch(Dispatchers.IO) {
            try {
                if (isMulticast) {
                    if (useNativeEngine) {
                        serverEngine = buildAndStartEngine(audioSettings, muteRender)
                        if (serverEngine == null) {
                            onStatusUpdate("error_virtual_driver_missing", emptyArray())
                            return@launch
                        }
                    } else {
                        serverGrabber = buildAndStartGrabber(audioSettings)
                        if (serverGrabber == null) {
                            onStatusUpdate("error_virtual_driver_missing", emptyArray())
                            return@launch
                        }
                    }

                    onStatusUpdate("status_multicast_streaming", arrayOf(port))

                    val nCh               = audioSettings.channels
                    val mcSession = McastSession()

                    fun applyGroupKey(key: String) {
                        if (encryptionEnabled && SecurityMode.requiresKey(securityMode) && key.isBlank()) {
                            onStatusUpdate("status_key_required", emptyArray())
                            AppDebug.log("[SERVER][MULTICAST] cifratura richiesta ma la chiave e' vuota: non parto")
                            throw IllegalStateException("encryption requested without a key")
                        }
                        if (!(encryptionEnabled && SecurityMode.requiresKey(securityMode))) {
                            mcSession.dir = null
                            mcSession.beacon = null
                            mcSession.epoch = 0L
                            mcSession.key = key
                            mcastSession.value = McastSnapshot(0L, key, false)
                            sessionEncryptedLive.value = false
                            return
                        }
                        val salt = ByteArray(WfasCrypto.SALT_BYTES)
                            .also { java.security.SecureRandom().nextBytes(it) }
                        val epoch = SettingsRepository.nextMcastEpoch()
                        val beacon = WfasCrypto.buildMcastBeacon(
                            key, epoch, System.currentTimeMillis() / 1000, salt
                        ).toByteArray(Charsets.US_ASCII)
                        mcSession.beacon = beacon
                        mcSession.beaconUrgent = true
                        mcSession.dir = WfasCrypto.deriveMulticast(key, salt)
                        mcSession.epoch = epoch
                        mcSession.key = key
                        mcastSession.value = McastSnapshot(epoch, key, true)
                        sessionEncryptedLive.value = true
                    }

                    applyGroupKey(authKey)
                    mcastRekey = { newKey -> applyGroupKey(newKey) }

                    val safeMtuSize        = 1400
                    var maxShortsPerPacket = (audioSettings.maxPayloadBytes.coerceIn(256, safeMtuSize - AUDIO_HEADER_SIZE)) / 2
                    maxShortsPerPacket    -= (maxShortsPerPacket % nCh)
                    run {
                        maxShortsPerPacket -= (WfasCrypto.AEAD_OVERHEAD + 1) / 2
                        maxShortsPerPacket -= (maxShortsPerPacket % nCh)
                        if (maxShortsPerPacket < nCh) maxShortsPerPacket = nCh
                    }
                    val exactPayloadBytes  = AUDIO_HEADER_SIZE + (maxShortsPerPacket * 2)
                    val chunkArray         = ShortArray(maxShortsPerPacket)
                    val byteBuffer         = java.nio.ByteBuffer.allocate(
                        if (useNativeEngine) exactPayloadBytes else maxShortsPerPacket * 2
                    ).apply { if (!useNativeEngine) order(java.nio.ByteOrder.LITTLE_ENDIAN) }
                    val packetArray        = ByteArray(exactPayloadBytes)
                    val packetTimeline = WfasPacketTimeline(nCh)
                    fun frameForSend(buf: ByteArray, len: Int): ByteArray {
                        val dir = mcSession.dir ?: return if (len == buf.size) buf else buf.copyOf(len)
                        val silence = (buf[3].toInt() and 0x01) != 0
                        val seq = ((buf[4].toInt() and 0xFF) shl 8) or (buf[5].toInt() and 0xFF)
                        val pos = ((buf[6].toLong() and 0xFF) shl 24) or ((buf[7].toLong() and 0xFF) shl 16) or
                                ((buf[8].toLong() and 0xFF) shl 8) or (buf[9].toLong() and 0xFF)
                        return WfasCrypto.encryptPacket(dir, seq, pos, silence, buf.copyOfRange(AUDIO_HEADER_SIZE, len))
                    }

                    try {
                        MulticastSocket(null as java.net.SocketAddress?).use { socket ->
                            socket.reuseAddress = true
                            socket.bind(java.net.InetSocketAddress(0))
                            socket.timeToLive = 4
                            socket.loopbackMode = true
                            MulticastNet.chooseSendInterface(getActiveNetworkInterface()) { WfasPolicy.enabledOn(it) }
                                ?.let { socket.networkInterface = it }
                            val group = MulticastNet.audioGroup(socket.networkInterface)
                            var sendFailureLogged = false

                            suspend fun emit(bytes: ByteArray, size: Int = bytes.size): Boolean {
                                return try {
                                    socket.send(DatagramPacket(bytes, size, group, port))
                                    sendFailureLogged = false
                                    true
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (e: Exception) {
                                    if (!sendFailureLogged) {
                                        sendFailureLogged = true
                                        AppDebug.log("[SERVER][MULTICAST] send fallita su $group:$port via ${socket.networkInterface?.name ?: "default"}: ${e.message}")
                                        onStatusUpdate("status_multicast_send_failed", arrayOf(e.message ?: e.javaClass.simpleName))
                                    }
                                    if (!TransientSendError.isTransient(e)) throw e
                                    delay(500)
                                    false
                                }
                            }

                            val keepAlive = launch(Dispatchers.IO) {
                                var lastBeacon = 0L
                                while (isActive) {
                                    delay(100)
                                    val beaconNow = mcSession.beacon ?: continue
                                    val now = System.currentTimeMillis()
                                    if (mcSession.beaconUrgent || now - lastBeacon >= 400L) {
                                        emit(beaconNow)
                                        lastBeacon = now
                                        mcSession.beaconUrgent = false
                                    }
                                }
                            }

                            val silenceTimeline = SilenceTimeline(audioSettings.sampleRate.toInt())
                            try {
                                while (isActive) {
                                    if (useNativeEngine) {
                                        val engine  = serverEngine ?: break
                                        val samples = engine.readFrame() ?: break
                                        // The beacon proves the sender is alive, but a silent stream still
                                        // starves the receiver's audio freshness check. Emitting silence
                                        // headers keeps the sample timeline moving like the unicast path.
                                        val silentRead = samples.isEmpty()
                                        val nowNs = System.nanoTime()
                                        val silenceFrames = if (silentRead) {
                                            silenceTimeline.onSilence(nowNs)
                                        } else {
                                            silenceTimeline.onAudio(nowNs)
                                            0
                                        }
                                        if (!silentRead) onAudioFrame?.invoke(samples)

                                        processEngineFrame(samples, chunkArray, byteBuffer, maxShortsPerPacket, packetTimeline, silenceFrames) { bytesToSend ->
                                            byteBuffer.array().copyInto(packetArray, 0, 0, bytesToSend)
                                            val ob = frameForSend(packetArray, bytesToSend)
                                            val sent = emit(ob)
                                            if (sent) WfasStats.add(if ((packetArray[3].toInt() and 0x01) != 0) WfasStats.Cat.SILENCE else WfasStats.Cat.AUDIO, bytesToSend)
                                            if (sent && (aacPcmQueue != null || opusPcmQueue != null || rtpPcmQueue != null || dlnaManager != null || snapcastManager != null))
                                                distributeToSidecars(byteBuffer.array().copyOfRange(AUDIO_HEADER_SIZE, bytesToSend))
                                            sent
                                        }
                                        if (silentRead) delay(10)
                                    } else {
                                        val grabber = serverGrabber ?: break
                                        val frame = try { grabber.grabSamples() }
                                        catch (e: org.bytedeco.javacv.FFmpegFrameGrabber.Exception) {
                                            AppDebug.log("Grabber exception (multicast): ${e.message}")
                                            break
                                        }
                                        if (frame != null) {
                                            silenceTimeline.onAudio(System.nanoTime())
                                            processGrabberFrame(frame, chunkArray, byteBuffer, maxShortsPerPacket, packetTimeline, onAudioFrame) { bytesToSend ->
                                                packetTimeline.stampPcm(packetArray, bytesToSend)
                                                byteBuffer.array().copyInto(packetArray, AUDIO_HEADER_SIZE, 0, bytesToSend)
                                                val totalBytes = AUDIO_HEADER_SIZE + bytesToSend
                                                val ob = frameForSend(packetArray, totalBytes)
                                                val sent = emit(ob)
                                                if (sent) WfasStats.add(WfasStats.Cat.AUDIO, totalBytes)
                                                if (sent && (aacPcmQueue != null || opusPcmQueue != null || rtpPcmQueue != null || dlnaManager != null || snapcastManager != null))
                                                    distributeToSidecars(byteBuffer.array().copyOf(bytesToSend))
                                                sent
                                            }
                                        } else {
                                            writeLegacySilenceHeader(packetArray, packetTimeline, silenceTimeline.onSilence(System.nanoTime()))
                                            if (emit(frameForSend(packetArray, AUDIO_HEADER_SIZE)))
                                                WfasStats.add(WfasStats.Cat.SILENCE, AUDIO_HEADER_SIZE)
                                            delay(10)
                                        }
                                    }
                                }

                            } finally {
                                withContext(NonCancellable) {
                                    runCatching { keepAlive.cancelAndJoin() }
                                    runCatching {
                                        val bye = "BYE".toByteArray()
                                        repeat(3) {
                                            socket.send(DatagramPacket(bye, bye.size, group, port))
                                            WfasStats.add(WfasStats.Cat.BYE, bye.size)
                                            Thread.sleep(15)
                                        }
                                        AppDebug.log("---Sent BYE to multicast group ---")
                                    }
                                }
                            }
                        }
                    } finally {
                        if (useNativeEngine) {
                            val engineToStop = serverEngine
                            serverEngine = null
                            runCatching { engineToStop?.stop() }
                        } else {
                            val grabberToStop = serverGrabber
                            serverGrabber = null
                            runCatching { grabberToStop?.stop() }
                            runCatching { grabberToStop?.release() }
                        }
                    }

                } else { // Unicast
                    val localAddress = InetSocketAddress(NetAddr.wildcardHost(), port)
                    AppDebug.log("[SERVER][UNICAST] binding $localAddress")
                    aSocket(selectorManager).udp().bind(localAddress) { reuseAddress = true }.use { socket ->
                        // A challenge belongs to the peer it was issued to, and expires
                        // on its own. A single shared slot meant the newest HELLO on the
                        // socket overwrote whatever the previous caller was still
                        // answering - and the session keys were then derived from
                        // whichever pair of nonces happened to be left in it.
                        class PendingChallenge(val cnonce: String, val snonce: String, val at: Long)
                        val challenges = HashMap<String, PendingChallenge>()
                        fun challengeFor(peer: String): PendingChallenge? {
                            val now = System.currentTimeMillis()
                            challenges.entries.removeAll { now - it.value.at > CHALLENGE_TTL_MS }
                            return challenges[peer]
                        }
                        // The nonces of the session that authenticated, kept for the key
                        // derivation below. Only ever written after a proof verified.
                        var pendCnonce = ""
                        var pendSnonce = ""
                        while (isActive) {
                            startAnnouncingPresence(isMulticast = false, port = port, capabilities = annCaps, audioSettings = audioSettings)
                            onStatusUpdate("status_server_waiting", arrayOf(port))

                            val clientDatagram = socket.receive()
                            val rxAt = System.currentTimeMillis()
                            val msg = clientDatagram.packet.readText().trim()
                            val clientAddress = clientDatagram.address
                            AppDebug.log("[SERVER][TIMING] rx '$msg' from $clientAddress")

                            if (msg == "MODE_PROBE") {
                                socket.send(Datagram(buildPacket { writeText("UNICAST") }, clientAddress))
                                WfasStats.add(WfasStats.Cat.PROBE, 7)
                                continue
                            }

                            if (!msg.startsWith(CLIENT_HELLO_MESSAGE)) continue

                            val peerHost = peerHostOf(clientAddress)
                            activePeerIp = peerHost
                            // Challenges are filed under the peer. When the address does
                            // not resolve to a literal host we still want a stable key, so
                            // the raw socket address stands in rather than one shared
                            // bucket that every unresolved peer would land in.
                            val challengeKey = clientAddress.toString()
                            AppDebug.log("[SERVER][TIMING] peer=$peerHost usbPeer=${UsbLink.isUsbPeer(peerHost)} " +
                                    "mode=${WfasPolicy.mode} allowed=${WfasPolicy.enabledForPeer(peerHost)} at +${System.currentTimeMillis() - rxAt}ms")
                            if (!WfasPolicy.enabledForPeer(peerHost)) {
                                AppDebug.log("[SERVER][UNICAST] WFAS not active for peer=$peerHost " +
                                        "(usbPeer=${UsbLink.isUsbPeer(peerHost)} mode=${WfasPolicy.mode}), refusing")
                                socket.send(Datagram(buildPacket { writeText(UNAUTHORIZED_MESSAGE) }, clientAddress))
                                continue
                            }

                            val clientVersion = parseProtocolVersion(msg)
                            if (clientVersion != WFAS_PROTOCOL_VERSION) {
                                AppDebug.log("[SERVER][UNICAST] incompatible client v=$clientVersion (mine v=$WFAS_PROTOCOL_VERSION), rejecting $clientAddress")
                                socket.send(Datagram(buildPacket { writeText(incompatibleMessage()) }, clientAddress))
                                WfasStats.add(WfasStats.Cat.HELLO, incompatibleMessage().length)
                                signalProtocolMismatch(clientVersion, PeerRole.RECEIVER)
                                continue
                            }

                            when (SecurityMode.fromStringSafe(securityMode)) {
                                SecurityMode.KEY -> {
                                    // Fail closed. Senza chiave la prova si
                                    // calcolerebbe su una stringa vuota, cioe'
                                    // su un segreto che chiunque puo'
                                    // indovinare: meglio rifiutare tutti che
                                    // accettare chi ha capito che non c'e'
                                    // niente da sapere. In modo QR e' lo stato
                                    // normale finche' non si genera un invito.
                                    if (authKey.isBlank()) {
                                        AppDebug.log("[SERVER][UNICAST] key mode without a key, refusing $clientAddress")
                                        socket.send(Datagram(buildPacket { writeText(UNAUTHORIZED_MESSAGE) }, clientAddress))
                                        continue
                                    }
                                    val cproof = WfasAuth.getToken(msg, "cproof")
                                    val cnonce = WfasAuth.getToken(msg, "cnonce") ?: ""
                                    if (cproof == null) {
                                        val snonce = WfasAuth.nonceHex()
                                        challenges[challengeKey] = PendingChallenge(cnonce, snonce, System.currentTimeMillis())
                                        val sproof = WfasAuth.proof(authKey, 'S', cnonce, snonce)
                                        socket.send(Datagram(buildPacket { writeText("$AUTH_REQUIRED_PREFIX;snonce=$snonce;sproof=$sproof") }, clientAddress))
                                        continue
                                    }
                                    // A proof is only an answer to a challenge we issued to
                                    // this peer and have not seen answered yet. Without one
                                    // there is nothing to check it against, and falling back
                                    // to nonces the caller supplied would check it against
                                    // its own input.
                                    val pending = challengeFor(challengeKey)
                                    if (pending == null) {
                                        AppDebug.log("[SERVER][UNICAST] proof with no pending challenge from $clientAddress")
                                        socket.send(Datagram(buildPacket { writeText(UNAUTHORIZED_MESSAGE) }, clientAddress))
                                        continue
                                    }
                                    val expected = WfasAuth.proof(authKey, 'C', pending.cnonce, pending.snonce)
                                    if (!WfasAuth.constantTimeEquals(cproof, expected)) {
                                        AppDebug.log("[SERVER][UNICAST] auth failed for $clientAddress")
                                        socket.send(Datagram(buildPacket { writeText(UNAUTHORIZED_MESSAGE) }, clientAddress))
                                        continue
                                    }
                                    // Single use: a replayed proof finds nothing waiting.
                                    challenges.remove(challengeKey)
                                    pendCnonce = pending.cnonce
                                    pendSnonce = pending.snonce
                                    AppDebug.log("[SERVER][UNICAST] auth OK for $clientAddress")
                                }
                                SecurityMode.ASK -> {
                                    val approval = awaitDesktopApproval(
                                        request = { onAuthRequest?.invoke(clientAddress.toString()) ?: false },
                                        sendPending = {
                                            socket.send(Datagram(buildPacket { writeText(PENDING_MESSAGE) }, clientAddress))
                                        }
                                    )
                                    if (approval != DesktopApproval.APPROVED) {
                                        if (approval == DesktopApproval.TIMED_OUT) {
                                            DesktopDiagnostics.event("AUTH_APPROVAL_TIMEOUT", "timeoutMs=30000")
                                            onStatusUpdate("status_auth_approval_timeout", emptyArray())
                                        }
                                        socket.send(Datagram(buildPacket { writeText(UNAUTHORIZED_MESSAGE) }, clientAddress))
                                        continue
                                    }
                                }
                                SecurityMode.OFF -> { }
                            }

                            val encrypting = encryptionEnabled &&
                                    SecurityMode.requiresKey(securityMode)
                            val sessionKeys = if (encrypting)
                                WfasCrypto.deriveUnicast(authKey, pendCnonce, pendSnonce) else null
                            val sendDir: WfasCrypto.Dir? = sessionKeys?.second
                            micRecvDir = sessionKeys?.first
                            if (encrypting) micRecvWin = WfasCrypto.ReplayWindow()
                            fun frameForSend(buf: ByteArray, len: Int): ByteArray {
                                val dir = sendDir ?: return if (len == buf.size) buf else buf.copyOf(len)
                                val silence = (buf[3].toInt() and 0x01) != 0
                                val seq = ((buf[4].toInt() and 0xFF) shl 8) or (buf[5].toInt() and 0xFF)
                                val pos = ((buf[6].toLong() and 0xFF) shl 24) or ((buf[7].toLong() and 0xFF) shl 16) or
                                        ((buf[8].toLong() and 0xFF) shl 8) or (buf[9].toLong() and 0xFF)
                                val pcm = buf.copyOfRange(AUDIO_HEADER_SIZE, len)
                                return WfasCrypto.encryptPacket(dir, seq, pos, silence, pcm)
                            }

                            stopAnnouncingPresence()

                            // Remote volume control exists only when the receiver asked
                            // for it (rvol=1 + rvnonce in HELLO). The session id is bound
                            // to this endpoint because the ACK is addressed to it, so a
                            // third peer cannot drive another peer's volume.
                            val volumeControl = RemoteVolumeProtocol.offer(
                                msg,
                                if (SecurityMode.requiresKey(securityMode)) authKey else "",
                                pendCnonce, pendSnonce,
                                object : RemoteVolumeProtocol.Volume {
                                    override fun getPercent() = DesktopVolumeScale.percent(
                                        currentServerVolume, DesktopVolumeScale.SENDER_MAX
                                    )
                                    override fun setPercent(value: Int) {
                                        setServerVolume(DesktopVolumeScale.gain(value, DesktopVolumeScale.SENDER_MAX))
                                    }
                                }
                            )
                            // The receiver expects audio as soon as it reads this ACK, so
                            // the capture session must already be running. Opening a WASAPI
                            // loopback takes tens of milliseconds; acknowledging first and
                            // failing afterwards leaves the peer connected to nothing.
                            val captureReady = if (useNativeEngine) {
                                serverEngine = buildAndStartEngine(audioSettings, muteRender)
                                serverEngine != null
                            } else {
                                serverGrabber = buildAndStartGrabber(audioSettings)
                                serverGrabber != null
                            }
                            if (!captureReady) {
                                unicastPeerConnected.value = false
                                sessionEncryptedLive.value = false
                                micRecvDir = null
                                micRecvWin = WfasCrypto.ReplayWindow()
                                activePeerIp = null
                                pendCnonce = ""; pendSnonce = ""
                                challenges.remove(challengeKey)
                                DesktopDiagnostics.event("CAPTURE_START_FAILED", "persist=$persist")
                                onStatusUpdate("status_capture_unavailable", emptyArray())
                                if (!persist) break
                                continue
                            }
                            unicastPeerConnected.value = true
                            sessionEncryptedLive.value = sendDir != null
                            onStatusUpdate("status_client_connected", arrayOf(clientAddress.toString()))

                            val ackText = helloAckMessage() + (if (encrypting) ";enc=1" else "") +
                                    (volumeControl?.ackSuffix() ?: "")
                            AppDebug.log("[SERVER][TIMING] sending HELLO_ACK at +${System.currentTimeMillis() - rxAt}ms")
                            socket.send(Datagram(buildPacket { writeText(ackText) }, clientAddress))
                            WfasStats.add(WfasStats.Cat.HELLO, ackText.length)

                            val clientAlive = java.util.concurrent.atomic.AtomicBoolean(true)
                            // Capability-gated heartbeat: only a peer that answered PONG
                            // can be evicted on silence. Older peers keep the send-failure
                            // rule, so a receiver that never sends PONG is never dropped
                            // for not sending it.
                            val clientPongCapable = java.util.concurrent.atomic.AtomicBoolean(false)
                            val lastClientHeartbeatAt =
                                java.util.concurrent.atomic.AtomicLong(System.nanoTime() / 1_000_000)

                            val pingJob = launch {
                                var failures = 0
                                while (isActive && clientAlive.get()) {
                                    delay(1000)
                                    try {
                                        socket.send(Datagram(buildPacket { writeText("PING") }, clientAddress))
                                        WfasStats.add(WfasStats.Cat.PING, 4)
                                        volumeControl?.snapshot()?.let { update ->
                                            socket.send(Datagram(buildPacket { writeText(update) }, clientAddress))
                                        }
                                        failures = 0
                                        if (clientPongCapable.get() &&
                                            System.nanoTime() / 1_000_000 - lastClientHeartbeatAt.get() >
                                            PONG_HEARTBEAT_TIMEOUT_MS
                                        ) {
                                            AppDebug.log("---PONG heartbeat stale, client considered gone ---")
                                            clientAlive.set(false)
                                        }
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        // One PING every second, so eight failures is the same
                                        // eight-second budget a PONG-capable peer gets. Ending the
                                        // session after three transient send errors dropped healthy
                                        // clients during a short Wi-Fi hiccup.
                                        if (++failures >= 8) {
                                            AppDebug.log("---PING failed 8 times, client considered gone ---")
                                            clientAlive.set(false)
                                        }
                                    }
                                }
                            }

                            val clientByeReceiverJob = launch {
                                try {
                                    while (isActive && clientAlive.get()) {
                                        val datagram = socket.receive()
                                        val msg = datagram.packet.readText().trim()
                                        // Solo il client collegato pilota questa sessione:
                                        // senza il controllo sull'indirizzo, un CLIENT_BYE
                                        // di un terzo dispositivo chiuderebbe la connessione
                                        // altrui. Ai terzi si risponde "occupato".
                                        if (datagram.address != clientAddress) {
                                            if (msg.startsWith(CLIENT_HELLO_MESSAGE) || msg == "MODE_PROBE") {
                                                AppDebug.log("[SERVER][UNICAST] ${datagram.address} rejected: busy with $clientAddress")
                                                runCatching {
                                                    socket.send(Datagram(
                                                        buildPacket { writeText(BUSY_MESSAGE) },
                                                        datagram.address
                                                    ))
                                                }
                                            }
                                            continue
                                        }
                                        if (msg == "PONG") {
                                            clientPongCapable.set(true)
                                            lastClientHeartbeatAt.set(System.nanoTime() / 1_000_000)
                                            continue
                                        }
                                        if (volumeControl != null &&
                                            (msg.startsWith(RemoteVolumeProtocol.GET) ||
                                                    msg.startsWith(RemoteVolumeProtocol.SET))
                                        ) {
                                            volumeControl.receive(msg)?.let { reply ->
                                                runCatching {
                                                    socket.send(Datagram(buildPacket { writeText(reply) }, clientAddress))
                                                }
                                            }
                                            lastClientHeartbeatAt.set(System.nanoTime() / 1_000_000)
                                            continue
                                        }
                                        // A HELLO_ACK lost in flight must not cost a whole
                                        // reconnect: repeat it for this endpoint and for the
                                        // session that actually negotiated it.
                                        if (msg.startsWith(CLIENT_HELLO_MESSAGE) &&
                                            (volumeControl == null || volumeControl.acceptsHello(msg))
                                        ) {
                                            runCatching {
                                                socket.send(Datagram(buildPacket { writeText(ackText) }, clientAddress))
                                            }
                                            continue
                                        }
                                        if (msg == "CLIENT_BYE") {
                                            AppDebug.log("---Received CLIENT_BYE from $clientAddress ---")
                                            clientAlive.set(false)
                                            if (useNativeEngine) {
                                                runCatching { serverEngine?.stop() }
                                            } else {
                                                runCatching { serverGrabber?.stop() }
                                            }
                                            break
                                        }
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    DesktopDiagnostics.event("SERVER_CONTROL_FAILED", "type=${failure.javaClass.simpleName}")
                                    clientAlive.set(false)
                                }
                            }

                            try {
                                if (useNativeEngine) {
                                    val safeMtuSize = 1400
                                    var maxShortsPerPacket = (audioSettings.maxPayloadBytes.coerceIn(256, safeMtuSize - AUDIO_HEADER_SIZE)) / 2
                                    maxShortsPerPacket -= (maxShortsPerPacket % audioSettings.channels)
                                    if (sendDir != null) {
                                        maxShortsPerPacket -= (WfasCrypto.AEAD_OVERHEAD + 1) / 2
                                        maxShortsPerPacket -= (maxShortsPerPacket % audioSettings.channels)
                                        if (maxShortsPerPacket < audioSettings.channels) maxShortsPerPacket = audioSettings.channels
                                    }
                                    val exactPayloadBytes = AUDIO_HEADER_SIZE + (maxShortsPerPacket * 2)
                                    val chunkArray = ShortArray(maxShortsPerPacket)
                                    val byteBuffer = java.nio.ByteBuffer.allocate(exactPayloadBytes)
                                    if (audioSettings.channels == 2 && maxShortsPerPacket % 2 != 0) maxShortsPerPacket -= 1
                                    val packetArray = ByteArray(exactPayloadBytes)
                                    val packetTimeline = WfasPacketTimeline(audioSettings.channels)

                                    val silenceTimeline = SilenceTimeline(audioSettings.sampleRate.toInt())
                                    var linkDown = false
                                    var failedSends = 0
                                    while (isActive && clientAlive.get()) {
                                        val engine = serverEngine ?: break
                                        val samples = engine.readFrame() ?: break
                                        if (!clientAlive.get()) break
                                        // A quiet loopback device returns "no data" rather than silence, and
                                        // skipping those reads leaves the peer without any frame for the whole
                                        // quiet period, which the receiver eventually treats as a dead session.
                                        val silentRead = samples.isEmpty()
                                        val nowNs = System.nanoTime()
                                        val silenceFrames = if (silentRead) {
                                            silenceTimeline.onSilence(nowNs)
                                        } else {
                                            silenceTimeline.onAudio(nowNs)
                                            0
                                        }
                                        if (!silentRead) onAudioFrame?.invoke(samples)
                                        processEngineFrame(samples, chunkArray, byteBuffer, maxShortsPerPacket, packetTimeline, silenceFrames) { bytesToSend ->
                                            byteBuffer.array().copyInto(packetArray, 0, 0, bytesToSend)
                                            val outBytes = frameForSend(packetArray, bytesToSend)
                                            val packet = buildPacket { writeFully(outBytes, 0, outBytes.size) }
                                            val sent = try {
                                                socket.send(Datagram(packet, clientAddress))
                                                WfasStats.add(if ((packetArray[3].toInt() and 0x01) != 0) WfasStats.Cat.SILENCE else WfasStats.Cat.AUDIO, bytesToSend)
                                                if (linkDown) {
                                                    linkDown = false
                                                    onStatusUpdate("status_link_restored", arrayOf<Any>())
                                                }
                                                failedSends = 0
                                                true
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (e: Exception) {
                                                if (TransientSendError.isTransient(e)) {
                                                    failedSends++
                                                    if (!linkDown) {
                                                        linkDown = true
                                                        onStatusUpdate("status_link_lost_waiting", arrayOf<Any>())
                                                    }
                                                    AppDebug.log("[SERVER][UNICAST] transient send failure #$failedSends: ${e.message}")
                                                    delay(500)
                                                    false
                                                } else {
                                                    clientAlive.set(false)
                                                    false
                                                }
                                            }
                                            if (sent && clientAlive.get() &&
                                                (aacPcmQueue != null || opusPcmQueue != null || rtpPcmQueue != null || dlnaManager != null || snapcastManager != null))
                                                distributeToSidecars(byteBuffer.array().copyOfRange(AUDIO_HEADER_SIZE, bytesToSend))
                                            sent
                                        }
                                        if (silentRead) delay(10)
                                    }
                                } else {
                                    val safeMtuSize = 1400
                                    var maxShortsPerPacket = (audioSettings.maxPayloadBytes.coerceIn(256, safeMtuSize - AUDIO_HEADER_SIZE)) / 2
                                    maxShortsPerPacket -= (maxShortsPerPacket % audioSettings.channels)
                                    if (sendDir != null) {
                                        maxShortsPerPacket -= (WfasCrypto.AEAD_OVERHEAD + 1) / 2
                                        maxShortsPerPacket -= (maxShortsPerPacket % audioSettings.channels)
                                        if (maxShortsPerPacket < audioSettings.channels) maxShortsPerPacket = audioSettings.channels
                                    }
                                    val exactPayloadBytes  = AUDIO_HEADER_SIZE + (maxShortsPerPacket * 2)
                                    val chunkArray         = ShortArray(maxShortsPerPacket)
                                    val byteBuffer         = java.nio.ByteBuffer.allocate(maxShortsPerPacket * 2)
                                        .apply { order(java.nio.ByteOrder.LITTLE_ENDIAN) }
                                    val packetArray        = ByteArray(exactPayloadBytes)
                                    val packetTimeline     = WfasPacketTimeline(audioSettings.channels)
                                    val silenceTimeline    = SilenceTimeline(audioSettings.sampleRate.toInt())
                                    var linkDown           = false
                                    var failedSends        = 0

                                    while (isActive && clientAlive.get()) {
                                        val grabber = serverGrabber ?: break
                                        val frame = try { grabber.grabSamples() }
                                        catch (e: org.bytedeco.javacv.FFmpegFrameGrabber.Exception) {
                                            AppDebug.log("Grabber exception (unicast): ${e.message}")
                                            break
                                        }
                                        if (!clientAlive.get()) break
                                        if (frame != null) {
                                            silenceTimeline.onAudio(System.nanoTime())
                                            processGrabberFrame(frame, chunkArray, byteBuffer, maxShortsPerPacket, packetTimeline, onAudioFrame) { bytesToSend ->
                                                packetTimeline.stampPcm(packetArray, bytesToSend)
                                                byteBuffer.array().copyInto(packetArray, AUDIO_HEADER_SIZE, 0, bytesToSend)
                                                val totalBytes = AUDIO_HEADER_SIZE + bytesToSend
                                                val outBytes = frameForSend(packetArray, totalBytes)
                                                val packet = buildPacket { writeFully(outBytes, 0, outBytes.size) }
                                                val sent = try {
                                                    socket.send(Datagram(packet, clientAddress))
                                                    WfasStats.add(WfasStats.Cat.AUDIO, totalBytes)
                                                    if (linkDown) {
                                                        linkDown = false
                                                        onStatusUpdate("status_link_restored", arrayOf<Any>())
                                                    }
                                                    failedSends = 0
                                                    true
                                                } catch (cancelled: CancellationException) {
                                                    throw cancelled
                                                } catch (e: Exception) {
                                                    if (TransientSendError.isTransient(e)) {
                                                        failedSends++
                                                        if (!linkDown) {
                                                            linkDown = true
                                                            onStatusUpdate("status_link_lost_waiting", arrayOf<Any>())
                                                        }
                                                        AppDebug.log("[SERVER][UNICAST] transient send failure #$failedSends: ${e.message}")
                                                        delay(500)
                                                        false
                                                    } else {
                                                        clientAlive.set(false)
                                                        false
                                                    }
                                                }
                                                if (sent && clientAlive.get() &&
                                                    (aacPcmQueue != null || opusPcmQueue != null || rtpPcmQueue != null || dlnaManager != null || snapcastManager != null))
                                                    distributeToSidecars(byteBuffer.array().copyOf(bytesToSend))
                                                sent
                                            }
                                        } else {
                                            // The grabber reports a stalled source as null; keep the session
                                            // alive with a payload-less silence header instead of idling.
                                            writeLegacySilenceHeader(packetArray, packetTimeline, silenceTimeline.onSilence(System.nanoTime()))
                                            val outBytes = frameForSend(packetArray, AUDIO_HEADER_SIZE)
                                            val packet = buildPacket { writeFully(outBytes, 0, outBytes.size) }
                                            try {
                                                socket.send(Datagram(packet, clientAddress))
                                                WfasStats.add(WfasStats.Cat.SILENCE, AUDIO_HEADER_SIZE)
                                                if (linkDown) {
                                                    linkDown = false
                                                    onStatusUpdate("status_link_restored", arrayOf<Any>())
                                                }
                                                failedSends = 0
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (e: Exception) {
                                                if (TransientSendError.isTransient(e)) {
                                                    failedSends++
                                                    if (!linkDown) {
                                                        linkDown = true
                                                        onStatusUpdate("status_link_lost_waiting", arrayOf<Any>())
                                                    }
                                                    AppDebug.log("[SERVER][UNICAST] transient silence send failure #$failedSends: ${e.message}")
                                                    delay(500)
                                                } else clientAlive.set(false)
                                            }
                                            delay(10)
                                        }
                                    }
                                }
                            } finally {
                                unicastPeerConnected.value = false
                                sessionEncryptedLive.value = false
                                withContext(NonCancellable) {
                                    clientByeReceiverJob.cancelAndJoin()
                                    pingJob.cancelAndJoin()
                                }
                                if (clientAlive.get()) {
                                    withContext(NonCancellable) {
                                        runCatching {
                                            socket.send(Datagram(buildPacket { writeText("BYE") }, clientAddress))
                                            WfasStats.add(WfasStats.Cat.BYE, 3)
                                            AppDebug.log("---Sent BYE to $clientAddress ---")
                                        }
                                    }
                                }
                                if (useNativeEngine) {
                                    val engineToStop = serverEngine
                                    serverEngine = null
                                    runCatching { engineToStop?.stop() }
                                } else {
                                    val grabberToStop = serverGrabber
                                    serverGrabber = null
                                    runCatching { grabberToStop?.stop() }
                                    runCatching { grabberToStop?.release() }
                                }
                            }
                            if (!clientAlive.get() && isActive) {
                                onStatusUpdate("status_client_disconnected", emptyArray())
                                if (!persist) break
                                // --persist: e' finita la sessione, non il server. Azzero
                                // lo stato del peer appena uscito e torno in cima al loop,
                                // che ricomincia ad annunciarsi e riapre le porte al
                                // prossimo client sullo stesso socket.
                                pendCnonce   = ""
                                pendSnonce   = ""
                                challenges.clear()
                                micRecvDir   = null
                                micRecvWin   = WfasCrypto.ReplayWindow()
                                activePeerIp = null
                                AppDebug.log("[SERVER][UNICAST] persist: session over, waiting for the next client")
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                if (t !is CancellationException) {
                    println("=== CRITICAL SERVER ERROR ===")
                    t.printStackTrace()
                    onStatusUpdate("status_error_server", arrayOf(t.message ?: t.toString()))
                }
            } finally {
                unicastPeerConnected.value = false
                sessionEncryptedLive.value = false
                activePeerIp = null
                micRecvDir = null
                micRecvWin = WfasCrypto.ReplayWindow()
                withContext(NonCancellable) { ownedMicReceiver?.cancelAndJoin() }
                if (micReceiverJob === ownedMicReceiver) micReceiverJob = null
                stopAnnouncingPresence()
                if (useNativeEngine) {
                    val engineToStop = serverEngine
                    serverEngine = null
                    runCatching { engineToStop?.stop() }
                } else {
                    val grabberToStop = serverGrabber
                    serverGrabber = null
                    runCatching { grabberToStop?.stop() }
                    runCatching { grabberToStop?.release() }
                    restoreLinuxAudioRouting()
                }
            }
        }
    }

    // ── Client ─────────────────────────────────────────────────────────────────
    fun launchClientInstance(
        audioSettings: AudioSettings_V1,
        serverInfo: ServerInfo,
        selectedMixerInfo: Mixer.Info,
        sendMicrophone: Boolean,
        micInputMixerInfo: Mixer.Info?,
        micPort: Int,
        connectionSoundEnabled: Boolean = true,
        disconnectionSoundEnabled: Boolean = true,
        onAudioFrame: ((ShortArray) -> Unit)? = null,
        outputMixerProvider: (() -> Mixer.Info?)? = null,
        onStatusUpdate: (key: String, args: Array<out Any>) -> Unit
    ) {
        val caps = serverInfo.capabilities
        if (caps != null && StreamingProtocol.WFAS !in caps.protocols &&
            StreamingProtocol.HTTP in caps.protocols && caps.httpPort != null) {
            val url = "http://${NetAddr.hostPort(serverInfo.ip, caps.httpPort)}"
            onStatusUpdate("status_opening_browser", arrayOf(url))
            runCatching { openUrl(url) }.onFailure {
                onStatusUpdate("status_error_client", arrayOf(it.message ?: "browser unavailable"))
            }
            return
        }
        // Compatibility entry point for CLI/single-source callers. Additional GUI
        // sources use addReceiverSource and do not replace any existing session.
        streamingJob = scope.launch {
            val id = addReceiverSource(audioSettings, serverInfo, selectedMixerInfo,
                sendMicrophone, micInputMixerInfo, micPort, connectionSoundEnabled,
                onAudioFrame, onStatusUpdate, outputMixerProvider)
            try { awaitCancellation() } finally { DesktopReceiverSessions.remove(id) }
        }
    }

    fun addReceiverSource(
        audioSettings: AudioSettings_V1,
        serverInfo: ServerInfo,
        selectedMixerInfo: Mixer.Info,
        sendMicrophone: Boolean = false,
        micInputMixerInfo: Mixer.Info? = null,
        micPort: Int = 9092,
        connectionSoundEnabled: Boolean = true,
        onAudioFrame: ((ShortArray) -> Unit)? = null,
        onStatusUpdate: (String, Array<out Any>) -> Unit = { _, _ -> },
        outputMixerProvider: (() -> Mixer.Info?)? = null
    ): String {
        val request = buildReceiverRequest(audioSettings, serverInfo, selectedMixerInfo, sendMicrophone,
            micInputMixerInfo, micPort, connectionSoundEnabled, onAudioFrame, onStatusUpdate,
            outputMixerProvider = outputMixerProvider)
        val id = DesktopReceiverSessions.connect(request)
        try {
            rememberReceiverSource(id, serverInfo, audioSettings, clientPresharedKey)
        } catch (failure: Exception) {
            DesktopReceiverSessions.remove(id)
            throw failure
        }
        return id
    }
    internal fun receiverCredential(id: String): String? = DesktopReceiverSessions.credentialForPersistence(id)

    /**
     * [selectedMixerInfo] is resolved lazily so a remembered source can be restored at
     * startup, before the user has picked an output device.
     */
    private fun buildReceiverRequest(
        audioSettings: AudioSettings_V1,
        serverInfo: ServerInfo,
        selectedMixerInfo: Mixer.Info?,
        sendMicrophone: Boolean,
        micInputMixerInfo: Mixer.Info?,
        micPort: Int,
        connectionSoundEnabled: Boolean,
        onAudioFrame: ((ShortArray) -> Unit)?,
        onStatusUpdate: (String, Array<out Any>) -> Unit,
        key: String = clientPresharedKey,
        fromInvite: Boolean = clientKeyFromInvite,
        expectedEpoch: Long? = expectedMcastEpoch,
        outputMixerProvider: (() -> Mixer.Info?)? = null
    ): DesktopReceiverRequest {
        val settings = SettingsRepository.loadSettings().app
        return DesktopReceiverRequest(
            peer = serverInfo, audio = audioSettings,
            openOutput = { format, preroll ->
                val mixer = desktopReceiverOutput(selectedMixerInfo, outputMixerProvider) {
                    findAvailableOutputMixers().firstOrNull()
                }
                prepareSourceDataLine(mixer, format, preroll, allowDefaultFallback = outputMixerProvider == null)
                    ?: throw PlaybackOutputFailure("Cannot open selected audio output")
            },
            interfaceName = settings.networkInterface,
            key = key, fromInvite = fromInvite, expectedEpoch = expectedEpoch,
            connectionSoundEnabled = connectionSoundEnabled,
            remoteVolumeEnabled = settings.remoteVolumeSyncEnabled,
            requestKey = { wrong -> if (clientKeyPromptAllowed) onKeyRequest?.invoke(wrong) else null },
            onConnectionCue = { playConnectionSound() },
            onProtocolMismatch = { signalProtocolMismatch(it, PeerRole.SENDER) },
            onStatus = onStatusUpdate, onFrame = onAudioFrame,
            masterGain = { currentClientVolume },
            onTargetChanged = {},
            onMicrophoneReady = { direction ->
                // The microphone is never opened before a validated HELLO_ACK.
                // One desktop microphone is shared explicitly, never silently
                // retargeted by an unrelated receiver's retry.
                if (sendMicrophone && micInputMixerInfo != null &&
                    serverInfo.capabilities?.acceptsClientMic != false && micReceiverJob?.isActive != true) {
                    scope.launchMicSender(audioSettings, serverInfo, micInputMixerInfo, micPort, direction)
                        .also { micReceiverJob = it }
                } else null
            })
    }

    private fun rememberReceiverSource(id: String, serverInfo: ServerInfo, audio: AudioSettings_V1, key: String) {
        val state = DesktopReceiverSessions.sources.value.firstOrNull { it.id == id }
        ReceiverSourceStore.remember(
            RememberedReceiverSource(
                id = id, ip = serverInfo.ip, port = serverInfo.port, multicast = serverInfo.isMulticast,
                gain = state?.gain ?: 1f, muted = state?.muted ?: false,
                latencyMs = state?.manualLatencyMs ?: audio.latencyMs.coerceIn(20, 400),
                adaptive = state?.adaptive ?: audio.adaptiveLatency,
                paused = false, serverAudio = serverInfo.serverAudioSettings,
                capabilities = serverInfo.capabilities, viaUsb = serverInfo.viaUsb,
                fromInvite = clientKeyFromInvite, expectedEpoch = expectedMcastEpoch
            ), key = key.takeIf { it.isNotBlank() }
        )
    }

    /**
     * Rebuilds the remembered receiver list as paused sources: they reappear in the UI
     * with their own gain and latency, but nothing opens the audio device until the user
     * retries. Returns the number of entries restored.
     */
    fun restoreRememberedSources(selectedMixerInfo: Mixer.Info? = null,
                                 outputMixerProvider: (() -> Mixer.Info?)? = null): Int {
        val settings = SettingsRepository.loadSettings()
        var restored = 0
        for (source in ReceiverSourceStore.load()) {
            if (DesktopReceiverSessions.sources.value.size >= DesktopReceiverSessions.MAX_SOURCES) break
            val audio = settings.audio.copy(latencyMs = source.latencyMs, adaptiveLatency = source.adaptive)
            val request = buildReceiverRequest(
                audioSettings = audio,
                serverInfo = source.toServerInfo(settings.audio),
                selectedMixerInfo = selectedMixerInfo,
                sendMicrophone = false,
                micInputMixerInfo = null,
                micPort = settings.micPort.toIntOrNull() ?: 9092,
                connectionSoundEnabled = settings.app.connectionSoundEnabled,
                onAudioFrame = null,
                onStatusUpdate = { _, _ -> },
                key = ReceiverSourceStore.loadKey(source).orEmpty(),
                fromInvite = source.fromInvite,
                expectedEpoch = source.expectedEpoch,
                outputMixerProvider = outputMixerProvider
            )
            DesktopReceiverSessions.restore(request, id = source.id, gain = source.gain,
                muted = source.muted, adaptive = source.adaptive, manualMs = source.latencyMs, paused = true)
            restored++
        }
        return restored
    }

    private val MAX_CONCEAL_FRAMES = 9600L

    private class ClientPlaybackState {
        var expectedSeq = -1
        var expectedSamplePos = -1L
        var lastGoodPcm: ByteArray? = null
        var inSilence = false
        var concealTail: ByteArray? = null
    }

    // Keep the existing transport/test API while the shared receiver owns playout.
    internal class JitterAudioPlayer(
        line: SourceDataLine,
        sampleRate: Int,
        channels: Int,
        prebufferMs: Int = 120,
        maxBufferMs: Int = 400,
        health: PlaybackHealth? = null,
        lineFactory: (() -> SourceDataLine)? = null,
        onRecover: () -> Unit = {},
        onRecovered: () -> Unit = {},
        watchdogTimeoutMs: Long = 5000
    ) : DesktopJitterAudioPlayer(
        line, sampleRate, channels, prebufferMs, maxBufferMs, health, lineFactory,
        onRecover, onRecovered, watchdogTimeoutMs,
        currentVolume = { currentClientVolume },
        onTargetChanged = { playbackTargetMs.value = it }
    )
    private const val PLC_XFADE_FRAMES = 96

    private fun bestContinuationFrame(ref: ShortArray, channels: Int, refFrames: Int): Int {
        if (refFrames < 4) return 0
        val lastBase = (refFrames - 1) * channels
        val prevBase = (refFrames - 2) * channels
        var best = Long.MAX_VALUE
        var bestFrame = 0
        for (f in 1 until refFrames - 2) {
            var cost = 0L
            for (c in 0 until channels) {
                val v = ref[f * channels + c].toLong() - ref[lastBase + c].toLong()
                val s = (ref[(f + 1) * channels + c] - ref[f * channels + c]).toLong() -
                        (ref[lastBase + c] - ref[prevBase + c]).toLong()
                cost += v * v + 4L * s * s
            }
            if (cost < best) { best = cost; bestFrame = f }
        }
        return bestFrame
    }

    private fun buildConcealment(ref: ByteArray, refUsable: Int, totalFrames: Int, frameBytes: Int): ByteArray {
        val channels = (frameBytes / 2).coerceAtLeast(1)
        val refFrames = refUsable / frameBytes
        val src = ShortArray(refUsable / 2)
        java.nio.ByteBuffer.wrap(ref, 0, refUsable)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(src)

        val offset = bestContinuationFrame(src, channels, refFrames)
        val period = (2 * (refFrames - 1)).coerceAtLeast(1)
        val outFrames = totalFrames + PLC_XFADE_FRAMES
        val hold = totalFrames
        val out = ByteArray(outFrames * frameBytes)
        val dst = java.nio.ByteBuffer.wrap(out)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()

        for (i in 0 until outFrames) {
            val p = (offset + i) % period
            val idx = if (p < refFrames) p else period - p
            val gain = if (i < hold) 1f
            else (1f - (i - hold + 1).toFloat() / (outFrames - hold)).coerceAtLeast(0f)
            val srcBase = idx * channels
            val dstBase = i * channels
            for (c in 0 until channels) {
                val s = (src[srcBase + c] * gain).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                dst.put(dstBase + c, s.toShort())
            }
        }
        return out
    }

    private fun concealGap(
        player: JitterAudioPlayer,
        state: ClientPlaybackState,
        totalBytes: Int,
        frameBytes: Int
    ) {
        val total = totalBytes - (totalBytes % frameBytes)
        if (total <= 0) return

        val ref = state.lastGoodPcm
        val refUsable = if (ref == null) 0 else ref.size - (ref.size % frameBytes)
        if (ref == null || refUsable < frameBytes * 4) {
            player.submitSilence(total)
            state.concealTail = null
            return
        }

        val block = buildConcealment(ref, refUsable, total / frameBytes, frameBytes)
        player.submit(block, 0, total)
        state.concealTail = block.copyOfRange(total, block.size)
    }

    private fun crossfadeIntoReal(real: ByteArray, off: Int, len: Int, tail: ByteArray, frameBytes: Int): ByteArray {
        val out = real.copyOfRange(off, off + len)
        val channels = (frameBytes / 2).coerceAtLeast(1)
        val n = minOf(tail.size / frameBytes, len / frameBytes)
        if (n <= 0) return out
        val tb = java.nio.ByteBuffer.wrap(tail).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val ob = java.nio.ByteBuffer.wrap(out).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        for (i in 0 until n) {
            val t = (i + 1).toFloat() / (n + 1)
            for (c in 0 until channels) {
                val a = tb.get(i * channels + c).toFloat()
                val b = ob.get(i * channels + c).toFloat()
                val m = (a * (1f - t) + b * t).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                ob.put(i * channels + c, m.toShort())
            }
        }
        return out
    }

    private fun handleAudioPacket(
        player: JitterAudioPlayer,
        data: ByteArray,
        len: Int,
        channels: Int,
        state: ClientPlaybackState,
        onAudioFrame: ((ShortArray) -> Unit)?
    ) {
        if (len < AUDIO_HEADER_SIZE) return
        val frameBytes = (channels * 2).coerceAtLeast(2)
        val silence = (data[3].toInt() and 0x01) != 0
        val seq = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
        val samplePos = ((data[6].toInt() and 0xFF).toLong() shl 24) or
                ((data[7].toInt() and 0xFF).toLong() shl 16) or
                ((data[8].toInt() and 0xFF).toLong() shl 8) or
                (data[9].toInt() and 0xFF).toLong()

        LinkMetrics.onPacket(seq, samplePos)

        var pcmLen = len - AUDIO_HEADER_SIZE
        if (pcmLen > 0) pcmLen -= pcmLen % frameBytes

        if (state.expectedSeq >= 0) {
            val delta = (seq - state.expectedSeq) and 0xFFFF
            when {
                delta == 0 -> { }
                delta < 0x8000 -> {
                    val exactBytes = if (state.expectedSamplePos in 0L until samplePos) {
                        val frames = samplePos - state.expectedSamplePos
                        if (frames in 1L..MAX_CONCEAL_FRAMES) frames.toInt() * frameBytes else -1
                    } else -1
                    val concealBytes = if (exactBytes >= 0) exactBytes
                    else state.lastGoodPcm?.let { delta.coerceAtMost(8) * it.size } ?: 0
                    concealGap(player, state, concealBytes.coerceAtMost(MAX_CONCEAL_FRAMES.toInt() * frameBytes), frameBytes)
                }
                else -> return
            }
        }

        state.expectedSeq = (seq + 1) and 0xFFFF
        state.expectedSamplePos = samplePos + (if (pcmLen > 0) (pcmLen / frameBytes).toLong() else 0L)

        player.noteAudioFlow(!silence && pcmLen > 0)

        if (silence || pcmLen <= 0) {
            val n = state.lastGoodPcm?.size ?: (frameBytes * 480)
            if (state.inSilence) {
                state.concealTail = null
                player.submitSilence(n)
            } else {
                state.inSilence = true
                concealGap(player, state, n, frameBytes)
            }
        } else {
            state.inSilence = false
            val tail = state.concealTail
            if (tail != null) {
                state.concealTail = null
                val merged = crossfadeIntoReal(data, AUDIO_HEADER_SIZE, pcmLen, tail, frameBytes)
                player.submit(merged, 0, merged.size)
            } else {
                player.submit(data, AUDIO_HEADER_SIZE, pcmLen)
            }
            val lg = state.lastGoodPcm
            if (lg == null || lg.size != pcmLen) state.lastGoodPcm = ByteArray(pcmLen)
            System.arraycopy(data, AUDIO_HEADER_SIZE, state.lastGoodPcm!!, 0, pcmLen)
            if (onAudioFrame != null && pcmLen >= 2) {
                val shorts = ShortArray(pcmLen / 2)
                java.nio.ByteBuffer.wrap(data, AUDIO_HEADER_SIZE, pcmLen)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                onAudioFrame.invoke(shorts)
            }
        }
    }

    /** La pipeline e' PCM 16 bit: altre profondita' non sono riproducibili. */
    private fun isPlayableFormat(s: AudioSettings_V1): Boolean =
        s.bitDepth == 16 &&
                s.channels in 1..2 &&
                s.sampleRate.toInt() in 4000..192000

    private fun describeFormat(s: AudioSettings_V1): String =
        "${s.sampleRate.toInt()} Hz, " +
                (if (s.channels == 1) "mono" else "stereo") + ", ${s.bitDepth} bit"

    private fun resolveMixer(info: Mixer.Info): Mixer? {
        runCatching { return AudioSystem.getMixer(info) }
        val current = runCatching { AudioSystem.getMixerInfo() }.getOrNull() ?: return null
        val again = current.firstOrNull { it == info }
            ?: current.firstOrNull { it.name == info.name }
            ?: current.firstOrNull { it.name.equals(info.name, ignoreCase = true) }
        if (again == null) {
            AppDebug.log("[CLIENT] mixer '${info.name}' non piu' presente fra i dispositivi correnti")
            return null
        }
        AppDebug.log("[CLIENT] mixer '${info.name}' risolto di nuovo dalla lista aggiornata")
        return runCatching { AudioSystem.getMixer(again) }.getOrNull()
    }

    /**
     * Uscita audio per il ricevitore RTP.
     *
     * Riusa la stessa linea e lo stesso jitter buffer del client WFAS: un
     * flusso RTP ha esattamente gli stessi problemi di rete (ritardo variabile,
     * pacchetti persi), quindi non ha senso una seconda implementazione che
     * invecchia per conto suo.
     *
     * La frequenza e i canali arrivano dallo stream, non dalle impostazioni:
     * un SDP puo' descrivere qualcosa di diverso da quello che l'app usa per
     * trasmettere, e la linea va aperta su quello che arriva davvero.
     */
    fun openPcmPlaybackSink(
        mixerInfo: Mixer.Info?,
        sampleRate: Int,
        channels: Int,
        latencyMs: Int
    ): PcmPlaybackSink? {
        val fmt = AudioSettings_V1(
            sampleRate = sampleRate.toFloat(),
            bitDepth   = 16,
            channels   = channels,
            bufferSize = (sampleRate * channels * 2 * 10 / 1000)
        )
        val line = (mixerInfo?.let { prepareSourceDataLine(it, fmt) }
            ?: runCatching {
                AudioSystem.getSourceDataLine(fmt.toAudioFormat()).also { it.open(fmt.toAudioFormat()); it.start() }
            }.getOrNull()) ?: run {
            AppDebug.log("[RTP-RX] nessuna linea di uscita per ${sampleRate}Hz ${channels}ch")
            return null
        }

        val prebuffer = latencyMs.coerceIn(40, 1000)
        val player = JitterAudioPlayer(
            line, sampleRate, channels,
            prebufferMs = prebuffer,
            maxBufferMs = prebuffer + 280
        ).also { it.start() }

        // Stesso trattamento dei buchi del client WFAS: senza mascheratura ogni
        // pacchetto perso e' un salto secco nella forma d'onda, e si sente come
        // un click. Lo stato serve a ricordare l'ultimo audio buono e la coda
        // da dissolvere sul primo pacchetto vero che torna.
        val frameBytes = (channels * 2).coerceAtLeast(2)
        val state = ClientPlaybackState()
        val maxConcealBytes = MAX_CONCEAL_FRAMES.toInt() * frameBytes

        return object : PcmPlaybackSink {
            override fun submit(pcmLittleEndian: ByteArray) {
                val len = pcmLittleEndian.size - (pcmLittleEndian.size % frameBytes)
                if (len <= 0) return
                val tail = state.concealTail
                if (tail != null) {
                    state.concealTail = null
                    val merged = crossfadeIntoReal(pcmLittleEndian, 0, len, tail, frameBytes)
                    player.submit(merged, 0, merged.size)
                } else {
                    player.submit(pcmLittleEndian, 0, len)
                }
                val lg = state.lastGoodPcm
                if (lg == null || lg.size != len) state.lastGoodPcm = ByteArray(len)
                System.arraycopy(pcmLittleEndian, 0, state.lastGoodPcm!!, 0, len)
            }

            override fun conceal(approxBytes: Int) {
                val n = approxBytes.coerceIn(0, maxConcealBytes)
                if (n >= frameBytes) concealGap(player, state, n, frameBytes)
            }

            override fun bufferedMs(): Int = player.bufferedMs()

            override fun close() { runCatching { player.stop() } }
        }
    }

    private fun prepareSourceDataLine(mixerInfo: Mixer.Info, audioSettings: AudioSettings_V1,
                                      deviceBufferMs: Int = 80, allowDefaultFallback: Boolean = true): SourceDataLine? {
        val format       = audioSettings.toAudioFormat()
        val dataLineInfo = DataLine.Info(SourceDataLine::class.java, format)

        AppDebug.log("[CLIENT] prepareSourceDataLine: mixer='${mixerInfo.name}' format=$format sr=${audioSettings.sampleRate} ch=${audioSettings.channels} bd=${audioSettings.bitDepth}")

        val frameSize = format.frameSize
        val targetLatencyMs = deviceBufferMs
        val targetBytes = ((audioSettings.sampleRate.toInt() * targetLatencyMs / 1000) * frameSize)
            .let { it - (it % frameSize) }
            .coerceAtLeast(frameSize * 256)

        val mixer = resolveMixer(mixerInfo)
        if (mixer != null && !runCatching { mixer.isLineSupported(dataLineInfo) }.getOrDefault(false)) {
            AppDebug.log("[CLIENT] mixer '${mixerInfo.name}' non dichiara $format: provo comunque ad aprirlo")
        }
        val line = when {
            mixer == null -> {
                if (allowDefaultFallback) {
                    AppDebug.log("[CLIENT] ripiego sull'uscita predefinita di sistema")
                    runCatching { AudioSystem.getSourceDataLine(format) }.getOrNull()
                } else null
            }
            else -> runCatching { mixer.getLine(dataLineInfo) as SourceDataLine }
                .onFailure { AppDebug.log("[CLIENT] getLine su '${mixerInfo.name}' fallito: ${it.message}") }
                .getOrNull()
                ?: runCatching { AudioSystem.getSourceDataLine(format, mixerInfo) }.getOrNull()
                ?: runCatching { if (allowDefaultFallback) AudioSystem.getSourceDataLine(format) else null }
                    .onSuccess { if (it != null) AppDebug.log("[CLIENT] ripiego sull'uscita predefinita di sistema") }
                    .getOrNull()
        }
        if (line == null) {
            AppDebug.log("[CLIENT] ERROR: nessuna linea di uscita utilizzabile per $format")
            return null
        }

        AppDebug.log("[CLIENT] SourceDataLine: frameSize=$frameSize targetLatencyMs=${targetLatencyMs}ms targetBufferBytes=$targetBytes")
        return runCatching { line.also { sdl ->
            sdl.open(format, targetBytes)
            AppDebug.log("[CLIENT] SourceDataLine opened: bufferSize=${sdl.bufferSize} format=${sdl.format} realLatency=${sdl.bufferSize * 1000 / (audioSettings.sampleRate.toInt() * frameSize)}ms")
            DesktopDiagnostics.event("PLAYOUT_OPEN", "rate=${audioSettings.sampleRate.toInt()} channels=${audioSettings.channels} " +
                "requestedCapacityMs=$deviceBufferMs actualCapacityBytes=${sdl.bufferSize}")

            val gainInfo = sdl.controls
                .filterIsInstance<javax.sound.sampled.FloatControl>()
                .find { it.type == javax.sound.sampled.FloatControl.Type.MASTER_GAIN }
            if (gainInfo != null) {
                AppDebug.log("[CLIENT] SDL MASTER_GAIN: ${gainInfo.value} dB (min=${gainInfo.minimum} max=${gainInfo.maximum})")
            } else {
                AppDebug.log("[CLIENT] SDL MASTER_GAIN: control not available")
            }
            val muteInfo = sdl.controls
                .filterIsInstance<javax.sound.sampled.BooleanControl>()
                .find { it.type == javax.sound.sampled.BooleanControl.Type.MUTE }
            if (muteInfo != null) {
                AppDebug.log("[CLIENT] SDL MUTE: ${muteInfo.value}")
            } else {
                AppDebug.log("[CLIENT] SDL MUTE: control not available")
            }

            sdl.start()
        } }.onFailure {
            AppDebug.log("[CLIENT] apertura della linea fallita: ${it.message}")
            runCatching { line.close() }
        }.getOrNull()
    }

    // ── Stop / terminate ───────────────────────────────────────────────────────
    fun requestStopCurrentStream(retainReceiverSources: Boolean = false): Deferred<Unit> {
        val myGen = lifecycleGeneration.incrementAndGet()
        return scope.async {
            lifecycleMutex.withLock {
                if (myGen < lifecycleGeneration.get()) return@withLock
                stopCurrentStreamLocked(retainReceiverSources)
            }
        }
    }

    suspend fun stopCurrentStream(retainReceiverSources: Boolean = false) {
        requestStopCurrentStream(retainReceiverSources).await()
    }

    private suspend fun stopCurrentStreamLocked(retainReceiverSources: Boolean = false) {
        if (retainReceiverSources) DesktopReceiverSessions.pauseAllAndJoin()
        else DesktopReceiverSessions.stopAllAndJoin()
        stopAnnouncingPresence()

        unicastPeerConnected.value = false
        sessionEncryptedLive.value = false
        mcastRekey = null
        mcastSession.value = null
        activePeerIp = null

        runCatching { dlnaManager?.stop() }
        dlnaManager = null

        runCatching { snapcastManager?.stop() }
        snapcastManager = null

        httpServerJob?.cancelAndJoin();  httpServerJob   = null
        rtpJob?.cancelAndJoin();         rtpJob          = null
        localMicMixJob?.cancelAndJoin(); localMicMixJob  = null
        runCatching { serverEngine?.setMicMixEnabled(false) }

        val streaming = streamingJob
        streamingJob = null
        if (streaming != null) {
            streaming.cancel()
            val stoppedCleanly = withTimeoutOrNull(1500) { streaming.join() } != null
            if (!stoppedCleanly) {
                AppDebug.log("[SERVER] loop di streaming bloccato sulla cattura: fermo la sorgente per sbloccarlo")
                runCatching { serverEngine?.stop() }
                runCatching { serverGrabber?.stop() }
                if (withTimeoutOrNull(3000) { streaming.join() } == null) {
                    AppDebug.log("[SERVER] il loop di streaming non ha risposto entro il timeout")
                    streamingJob = streaming
                    throw PlaybackOutputFailure("Capture did not stop; refusing a new audio owner")
                }
            }
        }
        runCatching { serverEngine?.stop() }
        runCatching { serverGrabber?.stop() }
        micReceiverJob?.cancelAndJoin(); micReceiverJob  = null
        broadcastingJob?.cancelAndJoin(); broadcastingJob = null
        softwareMicMixer.enable(false)
        softwareMicMixer.volume = 1.0f
        isMicMuted.value = false
        // Failed/timed-out starts retain a process-global native lease even
        // before serverEngine is assigned. A role change must await that lease.
        AudioEngine.stopPendingWindowsCapture()
        CaptureMonitor.clear()

        val savedVol = macOriginalVolume
        if (savedVol >= 0f) {
            macOriginalVolume = -1f
            val isMacOS = System.getProperty("os.name").lowercase().let { it.contains("mac") || it.contains("darwin") }
            if (isMacOS && AudioEngine.loadLibrary()) {
                AudioEngine().setSystemVolume(savedVol)
                AppDebug.log("[Server] macOS: volume restored to $savedVol")
            }
        }
    }

    fun terminateAllServices() {
        scope.cancel()
        runCatching { selectorManager.close() }
    }

    fun playConnectionSound() {
        scope.launch(Dispatchers.IO) {
            try {
                val stream = object {}.javaClass.getResourceAsStream("/raw/connection_sound.wav")
                if (stream != null) {
                    stream.use {
                        val audioStream = AudioSystem.getAudioInputStream(it.buffered())
                        val format = audioStream.format
                        val info = DataLine.Info(SourceDataLine::class.java, format)
                        val line = AudioSystem.getLine(info) as SourceDataLine
                        line.open(format)
                        line.start()
                        val buf = ByteArray(4096)
                        var n: Int
                        while (audioStream.read(buf, 0, buf.size).also { n = it } != -1) line.write(buf, 0, n)
                        line.drain()
                        line.stop()
                        line.close()
                    }
                    return@launch
                }
                val sampleRate = 44100f
                val durationMs = 120
                val samples = (sampleRate * durationMs / 1000).toInt()
                val buf = ByteArray(samples * 2)
                for (i in 0 until samples) {
                    val t = i / sampleRate
                    val env = if (i < samples / 4) i.toDouble() / (samples / 4)
                    else 1.0 - (i - samples / 4).toDouble() / (samples * 3 / 4)
                    val sample = ((Math.sin(2 * Math.PI * 880.0 * t) * 0.5 + Math.sin(2 * Math.PI * 1320.0 * t) * 0.5) * env * Short.MAX_VALUE).toInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    buf[i * 2] = (sample and 0xFF).toByte()
                    buf[i * 2 + 1] = (sample shr 8).toByte()
                }
                val format = AudioFormat(sampleRate, 16, 1, true, false)
                val info = DataLine.Info(SourceDataLine::class.java, format)
                val line = AudioSystem.getLine(info) as SourceDataLine
                line.open(format)
                line.start()
                line.write(buf, 0, buf.size)
                line.drain()
                line.stop()
                line.close()
            } catch (_: Exception) {}
        }
    }

    fun playDisconnectionSound() {
        scope.launch(Dispatchers.IO) {
            try {
                val stream = object {}.javaClass.getResourceAsStream("/raw/disconnection_sound.wav")
                if (stream != null) {
                    stream.use {
                        val audioStream = AudioSystem.getAudioInputStream(it.buffered())
                        val format = audioStream.format
                        val info = DataLine.Info(SourceDataLine::class.java, format)
                        val line = AudioSystem.getLine(info) as SourceDataLine
                        line.open(format)
                        line.start()
                        val buf = ByteArray(4096)
                        var n: Int
                        while (audioStream.read(buf, 0, buf.size).also { n = it } != -1) line.write(buf, 0, n)
                        line.drain()
                        line.stop()
                        line.close()
                    }
                    return@launch
                }
                val sampleRate = 44100f
                val durationMs = 120
                val samples = (sampleRate * durationMs / 1000).toInt()
                val buf = ByteArray(samples * 2)
                for (i in 0 until samples) {
                    val t = i / sampleRate
                    val env = if (i < samples / 4) i.toDouble() / (samples / 4)
                    else 1.0 - (i - samples / 4).toDouble() / (samples * 3 / 4)
                    val sample = ((Math.sin(2 * Math.PI * 660.0 * t) * 0.5 + Math.sin(2 * Math.PI * 440.0 * t) * 0.5) * env * Short.MAX_VALUE).toInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    buf[i * 2] = (sample and 0xFF).toByte()
                    buf[i * 2 + 1] = (sample shr 8).toByte()
                }
                val format = AudioFormat(sampleRate, 16, 1, true, false)
                val info = DataLine.Info(SourceDataLine::class.java, format)
                val line = AudioSystem.getLine(info) as SourceDataLine
                line.open(format)
                line.start()
                line.write(buf, 0, buf.size)
                line.drain()
                line.stop()
                line.close()
            } catch (_: Exception) {}
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Application entry point
// ─────────────────────────────────────────────────────────────────────────────

private fun loadTrayIconImage(): BufferedImage? {
    val loaders = listOfNotNull(
        Thread.currentThread().contextClassLoader,
        Any::class.java.classLoader,
        ClassLoader.getSystemClassLoader()
    )
    for (loader in loaders) {
        for (path in listOf("app_icon.png", "/app_icon.png")) {
            val resource = if (path.startsWith("/")) Any::class.java.getResourceAsStream(path)
            else loader.getResourceAsStream(path)
            if (resource != null) {
                val image = runCatching { resource.use { ImageIO.read(it) } }.getOrNull()
                if (image != null) return image
            }
        }
    }
    return null
}

private fun loadTrayIconPainter(): Painter {
    val loaders = listOfNotNull(
        Thread.currentThread().contextClassLoader,
        Any::class.java.classLoader,
        ClassLoader.getSystemClassLoader()
    )
    val candidates = listOf("app_icon.png", "/app_icon.png")
    for (loader in loaders) {
        for (path in candidates) {
            val resource = if (path.startsWith("/")) Any::class.java.getResourceAsStream(path)
            else loader.getResourceAsStream(path)
            if (resource != null) {
                return runCatching {
                    resource.use { input ->
                        val image = ImageIO.read(input)
                        if (image != null) BitmapPainter(image.toComposeImageBitmap())
                        else null
                    }
                }.getOrNull() ?: continue
            }
        }
    }
    val blank = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
    return BitmapPainter(blank.toComposeImageBitmap())
}

private enum class SetupAction { GUI, DONE, SHOW_HELP, EXIT }

private fun runInteractiveSetup(isHeadless: Boolean): SetupAction {
    val console = System.console() ?: return SetupAction.EXIT
    val ver = displayVersion(runCatching {
        object {}.javaClass.getResourceAsStream("/version.properties")
            ?.bufferedReader()?.lineSequence()
            ?.firstOrNull { it.startsWith("app.version=") }
            ?.removePrefix("app.version=")?.trim()
    }.getOrNull() ?: "")

    println()
    println("  AudioBridge${if (ver.isNotEmpty()) " $ver" else ""}")
    println()

    data class Option(val label: String, val action: SetupAction)
    val options = mutableListOf<Option>()
    if (!isHeadless) options += Option("Open GUI", SetupAction.GUI)
    val alreadyInstalled = CliPathInstaller.isInstalled()
    val pathDetail = if (CliPathInstaller.isWindows) "" else "  (~/.local/bin/wfas + man wfas)"
    options += Option(
        (if (alreadyInstalled) "Reinstall wfas to PATH" else "Add wfas to PATH") + pathDetail,
        SetupAction.DONE
    )
    options += Option("Show help", SetupAction.SHOW_HELP)
    options += Option("Exit",      SetupAction.EXIT)

    options.forEachIndexed { i, o -> println("  ${i + 1}) ${o.label}") }
    println()

    val choice = console.readLine("  Choose [1-${options.size}]: ")
        ?.trim()?.toIntOrNull()?.minus(1) ?: return SetupAction.EXIT
    val selected = options.getOrNull(choice) ?: return SetupAction.EXIT

    if (selected.action == SetupAction.DONE) {
        println()
        when (val result = CliPathInstaller.install()) {
            is CliPathInstaller.InstallResult.Success ->
                println("  ✓  Done.\n     Restart your terminal and run: wfas --help\n     Then try: man wfas")
            is CliPathInstaller.InstallResult.Failure ->
                System.err.println("  ✗  ${result.reason}")
            is CliPathInstaller.InstallResult.TerminalLaunched ->
                println("  ✓  Terminal launched for installation.")
        }
        println()
        return SetupAction.EXIT
    }
    return selected.action
}

fun main(args: Array<String>) {
    System.setOut(java.io.PrintStream(System.out, true, "UTF-8"))
    System.setErr(java.io.PrintStream(System.err, true, "UTF-8"))

    val socketDirProperty = "jdk.net.unixdomain.tmpdir"
    if (System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true) &&
        System.getProperty(socketDirProperty) == null) {
        System.getProperty("compose.application.resources.dir")?.takeIf { it.isNotBlank() }?.let { resourcesDir ->
            val appDir = File(resourcesDir).absoluteFile.parentFile
                ?: error("Cannot locate the bundled app directory from $resourcesDir")
            require(appDir.isDirectory) { "Bundled app directory does not exist: $appDir" }
            // Some Windows TEMP folders accept AF_UNIX bind but reject connect, breaking
            // Selector.open(). Use the bundle for internal wakeup sockets; Java uses TCP
            // if it cannot bind there. Apply before the first networking initialization.
            System.setProperty(socketDirProperty, appDir.path)
        }
    }

    CliPathInstaller.refreshIfOutdated()

    // Un deep link di pairing non e' un argomento CLI: va tolto prima del parsing,
    // altrimenti CliArgs lo tratterebbe come opzione sconosciuta.
    val pairVerbIndex = args.indexOfFirst { it.equals("pair", true) || it.equals("qr", true) }
    val pairSubVerb = (if (pairVerbIndex >= 0) args.getOrNull(pairVerbIndex + 1) else args.firstOrNull())
        .orEmpty().lowercase()
    val offlinePairCommand = pairSubVerb in CliArgs.OFFLINE_PAIR_VERBS
    val explicitPairCommand = pairVerbIndex >= 0 || offlinePairCommand

    val pairingArg = args.firstOrNull { PendingDeepLink.looksLikePairing(it) }
    if (pairingArg != null && !offlinePairCommand) {
        PendingDeepLink.uri = pairingArg
        if (PairIpc.handOffDeepLink(pairingArg)) return
    }
    var cliArgs = CliArgs.parse(
        if (pairingArg != null && !explicitPairCommand) args.filterNot { it === pairingArg }.toTypedArray()
        else args
    )

    cliArgs.configPath?.let { ConfigPaths.overrideConfigFile = java.io.File(it) }

    if (cliArgs.configCmd != null) {
        val code = ConfigCli.run(cliArgs.configCmd, cliArgs.json, cliArgs.reveal)
        kotlin.system.exitProcess(code)
    }

    if (cliArgs.firewallCmd != null) {
        val code = FirewallCli.run(cliArgs.firewallCmd, cliArgs.json)
        kotlin.system.exitProcess(code)
    }

    if (cliArgs.printDevices) {
        kotlin.system.exitProcess(DevicesCli.run(cliArgs.json))
    }

    if (cliArgs.printBareHint) { CliArgs.printBareHint(); return }
    if (cliArgs.printHelp)     { CliArgs.printHelp(cliArgs.helpTopic, cliArgs.json); return }
    if (cliArgs.printVersion)  { CliArgs.printVersion();  return }
    if (cliArgs.printProtocol) { CliArgs.printProtocol(); return }
    if (cliArgs.printLicenses) { CliArgs.printLicenses(); return }
    if (cliArgs.printFred)     { CliArgs.printFred();     return }

    // Le viste e i comandi di 'rtp' e 'snapcast' finiscono qui: solo l'ascolto
    // prosegue verso runCli, che e' l'unico che apre davvero una sessione.
    //
    // Le preferenze di rete pero' valgono anche qui. Saltarle voleva dire che
    // 'wfas snapcast discover' cercava su un'interfaccia scelta con criteri
    // diversi da 'wfas snapcast listen', e che --debug non stampava niente
    // proprio nei comandi in cui serve di piu'.
    if (cliArgs.rtpCmd != null || cliArgs.snapCmd != null) {
        AppDebug.enabled = cliArgs.debug
        val stored = SettingsRepository.loadSettings()
        NetAddr.configureFamily(cliArgs.ipFamily)
        UsbLink.configure(
            cliArgs.usb ?: stored.app.usbModeEnabled,
            cliArgs.usbLatency ?: stored.app.usbLatencyMs,
            cliArgs.usbIface ?: stored.app.usbInterface,
            override = cliArgs.usb != null || cliArgs.usbLatency != null || cliArgs.usbIface != null
        )
    }
    cliArgs.rtpCmd?.let { c ->
        if (c !is RtpCommand.Listen) kotlin.system.exitProcess(RtpCli.run(c, cliArgs))
    }
    cliArgs.snapCmd?.let { c ->
        // Ascolto e mixer riproducono: proseguono verso runCli come una
        // sessione qualunque. Tutto il resto e' solo canale di controllo e
        // finisce qui.
        if (c !is SnapCommand.Listen && c !is SnapCommand.Mixer)
            kotlin.system.exitProcess(SnapcastCli.run(c, cliArgs))
    }

    val pairCmd = cliArgs.pairCmd
    if (pairCmd != null) {
        if (pairCmd is PairCommand.Connect) {
            cliArgs = PairCli.connectArgs(pairCmd.uri, cliArgs)
                ?: kotlin.system.exitProcess(ExitCode.USAGE_ERROR)
        } else {
            kotlin.system.exitProcess(PairCli.run(pairCmd, cliArgs, cliArgs.qrOptions()))
        }
    }

    if (cliArgs.autoCheckUpdate != null) {
        val on = cliArgs.autoCheckUpdate == "on"
        SettingsRepository.setAutoUpdateCheckEnabled(on)
        println(if (on) "Automatic update check enabled." else "Automatic update check disabled.")
        return
    }
    if (cliArgs.checkUpdate) { printUpdateCheck(); return }

    // FIX CRASH: disabilita AVX-512/AVX3 — causa EXCEPTION_ACCESS_VIOLATION in
    // StubRoutines::jlong_disjoint_arraycopy_avx3 durante la copia di buffer audio
    // nativi su alcune CPU Windows con la JVM Temurin 17.

    DesktopDiagnostics.initialize()
    AudioEngine.loadLibrary()
    val loadedSettingsForInit = SettingsRepository.loadSettings()
    Strings.setLanguage(loadedSettingsForInit.app.language)
    NetworkHandler_v1.setupLinuxVirtualCable(loadedSettingsForInit.app.useNativeEngine)
    org.bytedeco.javacv.FFmpegLogCallback.set()
    org.bytedeco.ffmpeg.global.avdevice.avdevice_register_all()

    Runtime.getRuntime().addShutdownHook(Thread {
        runCatching { NetworkHandler_v1.terminateAllServices() }
        runCatching { DesktopDiagnostics.shutdown() }
    })

    val isHeadless = java.awt.GraphicsEnvironment.isHeadless()

    // Interactive terminal with no arguments: show first-run setup menu
    if (args.isEmpty() && System.console() != null) {
        when (runInteractiveSetup(isHeadless)) {
            SetupAction.GUI       -> startGuiApplication(CliArgs(runMode = RunMode.GUI))
            SetupAction.SHOW_HELP -> CliArgs.printHelp()
            else                  -> Unit
        }
        return
    }

    if (cliArgs.noTray) LinuxTray.disableForThisRun()

    Thread { runCatching { ProtocolRegistrar.ensureRegistered() } }
        .apply { isDaemon = true; name = "wfas-scheme-registrar" }
        .start()

    if (cliArgs.runMode != RunMode.GUI || isHeadless) {
        runCli(cliArgs)
    } else {
        startGuiApplication(cliArgs)
    }
}

fun startGuiApplication(cliArgs: CliArgs) = application {
    val loadedSettings = remember { SettingsRepository.loadSettings() }
    // La chiave del pairing QR non e' stata salvata: se ne conia una per questa
    // sessione, altrimenti il modo chiave partirebbe senza chiave.
    var appSettings    by remember { mutableStateOf(QrPairingState.seedSessionKey(loadedSettings.app)) }
    var audioSettings  by remember { mutableStateOf(loadedSettings.audio) }
    var streamingPort  by remember { mutableStateOf(loadedSettings.streamingPort) }
    var micPort        by remember { mutableStateOf(loadedSettings.micPort) }
    var micRoutingMode by remember { mutableStateOf(MicRoutingMode.fromStringSafe(loadedSettings.micRoutingMode)) }
    var settingsWriteError by remember { mutableStateOf<String?>(null) }
    val settingsSaveMutex = remember { Mutex() }
    var quitting by remember { mutableStateOf(false) }

    val isWindowsOS = remember { System.getProperty("os.name").lowercase().contains("win") }
    val receiverSources by DesktopReceiverSessions.sources.collectAsState()

    LaunchedEffect(appSettings.language) { Strings.setLanguage(appSettings.language) }

    LaunchedEffect(appSettings.senderGain) { NetworkHandler_v1.setServerVolume(appSettings.senderGain) }
    LaunchedEffect(Unit) {
        NetworkHandler_v1.setServerVolume(appSettings.senderGain)
        NetworkHandler_v1.captureGain.collect { gain ->
            if (appSettings.senderGain != gain) appSettings = appSettings.copy(senderGain = gain)
        }
    }
    LaunchedEffect(appSettings.receiverGain) { NetworkHandler_v1.setClientVolume(appSettings.receiverGain) }
    LaunchedEffect(appSettings.remoteVolumeSyncEnabled) {
        DesktopReceiverSessions.setRemoteVolumeEnabled(appSettings.remoteVolumeSyncEnabled)
    }

    // Il denoiser legge da qui: cambiando l'impostazione l'effetto e' immediato
    // sul flusso in riproduzione, senza riconnettersi.
    LaunchedEffect(appSettings.developerMode, appSettings.noiseReductionEnabled, appSettings.noiseReductionStrength) {
        NoiseReductionControl.apply(
            appSettings.developerMode && appSettings.noiseReductionEnabled,
            appSettings.noiseReductionStrength
        )
    }

    LaunchedEffect(appSettings, audioSettings, streamingPort, micPort, micRoutingMode, quitting) {
        if (quitting) return@LaunchedEffect
        val snapshot = AllSettings(appSettings, audioSettings, streamingPort, micPort, micRoutingMode.name)
        try {
            delay(250)
            settingsSaveMutex.withLock {
                withContext(Dispatchers.IO) { SettingsRepository.saveSettings(snapshot) }
            }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            settingsWriteError = failure.message ?: failure.javaClass.simpleName
            DesktopDiagnostics.event("SETTINGS_SAVE_FAILED", "type=${failure.javaClass.simpleName}")
        }
    }

    var showWelcome        by remember { mutableStateOf(!SettingsRepository.hasSeenWelcome()) }
    var showChangelog      by remember {
        mutableStateOf(
            SettingsRepository.hasSeenWelcome() &&
                    SettingsRepository.lastSeenChangelog() != Changelog.latest.version
        )
    }
    var changelogStandalone by remember { mutableStateOf(SettingsRepository.hasSeenWelcome()) }
    var updateBanner        by remember { mutableStateOf<UpdateChecker.Result.Available?>(null) }
    var manualUpdateResult  by remember { mutableStateOf<UpdateChecker.Result?>(null) }
    var checkingForUpdate   by remember { mutableStateOf(false) }

    // Al primo avvio non parte nessuna richiesta verso GitHub: la schermata di
    // benvenuto e' ancora aperta e sta spiegando che il controllo esiste e come
    // spegnerlo. Contattare l'esterno mentre l'utente legge quella spiegazione
    // renderebbe l'interruttore una formalita': la richiesta sarebbe gia' partita.
    // Chiusa la schermata, questo effetto riparte con l'interruttore nello stato
    // che l'utente ha scelto, e solo allora controlla.
    LaunchedEffect(showWelcome, appSettings.autoUpdateCheckEnabled) {
        if (showWelcome) return@LaunchedEffect
        if (!appSettings.autoUpdateCheckEnabled) return@LaunchedEffect
        if (updateBanner != null) return@LaunchedEffect
        val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { UpdateChecker.check() }
        updateBanner = UpdateChecker.automaticNotice(r)
    }

    val vizChannels = audioSettings.channels
    val vizSink: (ShortArray) -> Unit = remember(vizChannels) {
        { samples -> SpectrumAnalyzer.feedFrame(samples, vizChannels) }
    }
    LaunchedEffect(audioSettings.sampleRate) {
        SpectrumAnalyzer.configure(audioSettings.sampleRate.toInt())
    }
    var showSettings       by remember { mutableStateOf(false) }
    var isServer           by remember { mutableStateOf(true) }
    val discoveredDevices  = remember { mutableStateMapOf<String, ServerInfo>() }
    var connectionStatus   by remember { mutableStateOf(Strings.get("status_inactive")) }
    LaunchedEffect(settingsWriteError) {
        settingsWriteError?.let { connectionStatus = Strings.get("receiver_source_action_failed", it) }
    }
    var isStreaming         by remember { mutableStateOf(false) }
    LaunchedEffect(isStreaming) {
        if (!isStreaming) SpectrumAnalyzer.reset()
    }
    var virtualDriverStatus by remember { mutableStateOf<VirtualDriverStatus>(VirtualDriverStatus.Ok) }
    val scope = rememberCoroutineScope {
        CoroutineExceptionHandler { _, failure ->
            connectionStatus = Strings.get("receiver_source_action_failed", failure.message ?: failure.javaClass.simpleName)
            DesktopDiagnostics.event("GUI_ACTION_FAILED", "type=${failure.javaClass.simpleName} detail=${failure.message}")
        }
    }

    // ── Ricezione RTP / SDP ─────────────────────────────────────────────────
    // Il draft e' la configurazione in corso di modifica nella card; le salvate
    // vivono nelle impostazioni. Il ricevitore e' uno solo: ascoltare due
    // sorgenti insieme non ha senso con una sola uscita audio.
    var rtpDraft by remember { mutableStateOf(RtpSource()) }
    var rtpStatus by remember { mutableStateOf(RtpStatus()) }
    val rtpReceiver = remember { mutableStateOf<RtpReceiver?>(null) }
    val rtpSaved = remember(appSettings.rtpSources) {
        appSettings.rtpSources.mapNotNull { RtpSource.deserialize(it) }
    }
    DisposableEffect(Unit) {
        onDispose { rtpReceiver.value?.stop(); rtpReceiver.value = null }
    }

    // ── Client Snapcast ─────────────────────────────────────────────────────
    // Tre pezzi indipendenti: la ricerca in rete gira sempre mentre siamo in
    // ricezione, il client audio e quello di controllo solo da connessi. Il
    // controllo puo' fallire senza che l'audio ne risenta: sono due porte
    // diverse e due funzioni diverse.
    var snapDiscovered by remember { mutableStateOf<List<SnapcastServerRef>>(emptyList()) }
    var snapHost by remember { mutableStateOf("") }
    var snapStreamPort by remember { mutableStateOf(SnapcastDefaults.STREAM_PORT) }
    var snapControlPort by remember { mutableStateOf(SnapcastDefaults.CONTROL_PORT) }
    var snapStream by remember { mutableStateOf(SnapStreamStatus()) }
    var snapControl by remember { mutableStateOf(SnapControlStatus()) }
    // Si scopre solo provando: alcuni server non rialloggiano il client
    // staccato da un gruppo. Dopo il primo tentativo andato male non si
    // propone piu' l'operazione, invece di farla fallire ogni volta.
    var snapSplitSupported by remember { mutableStateOf(true) }
    var snapServer by remember { mutableStateOf<SnapcastServerRef?>(null) }
    val snapClient = remember { mutableStateOf<SnapcastStreamClient?>(null) }
    val snapCtrl = remember { mutableStateOf<SnapcastControlClient?>(null) }
    val snapBrowser = remember { mutableStateOf<SnapcastBrowser?>(null) }
    val snapSaved = remember(appSettings.snapcastServers) {
        appSettings.snapcastServers.mapNotNull { SnapcastServerRef.deserialize(it) }
    }
    val snapSelfId = remember { NetworkHandler_v1.localClientId() }

    // La ricerca ha senso solo mentre si guarda la lista dei dispositivi.
    LaunchedEffect(isServer, isStreaming) {
        if (!isServer && !isStreaming) {
            if (snapBrowser.value == null) {
                snapBrowser.value = SnapcastBrowser(
                    scope = scope,
                    interfaceProvider = { NetworkHandler_v1.getActiveNetworkInterface(appSettings.networkInterface) },
                    onServers = { snapDiscovered = it }
                ).also { it.start() }
            }
        } else {
            snapBrowser.value?.stop(); snapBrowser.value = null
            snapDiscovered = emptyList()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            snapClient.value?.stop(); snapCtrl.value?.stop(); snapBrowser.value?.stop()
        }
    }


    val outputDevices = remember { mutableStateOf<List<Mixer.Info>>(emptyList()) }
    var selectedOutputDevice by remember { mutableStateOf<Mixer.Info?>(null) }
    val currentReceiverOutput = rememberUpdatedState(selectedOutputDevice)
    var audioDevicesEnumerated by remember { mutableStateOf(false) }
    val inputDevices  = remember { mutableStateOf<List<Mixer.Info>>(emptyList()) }
    var selectedInputDevice  by remember { mutableStateOf<Mixer.Info?>(null) }
    var sendMicrophone          by remember { mutableStateOf(false) }
    var selectedClientMic       by remember { mutableStateOf<Mixer.Info?>(null) }
    var selectedServerMicOutput by remember { mutableStateOf<Mixer.Info?>(null) }
    var selectedMicMixInput     by remember { mutableStateOf<Mixer.Info?>(null) }
    var lastWfasServer by remember { mutableStateOf<ServerInfo?>(null) }
    var autoConnectSuspended by remember { mutableStateOf(false) }
    var autoAttemptPeer by remember { mutableStateOf<String?>(null) }
    var autoAnnouncedPeer by remember { mutableStateOf<String?>(null) }
    var guiSessionGeneration by remember { mutableStateOf(0L) }
    var guiStartupPending by remember { mutableStateOf(cliArgs.guiInitMode != null) }
    val guiCommands = remember { Mutex() }
    val savedSourceCredentials = remember { mutableMapOf<String, String>() }
    // A paused request resolves the current GUI choice when retry/recovery opens
    // its line. Capturing the initial null selection would pick the first mixer.
    LaunchedEffect(Unit) {
        NetworkHandler_v1.restoreRememberedSources(outputMixerProvider = { currentReceiverOutput.value })
    }
    // Metrics update much more often than user preferences. Key the save only
    // on preferences so slider drags settle without a write for every packet.
    val receiverPreferences = receiverSources.map(ReceiverSourcePreferences::from)
    LaunchedEffect(receiverPreferences, quitting) {
        if (quitting) return@LaunchedEffect
        delay(250)
        try {
            settingsSaveMutex.withLock {
                withContext(Dispatchers.IO) { ReceiverSourceStore.updatePreferences(receiverPreferences) }
            }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            settingsWriteError = failure.message ?: failure.javaClass.simpleName
            DesktopDiagnostics.event("SOURCE_PREFERENCES_SAVE_FAILED", "type=${failure.javaClass.simpleName}")
        }
    }
    LaunchedEffect(receiverSources) {
        if (!isServer && rtpReceiver.value == null && snapClient.value == null) {
            isStreaming = DesktopReceiverSessions.hasActiveSources()
        }
        savedSourceCredentials.keys.retainAll(receiverSources.map { it.id }.toSet())
        for (source in receiverSources) {
            if (!source.connected) continue
            val key = NetworkHandler_v1.receiverCredential(source.id)?.takeIf { it.isNotBlank() } ?: continue
            if (savedSourceCredentials[source.id] == key) continue
            try {
                if (withContext(Dispatchers.IO) { ReceiverSourceStore.updateCredential(source.id, key) }) {
                    savedSourceCredentials[source.id] = key
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                settingsWriteError = failure.message ?: failure.javaClass.simpleName
            }
        }
        val onlyActive = receiverSources.filter { it.connected && !it.paused && !it.blocked }.singleOrNull()
        NetworkHandler_v1.playbackTargetMs.value = onlyActive?.targetMs
    }

    fun stopSnapcast() {
        snapClient.value?.stop(); snapClient.value = null
        snapCtrl.value?.stop();   snapCtrl.value = null
        snapStream = SnapStreamStatus()
        snapControl = SnapControlStatus()
        snapSplitSupported = true
        snapServer = null
        SpectrumAnalyzer.reset()
    }

    /**
     * Apre (o riapre) il solo canale audio verso il server, lasciando in piedi
     * quello di controllo.
     *
     * Riaprirlo e' anche il modo di uscire da un gruppo su un server che non
     * rialloggia i client staccati: alla riconnessione il server assegna un
     * gruppo nuovo a chi non ne ha, e questo lo possiamo fare solo per noi
     * stessi — la riconnessione di un altro client non la comanda nessuno.
     */
    fun startSnapStream(server: SnapcastServerRef) {
        snapClient.value?.stop()
        snapClient.value = SnapcastStreamClient(
            server = server,
            clientId = snapSelfId,
            clientName = runCatching { java.net.InetAddress.getLocalHost().hostName }
                .getOrDefault("WFAS"),
            onStatus = { snapStream = it },
            onPcm = vizSink,
            openPlayer = { rate, ch, bufferMs ->
                NetworkHandler_v1.openPcmPlaybackSink(selectedOutputDevice, rate, ch, bufferMs)
            }
        ).also { it.start(scope) }
    }

    fun connectSnapcast(server: SnapcastServerRef) {
        stopSnapcast()
        snapServer = server
        snapHost = server.host
        snapStreamPort = server.streamPort
        snapControlPort = server.controlPort

        startSnapStream(server)

        snapCtrl.value = SnapcastControlClient(
            host = server.host,
            port = server.controlPort,
            onStatus = { snapControl = it }
        ).also { it.start(scope) }
    }

    var isMulticastMode by remember { mutableStateOf(appSettings.lastMulticastMode) }
    val isMicMuted by NetworkHandler_v1.isMicMuted.collectAsState()

    val serverCapabilities by remember(appSettings, streamingPort) {
        derivedStateOf {
            val protocols = mutableSetOf(StreamingProtocol.WFAS)
            if (appSettings.rtpEnabled)  protocols.add(StreamingProtocol.RTP)
            if (appSettings.httpEnabled) protocols.add(StreamingProtocol.HTTP)
            val httpPort = if (appSettings.httpEnabled) appSettings.httpPort.toIntOrNull() ?: 8080 else null
            ServerCapabilities(protocols, httpPort, appSettings.httpSafariMode)
        }
    }

    val localIp by NetworkHandler_v1.localIpFlow.collectAsState()
    val networkRevision by NetworkHandler_v1.networkRevision.collectAsState()
    LaunchedEffect(Unit) { NetworkHandler_v1.startNetworkWatch() }
    LaunchedEffect(networkRevision) {
        if (networkRevision > 0 && !isServer) {
            discoveredDevices.clear(); NetworkHandler_v1.forgetDiscoveredAddresses()
            NetworkHandler_v1.restartDeviceDiscovery { hostname, serverInfo ->
                discoveredDevices[hostname] = serverInfo
            }
        }
    }
    val httpUrl by remember(appSettings, isStreaming, isServer) {
        derivedStateOf {
            if (isStreaming && isServer && appSettings.httpEnabled)
                "http://$localIp:${appSettings.httpPort.toIntOrNull() ?: 8080}"
            else null
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            val outs = runCatching { NetworkHandler_v1.findAvailableOutputMixers() }.getOrNull()
            val ins  = runCatching { NetworkHandler_v1.findAvailableInputMixers() }.getOrNull()
            if (outs != null && outs.map { it.name } != outputDevices.value.map { it.name }) {
                outputDevices.value = outs
                if (selectedOutputDevice == null) {
                    selectedOutputDevice = outs.firstOrNull()
                } else {
                    // Keep an unavailable explicit choice until it returns or the
                    // owner selects another device; a retry must not change speakers.
                    outs.firstOrNull { it.name == selectedOutputDevice?.name }?.let { selectedOutputDevice = it }
                }
            }
            if (ins != null && ins.map { it.name } != inputDevices.value.map { it.name }) {
                inputDevices.value = ins
                if (selectedInputDevice == null || ins.none { it.name == selectedInputDevice?.name }) {
                    selectedInputDevice = ins.firstOrNull()
                }
            }
            delay(3000)
        }
    }

    LaunchedEffect(Unit) {
        outputDevices.value = NetworkHandler_v1.findAvailableOutputMixers()
        inputDevices.value  = NetworkHandler_v1.findAvailableInputMixers()
        selectedOutputDevice     = outputDevices.value.firstOrNull()
        selectedInputDevice      = inputDevices.value.firstOrNull()
        selectedClientMic        = inputDevices.value.firstOrNull()
        selectedMicMixInput      = inputDevices.value.firstOrNull()
        selectedServerMicOutput  = outputDevices.value
            .find { it.name.contains("CABLE Input", ignoreCase = true) }
            ?: outputDevices.value.firstOrNull()
        virtualDriverStatus = NetworkHandler_v1.checkVirtualDriverStatus(appSettings.useNativeEngine)
        audioDevicesEnumerated = true
    }

    val useDarkTheme = when (appSettings.theme) {
        Theme.Light  -> false
        Theme.Dark   -> true
        Theme.System -> isSystemInDarkTheme()
    }

    val windowState = rememberWindowState(size = DpSize(600.dp, 800.dp))
    var lastDisconnectTime by remember { mutableStateOf(0L) }

    var isWindowVisible by remember { mutableStateOf(!appSettings.startMinimizedToTray) }
    val trayState = rememberTrayState()
    val trayIcon: Painter = remember { loadTrayIconPainter() }
    val trayBaseImage = remember { loadTrayIconImage() }
    val captureKinds by CaptureMonitor.active.collectAsState()
    val capturing = captureKinds.isNotEmpty()
    val trayIconLive: Painter = remember(captureKinds, trayBaseImage) {
        if (!capturing) trayIcon
        else CaptureIcon.badge(trayBaseImage, captureKinds)
            ?.let { BitmapPainter(it.toComposeImageBitmap()) }
            ?: trayIcon
    }

    /**
     * Motivo per cui su Linux la tray non c'e'. Finche' e' valorizzato la
     * finestra non puo' nascondersi: senza icona non ci sarebbe piu' modo di
     * farla tornare, e l'app resterebbe viva e irraggiungibile.
     */
    var trayUnavailableReason by remember { mutableStateOf<String?>(null) }

    val performQuit: () -> Unit = {
        if (!quitting) {
            quitting = true
            scope.launch {
                try {
                    val settings = AllSettings(appSettings, audioSettings, streamingPort, micPort, micRoutingMode.name)
                    val preferences = receiverSources.map { ReceiverSourcePreferences.from(it).copy(paused = true) }
                    NetworkHandler_v1.stopCurrentStream()
                    rtpReceiver.value?.stop()
                    stopSnapcast()
                    settingsSaveMutex.withLock {
                        withContext(Dispatchers.IO) {
                            SettingsRepository.saveSettings(settings)
                            ReceiverSourceStore.updatePreferences(preferences)
                        }
                    }
                    exitApplication()
                } finally {
                    quitting = false
                }
            }
        }
        Unit
    }

    val hideOrQuit: () -> Unit = {
        if (appSettings.closeToTray && trayUnavailableReason == null) {
            isWindowVisible = false
            runCatching {
                trayState.sendNotification(
                    Notification(
                        title = "AudioBridge",
                        message = Strings.get("tray_running_in_background"),
                        type = Notification.Type.Info
                    )
                )
            }
        } else {
            performQuit()
        }
    }

    val showAndRaise: () -> Unit = {
        isWindowVisible = true
        windowState.isMinimized = false
    }

    suspend fun stopGuiAudio() {
        guiSessionGeneration++
        NetworkHandler_v1.endDeviceDiscovery()
        NetworkHandler_v1.stopCurrentStream(retainReceiverSources = true)
        val preferences = DesktopReceiverSessions.sources.value.map(ReceiverSourcePreferences::from)
        val settings = AllSettings(appSettings, audioSettings, streamingPort, micPort, micRoutingMode.name)
        settingsSaveMutex.withLock {
            withContext(Dispatchers.IO) {
                SettingsRepository.saveSettings(settings)
                ReceiverSourceStore.updatePreferences(preferences)
            }
        }
        rtpReceiver.value?.stop(); rtpReceiver.value = null
        stopSnapcast()
        isStreaming = false
        connectionStatus = Strings.get("status_inactive")
    }

    fun discoverForReceiving() {
        NetworkHandler_v1.beginDeviceDiscovery { hostname, info ->
            if (info.lastSeen == 0L) discoveredDevices.remove(hostname) else discoveredDevices[hostname] = info
        }
    }

    val clientDisconnectKeys = setOf(
        "status_server_disconnected", "status_server_no_response", "status_handshake_failed",
        "status_protocol_incompatible", "status_server_silent_maybe_outdated", "status_server_busy",
        "status_unauthorized", "status_unsupported_format", "status_error_client", "playback_failed"
    )
    val clientStatusHandler: (String, Array<out Any>) -> Unit = { key, args ->
        connectionStatus = if (args.isEmpty()) Strings.get(key) else Strings.get(key, *args)
        if (key == "status_connected_streaming_from" || key == "status_multicast_streaming") autoAttemptPeer?.let { autoAnnouncedPeer = it }
        // A failed source owns its retry/block state. Its callback must never
        // stop the other sources or erase their remembered targets.
        if (key in clientDisconnectKeys) isStreaming = DesktopReceiverSessions.hasActiveSources()
    }
    fun guardedClientStatus(): (String, Array<out Any>) -> Unit {
        val generation = guiSessionGeneration
        return { key, args -> if (generation == guiSessionGeneration) clientStatusHandler(key, args) }
    }

    suspend fun receiveWfas(info: ServerInfo) {
        val device = selectedOutputDevice
        if (device == null) {
            connectionStatus = Strings.get("rtp_rx_need_output")
            showAndRaise()
            return
        }
        if (isServer || rtpReceiver.value != null || snapClient.value != null) stopGuiAudio()
        // Connection setup reads network/remote-volume preferences from the
        // repository. Flush the current UI before the debounced save can lag it.
        val settings = AllSettings(appSettings, audioSettings, streamingPort, micPort, micRoutingMode.name)
        settingsSaveMutex.withLock {
            withContext(Dispatchers.IO) { SettingsRepository.saveSettings(settings) }
        }
        isServer = false
        autoConnectSuspended = false
        autoAttemptPeer = null; autoAnnouncedPeer = null
        lastWfasServer = info
        isStreaming = true
        NetworkHandler_v1.clientKeyPromptAllowed = true
        try {
            NetworkHandler_v1.addReceiverSource(
                audioSettings, info, device, sendMicrophone, selectedClientMic, micPort.toIntOrNull() ?: 9092,
                appSettings.connectionSoundEnabled,
                onAudioFrame = vizSink, onStatusUpdate = guardedClientStatus(),
                outputMixerProvider = { currentReceiverOutput.value }
            )
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            isStreaming = DesktopReceiverSessions.hasActiveSources()
            connectionStatus = Strings.get("receiver_source_action_failed", failure.message ?: failure.javaClass.simpleName)
        }
    }

    fun guardedServerStatus(nativeEngine: Boolean): (String, Array<out Any>) -> Unit {
        val generation = guiSessionGeneration
        val persistent = cliArgs.persist ?: appSettings.serverPersist
        return { key, args ->
            if (generation == guiSessionGeneration) {
                val error = key == "error_virtual_driver_missing" || key.startsWith("status_error_") ||
                    (key == "status_capture_unavailable" && !persistent)
                if (key == "error_virtual_driver_missing") {
                    virtualDriverStatus = NetworkHandler_v1.checkVirtualDriverStatus(nativeEngine)
                }
                if (error || (key == "status_client_disconnected" && !persistent)) {
                    val status = if (error) Strings.get(key, *args) else Strings.get("status_inactive")
                    scope.launch { guiCommands.withLock {
                        if (generation == guiSessionGeneration) {
                            stopGuiAudio()
                            connectionStatus = status
                        }
                    } }
                } else connectionStatus = Strings.get(key, *args)
            }
        }
    }

    val startSending: () -> Unit = {
        scope.launch {
            guiCommands.withLock {
                stopGuiAudio()
                isServer = true
                autoConnectSuspended = false
                autoAttemptPeer = null; autoAnnouncedPeer = null
                if (SecurityMode.requiresKey(appSettings.securityMode) && appSettings.authKey.isBlank()) {
                    connectionStatus = Strings.get("server_key_required_desc")
                    showAndRaise()
                    return@withLock
                }
                if (!WfasPolicy.canStartServerWith(appSettings.wfasMode, UsbLink.isReady(), appSettings.rtpEnabled,
                        appSettings.httpEnabled, appSettings.dlnaEnabled || appSettings.snapcastEnabled)) {
                    connectionStatus = Strings.get("wfas_no_protocol_desc")
                    showAndRaise()
                    return@withLock
                }
                isStreaming = true
                connectionStatus = Strings.get("status_starting_server")
                isMulticastMode = isMulticastMode || appSettings.rtpEnabled || appSettings.httpEnabled ||
                    appSettings.dlnaEnabled || appSettings.snapcastEnabled
                NetworkHandler_v1.configureSecurity(appSettings.securityMode, appSettings.authKey, appSettings.encryptionEnabled)
                IpcServer.applySecurity(appSettings.securityMode, appSettings.authKey)
                NetworkHandler_v1.launchServerInstance(
                    audioSettings, streamingPort.toIntOrNull() ?: 9090, isMulticastMode, serverCapabilities,
                    micRoutingMode, selectedServerMicOutput, micPort.toIntOrNull() ?: 9092, appSettings.rtpPort.toIntOrNull() ?: 9094,
                    appSettings.useNativeEngine, selectedMicMixInput,
                    muteRender = cliArgs.muteRender ?: appSettings.muteRender,
                    persist = cliArgs.persist ?: appSettings.serverPersist,
                    dlnaConfig = appSettings.toDlnaConfig(), snapcastConfig = appSettings.toSnapcastConfig(), onAudioFrame = vizSink,
                    onStatusUpdate = guardedServerStatus(appSettings.useNativeEngine)
                )
            }
        }
        Unit
    }
    val stopAudio: () -> Unit = {
        scope.launch { guiCommands.withLock {
            autoConnectSuspended = true
            autoAttemptPeer = null; autoAnnouncedPeer = null
            stopGuiAudio()
            if (!isServer) discoverForReceiving()
        } }
        Unit
    }
    fun sourceAction(action: () -> Unit) {
        try { action() } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            connectionStatus = Strings.get("receiver_source_action_failed", failure.message ?: failure.javaClass.simpleName)
            DesktopDiagnostics.event("SOURCE_ACTION_FAILED", "type=${failure.javaClass.simpleName} detail=${failure.message}")
        }
    }
    fun updateAppSettings(updated: AppSettings) {
        if (updated.muteRender != appSettings.muteRender && isServer && isStreaming) {
            scope.launch { guiCommands.withLock {
                stopGuiAudio()
                appSettings = updated
                connectionStatus = Strings.get("sender_playback_restart_required")
            } }
        } else appSettings = updated
    }
    val restartReceiving: () -> Unit = {
        scope.launch { guiCommands.withLock {
            val target = appSettings.activeAutoConnectTargets().firstOrNull()
            val info = lastWfasServer ?: target?.let { ServerInfo(it.ip, false, it.port ?: 9090) }
            if (lastWfasServer == null && target != null) NetworkHandler_v1.clientPresharedKey = target.resolveKey().orEmpty()
            if (info != null) receiveWfas(info) else {
                stopGuiAudio(); isServer = false; autoConnectSuspended = false
                discoverForReceiving(); showAndRaise()
            }
        } }
        Unit
    }

    // Se l'avvio era "minimizzato nella tray" ma la tray non si e' materializzata,
    // la finestra torna visibile: meglio una finestra non richiesta che un
    // processo senza interfaccia.
    LaunchedEffect(trayUnavailableReason) {
        if (trayUnavailableReason != null && !isWindowVisible) isWindowVisible = true
    }

    val isLinux = System.getProperty("os.name").lowercase().contains("linux")

    if (!isLinux) {
        // --- Windows e macOS: Usiamo il Tray nativo di Compose ---
        Tray(
            icon = trayIconLive,
            state = trayState,
            tooltip = "${BuildIdentity.title} · $connectionStatus",
            onAction = showAndRaise,
            menu = {
                Item(text = connectionStatus, enabled = false, onClick = {})
                Item(text = Strings.get("tray_send"), onClick = startSending)
                Item(text = Strings.get("tray_receive"), onClick = restartReceiving)
                Item(text = Strings.get("tray_stop"), onClick = stopAudio)
                Separator()
                Item(text = Strings.get("tray_volume", DesktopVolumeScale.percent(
                    if (isServer) appSettings.senderGain else appSettings.receiverGain,
                    if (isServer) DesktopVolumeScale.SENDER_MAX else DesktopVolumeScale.RECEIVER_MAX)), enabled = false, onClick = {})
                Item(text = Strings.get("tray_volume_down"), onClick = {
                    appSettings = if (isServer) appSettings.copy(senderGain = (appSettings.senderGain - 0.2f).coerceAtLeast(0f))
                        else appSettings.copy(receiverGain = (appSettings.receiverGain - 0.1f).coerceAtLeast(0f))
                })
                Item(text = Strings.get("tray_volume_up"), onClick = {
                    appSettings = if (isServer) appSettings.copy(senderGain = (appSettings.senderGain + 0.2f).coerceAtMost(2f))
                        else appSettings.copy(receiverGain = (appSettings.receiverGain + 0.1f).coerceAtMost(1f))
                })
                Separator()
                if (capturing) {
                    Item(text = "${Strings.get("capture_active_title")}: ${CaptureMonitor.summary()}", onClick = showAndRaise)
                    Separator()
                }
                Item(
                    text = if (isWindowVisible) Strings.get("tray_hide_window") else Strings.get("tray_show_window"),
                    onClick = {
                        if (isWindowVisible) isWindowVisible = false
                        else showAndRaise()
                    }
                )
                Separator()
                Item(
                    text = Strings.get("tray_quit"),
                    onClick = performQuit
                )
            }
        )
    } else {
        LaunchedEffect(Unit) {
            val iconUrl = Thread.currentThread().contextClassLoader?.getResource("app_icon.png")
                ?: ClassLoader.getSystemResource("app_icon.png")
                ?: object {}.javaClass.getResource("/app_icon.png")

            // --tray vale solo per questa esecuzione e batte l'impostazione salvata.
            val trayMode = cliArgs.trayMode ?: appSettings.linuxTray
            AppDebug.log("[TRAY] ${LinuxTray.describe(trayMode)}")

            val created = LinuxTray.install(trayMode, iconUrl, "AudioBridge") { linuxTray ->
                val toggleItem = dorkbox.systemTray.MenuItem(Strings.get("tray_show_window"))

                toggleItem.setCallback {
                    isWindowVisible = !isWindowVisible
                    if (isWindowVisible) {
                        windowState.isMinimized = false
                        toggleItem.text = Strings.get("tray_hide_window")
                    } else {
                        toggleItem.text = Strings.get("tray_show_window")
                    }
                }

                val quitItem = dorkbox.systemTray.MenuItem(Strings.get("tray_quit")) {
                    linuxTray.shutdown()
                    performQuit()
                }

                linuxTray.menu.add(toggleItem)
                linuxTray.menu.add(dorkbox.systemTray.Separator())
                linuxTray.menu.add(quitItem)
            }

            // Senza icona la finestra non puo' sparire, altrimenti l'app resta
            // in esecuzione senza un modo per tornarci.
            if (created) {
                launch {
                    CaptureMonitor.active.collect { LinuxTray.reflectCapture(it) }
                }
            }

            if (!created) {
                trayUnavailableReason = LinuxTray.lastSkipReason
                AppDebug.log("[TRAY] not installed: ${LinuxTray.lastSkipReason}")
            }
        }
    }

    Window(
        onCloseRequest = hideOrQuit,
        state      = windowState,
        visible    = isWindowVisible,
        undecorated = false,
        title      = BuildIdentity.title,
        icon       = trayIcon
    ) {
        // Enforce a minimum window size so content is always reachable on low-res displays.
        LaunchedEffect(Unit) {
            window.minimumSize = java.awt.Dimension(400, 500)
        }
        // Restore what the user left behind. Size always applies; the position only
        // when it still falls on an attached screen, otherwise the default centring wins.
        LaunchedEffect(Unit) {
            val stored = WindowGeometryCodec.parse(appSettings.windowGeometry) ?: return@LaunchedEffect
            runCatching {
                window.setSize(stored.width, stored.height)
                val x = stored.x
                val y = stored.y
                if (x != null && y != null && WindowGeometryCodec.isOnAnyScreen(x, y)) {
                    window.setLocation(x, y)
                }
            }
        }
        LaunchedEffect(windowState) {
            snapshotFlow { windowState.size to windowState.position }.collectLatest { (size, position) ->
                // Debounce a drag/resize; the last position of a gesture is the one to keep.
                delay(800)
                if (windowState.isMinimized) return@collectLatest
                if (windowState.placement != WindowPlacement.Floating) return@collectLatest
                // WindowState values already map 1:1 onto AWT pixels, so they are stored
                // as-is: converting through LocalDensity would multiply them by the scale
                // factor and the window would grow on every restart.
                val width = size.width.value.toInt()
                val height = size.height.value.toInt()
                val absolute = position as? WindowPosition.Absolute
                val x = absolute?.x?.value?.toInt()
                val y = absolute?.y?.value?.toInt()
                val encoded = WindowGeometryCodec.format(WindowGeometry(width, height, x, y))
                if (encoded != appSettings.windowGeometry) {
                    appSettings = appSettings.copy(windowGeometry = encoded)
                }
            }
        }
        LaunchedEffect(
            appSettings.securityMode,
            appSettings.qrPairingEnabled,
            appSettings.encryptionEnabled,
            isMulticastMode,
            appSettings.rtpEnabled,
            appSettings.httpEnabled,
            appSettings.dlnaEnabled,
            appSettings.snapcastEnabled
        ) {
            val forced = SecurityMode.encryptionForcedStored(
                appSettings.securityMode,
                appSettings.qrPairingEnabled,
                isMulticastMode || appSettings.rtpEnabled || appSettings.httpEnabled ||
                        appSettings.dlnaEnabled || appSettings.snapcastEnabled
            )
            if (forced && !appSettings.encryptionEnabled) {
                appSettings = appSettings.copy(encryptionEnabled = true)
            }
        }
        // Un invito chiesto da fuori ('wfas pair invite') deve nascere dallo
        // stato di questa finestra: la chiave sta qui, non nel file, e chi la
        // rinnova deve poterla far vedere anche alla finestra stessa.
        LaunchedEffect(appSettings, streamingPort, isMulticastMode) {
            PairRuntime.issuer = { forceNewKey ->
                QrPairingState.invite.value = null
                QrPairingState.generateInvite(
                    settings = appSettings,
                    localIp = NetworkHandler_v1.getLocalIpAddress(),
                    port = streamingPort.toIntOrNull() ?: 9090,
                    multicast = isMulticastMode,
                    forceNewKey = forceNewKey
                ) { next -> appSettings = next }
                QrPairingState.invite.value
            }
        }
        LaunchedEffect(appSettings.securityMode, appSettings.authKey, appSettings.encryptionEnabled) {
            NetworkHandler_v1.configureSecurity(appSettings.securityMode, appSettings.authKey, appSettings.encryptionEnabled)
            // Anche il canale di controllo locale segue la stessa sicurezza:
            // attivare la modalita' KEY dalle impostazioni deve chiudere
            // 'wfas control' a chi la chiave non ce l'ha, subito e non al
            // prossimo avvio.
            IpcServer.applySecurity(appSettings.securityMode, appSettings.authKey)
        }
        var authRequestPeer by remember { mutableStateOf<String?>(null) }
        val authDecision = remember { java.util.concurrent.atomic.AtomicReference<kotlinx.coroutines.CompletableDeferred<Boolean>?>(null) }
        LaunchedEffect(Unit) {
            NetworkHandler_v1.onAuthRequest = { peer ->
                val def = kotlinx.coroutines.CompletableDeferred<Boolean>()
                authDecision.set(def)
                authRequestPeer = peer
                try { def.await() } finally {
                    if (authDecision.compareAndSet(def, null)) authRequestPeer = null
                }
            }
        }
        var keyRequestWrong by remember { mutableStateOf<Boolean?>(null) }
        val keyDecision = remember { java.util.concurrent.atomic.AtomicReference<kotlinx.coroutines.CompletableDeferred<String?>?>(null) }
        val keyPromptMutex = remember { Mutex() }
        LaunchedEffect(Unit) {
            NetworkHandler_v1.onKeyRequest = { wrong ->
                keyPromptMutex.withLock {
                if (!NetworkHandler_v1.clientKeyPromptAllowed) {
                    AppDebug.log("[CLIENT] key requested but prompting is disabled for this attempt")
                    null
                } else {
                    val def = kotlinx.coroutines.CompletableDeferred<String?>()
                    keyDecision.set(def)
                    keyRequestWrong = wrong
                    try { def.await() } finally {
                        if (keyDecision.compareAndSet(def, null)) keyRequestWrong = null
                    }
                }
                }
            }
        }
        DisposableEffect(Unit) {
            onDispose {
                authDecision.getAndSet(null)?.cancel()
                keyDecision.getAndSet(null)?.cancel()
                NetworkHandler_v1.onAuthRequest = null
                NetworkHandler_v1.onKeyRequest = null
            }
        }
        val customColor = appSettings.customThemeColor?.toULong()?.let { Color(it) }
        val currentColorScheme = if (customColor != null) {
            MaterialYouGenerator.generateDynamicColorScheme(customColor, useDarkTheme)
        } else {
            if (useDarkTheme) darkColorScheme() else lightColorScheme()
        }

        val typography = remember(Strings.language) { desktopTypography(Strings.language) }
        MaterialTheme(colorScheme = currentColorScheme, typography = typography) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                authRequestPeer?.let { peer ->
                    AlertDialog(
                        onDismissRequest = { authDecision.getAndSet(null)?.complete(false); authRequestPeer = null },
                        title = { Text(stringResource("auth_request_title")) },
                        text  = { Text(stringResource("auth_request_body", peer)) },
                        confirmButton = {
                            Button(onClick = { authDecision.getAndSet(null)?.complete(true); authRequestPeer = null }) {
                                Text(stringResource("auth_allow"))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { authDecision.getAndSet(null)?.complete(false); authRequestPeer = null }) {
                                Text(stringResource("auth_deny"))
                            }
                        }
                    )
                }
                keyRequestWrong?.let { wrong ->
                    var keyText by remember { mutableStateOf("") }
                    AlertDialog(
                        onDismissRequest = { keyDecision.getAndSet(null)?.complete(null); keyRequestWrong = null },
                        title = { Text(stringResource("key_dialog_title")) },
                        text = {
                            Column {
                                Text(if (wrong) stringResource("key_dialog_wrong") else stringResource("key_dialog_body"))
                                Spacer(Modifier.height(8.dp))
                                AuthKeyField(
                                    value = keyText,
                                    onValueChange = { keyText = it },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        },
                        confirmButton = {
                            Button(onClick = { keyDecision.getAndSet(null)?.complete(keyText); keyRequestWrong = null }) {
                                Text(stringResource("key_dialog_connect"))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { keyDecision.getAndSet(null)?.complete(null); keyRequestWrong = null }) {
                                Text(stringResource("key_dialog_cancel"))
                            }
                        }
                    )
                }
                val protocolMismatch by NetworkHandler_v1.protocolMismatch.collectAsState()
                if (protocolMismatch != null) {
                    val mm = protocolMismatch!!
                    AlertDialog(
                        onDismissRequest = { NetworkHandler_v1.clearProtocolMismatch() },
                        icon  = { Icon(Icons.Default.Warning, contentDescription = null) },
                        title = {
                            Text(
                                Strings.get(
                                    if (mm.localIsOutdated) "protocol_incompatible_title_local"
                                    else "protocol_incompatible_title"
                                )
                            )
                        },
                        text  = {
                            val bodyKey = when {
                                mm.localIsOutdated -> "protocol_incompatible_body_local"
                                mm.remoteRole == PeerRole.SENDER -> "protocol_incompatible_body_sender"
                                else -> "protocol_incompatible_body_receiver"
                            }
                            Text(Strings.get(bodyKey, mm.localVersion, mm.remoteVersion))
                        },
                        confirmButton = {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    runCatching { openUrl("https://github.com/mu23XR/AudioBridge/releases") }
                                    NetworkHandler_v1.clearProtocolMismatch()
                                }) { Text(Strings.get("protocol_incompatible_website")) }
                                TextButton(onClick = {
                                    val updateUrl = if (mm.localVersion < mm.remoteVersion)
                                        "https://github.com/mu23XR/AudioBridge/releases"
                                    else
                                        "https://github.com/mu23XR/AudioBridge/releases"
                                    runCatching { openUrl(updateUrl) }
                                    NetworkHandler_v1.clearProtocolMismatch()
                                }) { Text(Strings.get("protocol_incompatible_github")) }
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { NetworkHandler_v1.clearProtocolMismatch() }) { Text(Strings.get("close")) }
                        }
                    )
                }

                // Ogni connessione avviata a mano riporta la finestra della chiave
                // in servizio: il divieto vale solo per il tentativo automatico
                // che lo ha impostato.
                val allowKeyPrompt: () -> Unit = { NetworkHandler_v1.clientKeyPromptAllowed = true }

                LaunchedEffect(Unit) {
                    val initialGeneration = guiSessionGeneration
                    if (cliArgs.guiInitMode == null && appSettings.autoStartServer && isServer) {
                        // Ritardo dichiarato dall'utente: al login la rete spesso
                        // non c'e' ancora, e un server che parte senza indirizzo
                        // e' un server che nessuno trova.
                        if (appSettings.autoStartDelaySec > 0) {
                            connectionStatus = Strings.get("auto_start_waiting")
                            delay(appSettings.autoStartDelaySec * 1000L)
                        }

                        if (appSettings.autoStartRequireNetwork) {
                            val deadline = System.currentTimeMillis() + AUTO_START_NETWORK_TIMEOUT_MS
                            while (System.currentTimeMillis() < deadline &&
                                NetworkHandler_v1.getLocalIpAddress().isBlank() &&
                                guiSessionGeneration == initialGeneration && isServer && !quitting
                            ) {
                                connectionStatus = Strings.get("auto_start_waiting_network")
                                delay(1000)
                            }
                            if (quitting || !isServer || guiSessionGeneration != initialGeneration) return@LaunchedEffect
                            if (NetworkHandler_v1.getLocalIpAddress().isBlank()) {
                                connectionStatus = Strings.get("auto_start_no_network")
                                return@LaunchedEffect
                            }
                        }

                        awaitDesktopStartupReady(
                            ready = { Unit.takeIf { audioDevicesEnumerated } },
                            cancelled = { quitting || !isServer || isStreaming || guiSessionGeneration != initialGeneration }
                        ) ?: return@LaunchedEffect

                        // La sicurezza dell'avvio automatico puo' differire da
                        // quella generale: e' il caso di chi tiene la GUI aperta
                        // senza chiave ma vuole che il server automatico la esiga.
                        val autoMode = AutoStartSecurity.resolve(
                            appSettings.autoStartSecurityMode, appSettings.securityMode
                        )
                        if (SecurityMode.requiresKey(autoMode) && appSettings.authKey.isBlank()) {
                            connectionStatus = Strings.get("auto_start_key_missing")
                            return@LaunchedEffect
                        }
                        // La cifratura dell'avvio automatico e' una scelta a se':
                        // INHERIT segue l'impostazione generale, ON e OFF la
                        // scavalcano. Resta comunque legata alla chiave, quindi
                        // in OFF e in ASK non si attiva in nessun caso.
                        val autoEncrypt = AutoStartSecurity.resolveEncryption(
                            appSettings.autoStartEncryption,
                            appSettings.encryptionEnabled,
                            autoMode
                        )
                        NetworkHandler_v1.configureSecurity(autoMode, appSettings.authKey, autoEncrypt)
                        IpcServer.applySecurity(autoMode, appSettings.authKey)
                        AppDebug.log("[AUTOSTART] security=$autoMode encrypted=$autoEncrypt")

                        if (appSettings.autoStartVolume >= 0) {
                            appSettings = appSettings.copy(senderGain = (appSettings.autoStartVolume / 100f).coerceIn(0f, 2f))
                        }

                        isStreaming = true
                        connectionStatus = Strings.get("status_starting_server")
                        val port = streamingPort.toIntOrNull() ?: 9090
                        val mic = micPort.toIntOrNull() ?: 9092
                        val rtp = appSettings.rtpPort.toIntOrNull() ?: 9094

                        isMulticastMode = appSettings.autoStartMulticast || appSettings.rtpEnabled ||
                                appSettings.httpEnabled || appSettings.dlnaEnabled || appSettings.snapcastEnabled

                        NetworkHandler_v1.launchServerInstance(
                            audioSettings, port, isMulticastMode, serverCapabilities,
                            micRoutingMode, selectedServerMicOutput, mic, rtp,
                            appSettings.useNativeEngine, selectedMicMixInput,
                            muteRender = cliArgs.muteRender ?: appSettings.muteRender,
                            persist = cliArgs.persist ?: appSettings.serverPersist,
                            dlnaConfig = appSettings.toDlnaConfig(),
                            snapcastConfig = appSettings.toSnapcastConfig(),
                            onAudioFrame = vizSink,
                            onStatusUpdate = guardedServerStatus(appSettings.useNativeEngine)
                        )
                    }
                }

                LaunchedEffect(Unit) {
                    if (appSettings.autoConnectClientEnabled && cliArgs.guiInitMode != "server") {
                        isServer = false
                        discoveredDevices.clear(); NetworkHandler_v1.forgetDiscoveredAddresses()
                        NetworkHandler_v1.beginDeviceDiscovery { hostname, serverInfo ->
                            discoveredDevices[hostname] = serverInfo
                        }
                    }
                }

                LaunchedEffect(Unit) {
                    IpcServer.start(cliArgs)
                }

                LaunchedEffect(Unit) {
                    if (!guiStartupPending) return@LaunchedEffect
                    try {
                        val requestedMode = cliArgs.guiInitMode ?: return@LaunchedEffect
                        val initialGeneration = guiSessionGeneration
                        if (isStreaming) return@LaunchedEffect
                        isServer = requestedMode == "server"
                        if (requestedMode == "client" && cliArgs.serverIp == null) discoverForReceiving()
                        val startup = awaitDesktopStartupReady<Pair<Mixer.Info?, ServerInfo?>>(
                            ready = {
                                if (!audioDevicesEnumerated) null
                                else if (requestedMode == "server") Pair(null, null)
                                else {
                                    val output = selectedOutputDevice
                                    val peer = cliArgs.serverIp?.let { ServerInfo(it, false, cliArgs.port) }
                                        ?: discoveredDevices.values.firstOrNull()
                                    if (output == null || peer == null) null else Pair(output, peer)
                                }
                            },
                            cancelled = { quitting || isStreaming || guiSessionGeneration != initialGeneration }
                        ) ?: return@LaunchedEffect
                        guiCommands.withLock {
                            if (quitting || isStreaming || guiSessionGeneration != initialGeneration) return@withLock
                            when (requestedMode) {
                                "server" -> {
                                    isServer = true
                                    isStreaming = true
                                    connectionStatus = Strings.get("status_starting_server")

                                    val port = cliArgs.port
                                    val mic  = cliArgs.micPort
                                    val rtp  = cliArgs.rtpPort

                                    if (cliArgs.multicast || cliArgs.rtp || cliArgs.http ||
                                        cliArgs.dlna || cliArgs.snapcast
                                    ) isMulticastMode = true

                                    val protocols = mutableSetOf(StreamingProtocol.WFAS)
                                    if (cliArgs.rtp)  protocols += StreamingProtocol.RTP
                                    if (cliArgs.http) protocols += StreamingProtocol.HTTP
                                    val caps = ServerCapabilities(
                                        protocols  = protocols,
                                        httpPort   = if (cliArgs.http) cliArgs.httpPort else null,
                                        safariMode = cliArgs.httpSafari
                                    )

                                    val micMode = if (cliArgs.mic) cliArgs.micRouting else micRoutingMode

                                    NetworkHandler_v1.launchServerInstance(
                                        audioSettings, port, isMulticastMode, caps,
                                        micMode, selectedServerMicOutput, mic, rtp,
                                        cliArgs.useNativeEngine, selectedMicMixInput,
                                        muteRender = cliArgs.muteRender ?: appSettings.muteRender,
                                        persist = cliArgs.persist ?: appSettings.serverPersist,
                                        dlnaConfig = appSettings.copy(
                                            dlnaEnabled = cliArgs.dlna || appSettings.dlnaEnabled,
                                            dlnaPort = if (cliArgs.dlna) cliArgs.dlnaPort.toString() else appSettings.dlnaPort,
                                            dlnaFormat = if (cliArgs.dlna) cliArgs.dlnaFormat else appSettings.dlnaFormat
                                        ).toDlnaConfig(),
                                        snapcastConfig = appSettings.copy(
                                            snapcastEnabled = cliArgs.snapcast || appSettings.snapcastEnabled,
                                            snapcastPort = if (cliArgs.snapcast) cliArgs.snapcastPort.toString() else appSettings.snapcastPort,
                                            snapcastControlPort = if (cliArgs.snapcast) cliArgs.snapcastControlPort.toString() else appSettings.snapcastControlPort,
                                            snapcastCodec = if (cliArgs.snapcast) cliArgs.snapcastCodec else appSettings.snapcastCodec,
                                            snapcastChunkMs = if (cliArgs.snapcast) cliArgs.snapcastChunkMs else appSettings.snapcastChunkMs,
                                            snapcastBufferMs = if (cliArgs.snapcast) cliArgs.snapcastBufferMs else appSettings.snapcastBufferMs,
                                            snapcastStreamName = if (cliArgs.snapcast) cliArgs.snapcastStreamName else appSettings.snapcastStreamName
                                        ).toSnapcastConfig(),
                                        onAudioFrame = vizSink,
                                        onStatusUpdate = guardedServerStatus(cliArgs.useNativeEngine)
                                    )

                                    if (cliArgs.volume != null) appSettings = appSettings.copy(senderGain = cliArgs.volume.coerceIn(0f, 2f))
                                    if (cliArgs.mute)           NetworkHandler_v1.isMicMuted.value = true
                                }

                                "client" -> {
                                    isServer = false
                                    isStreaming = true
                                    val serverInfo = checkNotNull(startup.second)

                                    connectionStatus = Strings.get("status_contacting_server", serverInfo.ip)
                                    NetworkHandler_v1.endDeviceDiscovery()
                                    NetworkHandler_v1.launchClientInstance(
                                        audioSettings, serverInfo, checkNotNull(startup.first),
                                        cliArgs.mic, selectedClientMic, cliArgs.micPort,
                                        appSettings.connectionSoundEnabled,
                                        appSettings.disconnectionSoundEnabled,
                                        onAudioFrame = vizSink,
                                        outputMixerProvider = { currentReceiverOutput.value },
                                        onStatusUpdate = guardedClientStatus()
                                    )

                                    if (cliArgs.volume != null) appSettings = appSettings.copy(receiverGain = cliArgs.volume.coerceIn(0f, 1f))
                                    if (cliArgs.mute)           NetworkHandler_v1.isMicMuted.value = true
                                }
                            }
                        }
                    } finally { guiStartupPending = false }
                }

                LaunchedEffect(isStreaming) {
                    if (!isStreaming) {
                        lastDisconnectTime = System.currentTimeMillis()
                    }
                }

                LaunchedEffect(isServer, appSettings.autoConnectClientEnabled, appSettings.autoConnectIps, selectedOutputDevice) {
                    if (isServer || !appSettings.autoConnectClientEnabled || selectedOutputDevice == null) return@LaunchedEffect
                    val targets = appSettings.activeAutoConnectTargets()
                    if (targets.isEmpty()) return@LaunchedEffect

                    val defaultPort = streamingPort.toIntOrNull() ?: 9090
                    val mic = micPort.toIntOrNull() ?: 9092
                    val retryMs = appSettings.autoConnectRetryDelaySec.coerceAtLeast(0) * 1000L
                    val intervalMs = (appSettings.autoConnectIntervalSec.coerceAtLeast(1) * 1000L)

                    while (isActive) {
                        val currentTime = System.currentTimeMillis()
                        val canReconnect = (currentTime - lastDisconnectTime) >= retryMs

                        if (!guiStartupPending && !autoConnectSuspended && !isStreaming && canReconnect) {
                            for (target in targets) {
                                if (target.ip.isBlank() || isStreaming) continue

                                // Ogni voce puo' avere la sua porta: due server sulla
                                // stessa rete non sono obbligati a condividerla.
                                val port = target.port ?: defaultPort
                                val knownServer = DesktopDiscoveredPeer.find(discoveredDevices.values, target.ip, port)
                                val isOnline = knownServer != null || NetworkHandler_v1.pingServerUnicast(target.ip, port)
                                if (!isOnline) continue

                                // La chiave arriva dalla custodia di sistema, non dal
                                // file di configurazione. Se la voce dichiara di
                                // averne una ma la custodia non la restituisce
                                // (keyring chiuso, profilo copiato altrove) si salta:
                                // tentare senza chiave finirebbe comunque in un
                                // rifiuto, ma dopo aver annunciato la nostra presenza.
                                val storedKey = target.resolveKey()
                                if (target.hasKey && storedKey == null) {
                                    AppDebug.log("[AUTOCONNECT] key for ${target.displayName()} not available in ${SecretVault.label}, skipping")
                                    connectionStatus = Strings.get("auto_connect_key_locked", target.displayName())
                                    continue
                                }

                                val started = guiCommands.withLock {
                                    if (isServer || isStreaming || autoConnectSuspended) return@withLock false
                                    stopGuiAudio()
                                NetworkHandler_v1.clientPresharedKey = storedKey.orEmpty()
                                NetworkHandler_v1.clientKeyFromInvite = false
                                NetworkHandler_v1.expectedMcastEpoch = null
                                // Senza chiave salvata la finestra si apre solo se
                                // l'utente ha scelto di essere interrotto.
                                NetworkHandler_v1.clientKeyPromptAllowed =
                                    appSettings.autoConnectPromptForKey

                                val targetServer = knownServer ?: ServerInfo(target.ip, false, port, null)
                                lastWfasServer = targetServer
                                autoAttemptPeer = target.ip
                                isStreaming = true
                                connectionStatus = Strings.get("auto_connect_connecting", target.displayName())

                                NetworkHandler_v1.endDeviceDiscovery()
                                NetworkHandler_v1.launchClientInstance(
                                    audioSettings, targetServer, selectedOutputDevice!!,
                                    sendMicrophone, selectedClientMic, mic,
                                    appSettings.connectionSoundEnabled && autoAnnouncedPeer != target.ip,
                                    appSettings.disconnectionSoundEnabled,
                                    onAudioFrame = vizSink,
                                    outputMixerProvider = { currentReceiverOutput.value },
                                    onStatusUpdate = guardedClientStatus()
                                )
                                    true
                                }
                                if (started) break
                            }
                        }
                        delay(intervalMs)
                    }
                }
                LaunchedEffect(Unit) {
                    while (isActive) {
                        val now = System.currentTimeMillis()
                        // Rimuove i server che non si sentono da più di 12 secondi
                        val toRemove = discoveredDevices.filter { now - it.value.lastSeen > 12000L || it.value.lastSeen == 0L }.keys
                        if (toRemove.isNotEmpty()) {
                            toRemove.forEach { discoveredDevices.remove(it) }
                        }
                        delay(5000)
                    }
                }
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize()) {
                        AppContent(
                            appSettings               = appSettings,
                            audioSettings             = audioSettings,
                            isServer                  = isServer,
                            isStreaming               = isStreaming,
                            connectionStatus          = connectionStatus,
                            discoveredDevices         = discoveredDevices,
                            isMulticastMode           = isMulticastMode,
                            sendMicrophone            = sendMicrophone,
                            outputDevices             = outputDevices.value,
                            selectedOutputDevice      = selectedOutputDevice,
                            inputDevices              = inputDevices.value,
                            selectedInputDevice       = null,
                            selectedClientMic         = selectedClientMic,
                            selectedServerMicOutput   = selectedServerMicOutput,
                            streamingPort             = streamingPort,
                            localIp                   = localIp,
                            httpUrl                   = httpUrl,
                            serverVolume              = if (isServer) appSettings.senderGain else appSettings.receiverGain,
                            onServerVolumeChange      = { gain ->
                                appSettings = if (isServer) appSettings.copy(senderGain = gain)
                                    else appSettings.copy(receiverGain = gain)
                            },
                            receiverSources           = receiverSources,
                            onReceiverSourceGain      = { id, gain ->
                                sourceAction {
                                    DesktopReceiverSessions.setGain(id, gain)
                                }
                            },
                            onReceiverSourceMute      = { id, muted ->
                                sourceAction {
                                    DesktopReceiverSessions.setMuted(id, muted)
                                }
                            },
                            onReceiverSourceLatency   = { id, adaptive, ms ->
                                sourceAction {
                                    DesktopReceiverSessions.setLatency(id, adaptive, ms)
                                }
                            },
                            onReceiverSourceRetry     = { id ->
                                scope.launch { guiCommands.withLock {
                                    if (isServer) { stopGuiAudio(); isServer = false }
                                    NetworkHandler_v1.clientKeyPromptAllowed = true
                                    DesktopReceiverSessions.retry(id)
                                    ReceiverSourceStore.update(id) { it.copy(paused = false) }
                                    isStreaming = DesktopReceiverSessions.hasActiveSources()
                                } }
                            },
                            onReceiverSourceRemove    = { id ->
                                scope.launch { guiCommands.withLock {
                                    autoConnectSuspended = true
                                    DesktopReceiverSessions.remove(id)
                                    lastWfasServer = DesktopReceiverSessions.sources.value.lastOrNull()?.peer
                                    if (!ReceiverSourceStore.forget(id)) connectionStatus = Strings.get("receiver_source_save_failed")
                                    isStreaming = DesktopReceiverSessions.hasActiveSources()
                                } }
                            },
                            onReceiverSourceRemotePercent = { id, percent -> DesktopReceiverSessions.setRemoteVolume(id, percent); Unit },
                            isWindowsOS               = isWindowsOS,
                            virtualDriverStatus       = virtualDriverStatus,
                            onDismissPrivacyBanner    = { dontShowAgain ->
                                if (dontShowAgain) appSettings = appSettings.copy(hideWindowsPrivacyBanner = true)
                            },
                            onDismissRoutingBanner    = { dontShowAgain ->
                                if (dontShowAgain) appSettings = appSettings.copy(hideWindowsRoutingBanner = true)
                            },
                            onConnectManual = { rawIp ->
                                val (ip, requestedPort) = NetAddr.splitHostPort(rawIp, streamingPort.toIntOrNull() ?: 9090)
                                scope.launch { guiCommands.withLock {
                                    connectionStatus = Strings.get("status_detecting_mode", NetAddr.display(ip))
                                    require(ip.isNotBlank() && requestedPort in 1..65535) { "Invalid source address or port" }
                                    val port = requestedPort
                                    val known = DesktopDiscoveredPeer.find(discoveredDevices.values, ip, port)
                                    val multicast = known?.isMulticast ?: NetworkHandler_v1.probeIsMulticast(ip, port)
                                    receiveWfas(ServerInfo(ip, multicast, port, known?.capabilities,
                                        serverAudioSettings = known?.serverAudioSettings))
                                } }
                                Unit
                            },
                            onModeChange = { isSrv ->
                                scope.launch { guiCommands.withLock {
                                    stopGuiAudio()
                                    isServer = isSrv
                                    autoConnectSuspended = false
                                    autoAttemptPeer = null; autoAnnouncedPeer = null
                                    discoveredDevices.clear(); NetworkHandler_v1.forgetDiscoveredAddresses()
                                    if (!isSrv) discoverForReceiving()
                                } }
                                Unit
                            },
                            onStartStreaming = startSending,
                            onStopStreaming = stopAudio,
                            onConnectToServer = { info -> scope.launch { guiCommands.withLock { receiveWfas(info) } }; Unit },
                            onRefreshDevices = {
                                discoveredDevices.clear(); NetworkHandler_v1.forgetDiscoveredAddresses()
                                NetworkHandler_v1.endDeviceDiscovery()
                                NetworkHandler_v1.beginDeviceDiscovery { hostname, serverInfo ->
                                    discoveredDevices[hostname] = serverInfo
                                }
                            },
                            onMulticastModeChange      = {
                                isMulticastMode = it
                                appSettings = appSettings.copy(lastMulticastMode = it)
                            },
                            onSendMicrophoneChange     = { send -> sendMicrophone = send },
                            onSelectedOutputDeviceChange      = { selectedOutputDevice = it },
                            onSelectedInputDeviceChange       = { selectedInputDevice  = it },
                            onSelectedClientMicChange         = { selectedClientMic    = it },
                            onSelectedServerMicOutputChange   = { selectedServerMicOutput = it },
                            selectedMicMixInput               = selectedMicMixInput,
                            onMicMixInputSelected             = { selectedMicMixInput = it },
                            onAppSettingsChange               = { updateAppSettings(it) },
                            onOpenSettings                    = { showSettings = true },
                            isMicMuted                        = isMicMuted,
                            onMicMuteToggle                   = { NetworkHandler_v1.isMicMuted.value = !NetworkHandler_v1.isMicMuted.value },
                            micRoutingMode                    = micRoutingMode,
                            onMicRoutingModeChange            = { micRoutingMode = it },
                            rtpDraft                          = rtpDraft,
                            onRtpDraftChange                  = { rtpDraft = it },
                            rtpSaved                          = rtpSaved,
                            onRtpSave                         = { src ->
                                // Stessa destinazione = stessa voce: si aggiorna, non si duplica.
                                val line = src.serialize()
                                val rest = appSettings.rtpSources.filterNot {
                                    RtpSource.deserialize(it)?.let { o ->
                                        o.address == src.address && o.port == src.port
                                    } == true
                                }
                                appSettings = appSettings.copy(rtpSources = rest + line)
                            },
                            onRtpForget                       = { src ->
                                appSettings = appSettings.copy(
                                    rtpSources = appSettings.rtpSources.filterNot {
                                        RtpSource.deserialize(it)?.let { o ->
                                            o.address == src.address && o.port == src.port
                                        } == true
                                    }
                                )
                            },
                            rtpStatus                         = rtpStatus,
                            onRtpListen                       = { src ->
                                rtpReceiver.value?.stop()
                                val rx = RtpReceiver(
                                    source = src,
                                    onStatus = { st -> rtpStatus = st },
                                    onPcm = vizSink,
                                    openPlayer = { rate, ch ->
                                        NetworkHandler_v1.openPcmPlaybackSink(
                                            selectedOutputDevice, rate, ch, if (audioSettings.adaptiveLatency) 120 else audioSettings.latencyMs
                                        )
                                    },
                                    preferredInterface = appSettings.networkInterface
                                )
                                rtpReceiver.value = rx
                                rx.start(scope)
                            },
                            onRtpStop                         = {
                                rtpReceiver.value?.stop()
                                rtpReceiver.value = null
                                rtpStatus = RtpStatus()
                                SpectrumAnalyzer.reset()
                            },
                            snapcastDiscovered                = snapDiscovered,
                            snapcastSaved                     = snapSaved,
                            snapcastHost                      = snapHost,
                            onSnapcastHostChange              = { snapHost = it.trim() },
                            snapcastStreamPort                = snapStreamPort,
                            onSnapcastStreamPortChange        = { snapStreamPort = it },
                            snapcastControlPort               = snapControlPort,
                            onSnapcastControlPortChange       = { snapControlPort = it },
                            onSnapcastSave                    = { srv ->
                                val rest = appSettings.snapcastServers.filterNot {
                                    SnapcastServerRef.deserialize(it)?.let { o ->
                                        o.host == srv.host && o.streamPort == srv.streamPort
                                    } == true
                                }
                                appSettings = appSettings.copy(snapcastServers = rest + srv.serialize())
                            },
                            onSnapcastForget                  = { srv ->
                                appSettings = appSettings.copy(
                                    snapcastServers = appSettings.snapcastServers.filterNot {
                                        SnapcastServerRef.deserialize(it)?.let { o ->
                                            o.host == srv.host && o.streamPort == srv.streamPort
                                        } == true
                                    }
                                )
                            },
                            onSnapcastConnect                 = { connectSnapcast(it) },
                            onSnapcastStop                    = { stopSnapcast() },
                            snapStream                        = snapStream,
                            snapControl                       = snapControl,
                            snapSelfClientId                  = snapSelfId,
                            onSnapSetVolume                   = { id, pct, muted ->
                                snapCtrl.value?.setClientVolume(id, pct, muted)
                            },
                            onSnapSetName                     = { id, name ->
                                snapCtrl.value?.setClientName(id, name)
                            },
                            onSnapSetLatency                  = { id, ms ->
                                snapCtrl.value?.setClientLatency(id, ms)
                            },
                            onSnapGroupMute                   = { id, mute ->
                                snapCtrl.value?.setGroupMute(id, mute)
                            },
                            onSnapGroupStream                 = { id, stream ->
                                snapCtrl.value?.setGroupStream(id, stream)
                            },
                            onSnapGroupName                   = { id, name ->
                                snapCtrl.value?.setGroupName(id, name)
                            },
                            onSnapMoveClient                  = { clientId, targetGroupId ->
                                // In Snapcast un client sta in un gruppo solo, e
                                // l'unico comando disponibile riscrive l'INTERA
                                // composizione del gruppo di destinazione: si
                                // prendono i suoi client attuali e si aggiunge
                                // quello spostato. Al vecchio gruppo ci pensa il
                                // server.
                                val target = snapControl.status.groups
                                    .firstOrNull { it.id == targetGroupId }
                                if (target != null) {
                                    val members = (target.clients.map { it.id } + clientId).distinct()
                                    snapCtrl.value?.setGroupClients(targetGroupId, members)
                                    snapCtrl.value?.refresh()
                                }
                            },
                            onSnapSplitClient                 = { clientId ->
                                // Per staccare un client non esiste un comando
                                // apposito: si riscrive il suo gruppo ATTUALE
                                // senza di lui. Il server dovrebbe poi dargliene
                                // uno nuovo tutto suo -- ma non tutti lo fanno,
                                // e chi non lo fa lascia il client orfano: non
                                // compare piu' da nessuna parte pur continuando
                                // a suonare.
                                val g = snapControl.status.groups
                                    .firstOrNull { grp -> grp.clients.any { it.id == clientId } }
                                if (g != null && g.clients.size > 1) {
                                    val original  = g.clients.map { it.id }
                                    val remaining = original.filter { it != clientId }
                                    snapCtrl.value?.setGroupClients(g.id, remaining)
                                    snapCtrl.value?.refresh()

                                    if (clientId == snapSelfId) {
                                        // Siamo noi: usciti dal gruppo, ci
                                        // ricolleghiamo. Il server assegna un
                                        // gruppo nuovo a chi si presenta senza,
                                        // quindi funziona anche dove il comando
                                        // da solo lascerebbe il client orfano.
                                        // Costa mezzo secondo di silenzio.
                                        scope.launch {
                                            // Prima il comando di uscita deve
                                            // arrivare al server.
                                            delay(250)
                                            snapClient.value?.stop()
                                            snapClient.value = null
                                            // Si resta "in collegamento" per non
                                            // far sparire la schermata d'ascolto
                                            // durante la pausa.
                                            snapStream = snapStream.copy(
                                                state = SnapStreamState.CONNECTING
                                            )
                                            delay(500)
                                            snapServer?.let { startSnapStream(it) }
                                            delay(1200)
                                            snapCtrl.value?.refresh()
                                        }
                                    } else {
                                        // Un client altrui non possiamo
                                        // riavviarlo: resta il tentativo, con
                                        // verifica e ripristino se il server lo
                                        // lascia orfano.
                                        scope.launch {
                                            delay(1500)
                                            val stillListed = snapControl.status.groups.any { grp ->
                                                grp.clients.any { it.id == clientId }
                                            }
                                            if (!stillListed) {
                                                AppDebug.log(
                                                    "[Snapcast/ctrl] il server non ha rialloggiato " +
                                                    "$clientId dopo lo stacco: ripristino il gruppo"
                                                )
                                                snapSplitSupported = false
                                                snapCtrl.value?.setGroupClients(g.id, original)
                                                snapCtrl.value?.refresh()
                                            }
                                        }
                                    }
                                }
                            },
                            snapSplitSupported                = snapSplitSupported,
                            onSnapRefresh                     = { snapCtrl.value?.refresh() }
                        )

                        SettingsScreen(
                            visible                = showSettings,
                            appSettings            = appSettings,
                            audioSettings          = audioSettings,
                            streamingPort          = streamingPort,
                            micPort                = micPort,
                            onAppSettingsChange    = { updateAppSettings(it) },
                            onAudioSettingsChange  = { audioSettings  = it },
                            onStreamingPortChange  = { streamingPort  = it },
                            onMicPortChange        = { micPort        = it },
                            onClose                = { showSettings   = false },
                            onCustomColorChange    = { color -> appSettings = appSettings.copy(customThemeColor = color) },
                            onShowWelcome          = { showSettings = false; showWelcome = true },
                            checkingForUpdate      = checkingForUpdate,
                            onCheckForUpdates      = {
                                if (!checkingForUpdate) {
                                    checkingForUpdate = true
                                    scope.launch {
                                        val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { UpdateChecker.check() }
                                        checkingForUpdate = false
                                        manualUpdateResult = r
                                    }
                                }
                            }
                        )

                        WelcomeScreen(
                            visible   = showWelcome,
                            autoUpdateEnabled = appSettings.autoUpdateCheckEnabled,
                            onAutoUpdateChange = { enabled ->
                                appSettings = appSettings.copy(autoUpdateCheckEnabled = enabled)
                            },
                            onDismiss = {
                                showWelcome = false
                                SettingsRepository.markWelcomeSeen()
                                changelogStandalone = false
                                showChangelog = true
                            }
                        )

                        ChangelogScreen(
                            visible    = showChangelog,
                            standalone = changelogStandalone,
                            onContinue = {
                                showChangelog = false
                                SettingsRepository.setLastSeenChangelog(Changelog.latest.version)
                            }
                        )

                        updateBanner?.let { info ->
                            AlertDialog(
                                onDismissRequest = { updateBanner = null },
                                icon  = { Icon(Icons.Outlined.Update, contentDescription = null) },
                                title = { Text(Strings.get("update_available_title")) },
                                text  = {
                                    Text(Strings.get("update_available_body", info.latest, info.current))
                                },
                                confirmButton = {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = {
                                            runCatching { openUrl(DownloadLinks.site()) }
                                            updateBanner = null
                                        }) { Text(Strings.get("update_download_site")) }
                                        FilledTonalButton(onClick = {
                                            runCatching { openUrl(DownloadLinks.GITHUB_RELEASES) }
                                            updateBanner = null
                                        }) { Text(Strings.get("update_download_github")) }
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { updateBanner = null }) {
                                        Text(Strings.get("update_later"))
                                    }
                                }
                            )
                        }

                        manualUpdateResult?.let { res ->
                            val message = when (res) {
                                is UpdateChecker.Result.Available ->
                                    Strings.get("update_available_body", res.latest, res.current)
                                is UpdateChecker.Result.UpToDate ->
                                    Strings.get("update_uptodate", res.current)
                                is UpdateChecker.Result.Ahead ->
                                    Strings.get("update_ahead_body")
                                is UpdateChecker.Result.Failed ->
                                    Strings.get("update_check_failed")
                            }
                            AlertDialog(
                                onDismissRequest = { manualUpdateResult = null },
                                icon  = { Icon(Icons.Outlined.Update, contentDescription = null) },
                                title = { Text(Strings.get("update_check_title")) },
                                text  = { Text(message) },
                                confirmButton = {
                                    if (res is UpdateChecker.Result.Available) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Button(onClick = {
                                                runCatching { openUrl(DownloadLinks.site()) }
                                                manualUpdateResult = null
                                            }) { Text(Strings.get("update_download_site")) }
                                            FilledTonalButton(onClick = {
                                                runCatching { openUrl(DownloadLinks.GITHUB_RELEASES) }
                                                manualUpdateResult = null
                                            }) { Text(Strings.get("update_download_github")) }
                                        }
                                    } else {
                                        TextButton(onClick = { manualUpdateResult = null }) {
                                            Text(Strings.get("close"))
                                        }
                                    }
                                },
                                dismissButton = {
                                    if (res is UpdateChecker.Result.Available) {
                                        TextButton(onClick = { manualUpdateResult = null }) {
                                            Text(Strings.get("close"))
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
