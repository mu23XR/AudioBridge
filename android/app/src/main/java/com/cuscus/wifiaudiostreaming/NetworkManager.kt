/*
 * Copyright AudioBridge
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

package com.cuscus.wifiaudiostreaming

import com.cuscus.wifiaudiostreaming.diagnostics.RuntimeDiagnostics

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.media.projection.MediaProjection
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.glance.appwidget.state.updateAppWidgetState
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.first
import com.cuscus.wifiaudiostreaming.data.SettingsDataStore
import java.net.BindException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.cancellation.CancellationException

data class ServerInfo(
    val ip: String,
    val isMulticast: Boolean,
    val port: Int,
    val hostname: String = "",
    val securityMode: String? = null,
    val encrypted: Boolean = false,
    val serverSendsMic: Boolean = false,
    val serverWantsMic: Boolean = false,
    val fromBeacon: Boolean = false,
    /** Formato audio annunciato dal server nel beacon di discovery, se presente. */
    val audioFormat: StreamAudioFormat? = null,
    /** Il beacon e' arrivato sul collegamento USB, non sulla rete Wi-Fi. */
    val viaUsb: Boolean = false,
    /** Istante dell'ultimo beacon ricevuto: serve a far scadere i server spariti. */
    val lastSeen: Long = System.currentTimeMillis()
) {
    val acceptsClientMic: Boolean get() = !fromBeacon || serverWantsMic
}

/**
 * Formato audio dichiarato da un server WFAS (campi sr/ch/bd del beacon).
 * La pipeline di riproduzione e' PCM 16 bit: qualunque altra profondita' non e'
 * riproducibile e va rifiutata invece di essere interpretata a caso.
 */
data class StreamAudioFormat(
    val sampleRate: Int,
    val channels: Int,
    val bitDepth: Int
) {
    val isPlayable: Boolean
        get() = bitDepth == 16 &&
                channels in 1..2 &&
                sampleRate in 4000..192000

    fun describe(): String = "$sampleRate Hz, " +
            (if (channels == 1) "mono" else "stereo") + ", $bitDepth bit"

    companion object {
        /** Estrae sr/ch/bd da un beacon gia' diviso su ';'. Null se assenti o non numerici. */
        fun fromBeaconParts(parts: List<String>): StreamAudioFormat? {
            val sr = parts.firstOrNull { it.startsWith("sr=") }?.removePrefix("sr=")
                ?.toFloatOrNull()?.toInt() ?: return null
            val ch = parts.firstOrNull { it.startsWith("ch=") }?.removePrefix("ch=")
                ?.toIntOrNull() ?: return null
            val bd = parts.firstOrNull { it.startsWith("bd=") }?.removePrefix("bd=")
                ?.toIntOrNull() ?: 16
            return StreamAudioFormat(sr, ch, bd)
        }
    }
}

enum class PeerRole { SENDER, RECEIVER }

data class ProtocolMismatch(
    val localVersion: Int,
    val remoteVersion: Int,
    val remoteRole: PeerRole
) {
    val localIsOutdated: Boolean get() = remoteVersion > localVersion
}

@SuppressLint("MissingPermission")
object NetworkManager {

    private const val TAG = "WFAS_DBG"

    /**
     * Un server annuncia la propria presenza ogni 3 secondi. Tolleriamo tre
     * beacon persi prima di considerarlo sparito, cosi' un pacchetto smarrito
     * non lo fa lampeggiare dentro e fuori dalla lista.
     */
    private const val DISCOVERY_TTL_MS = 10_000L
    private const val UDP_QUEUE_SLOTS = 4

    // --- GESTIONE VOLUME ---
    /** Sender-side software gain for the stream sent over the network. */
    private val senderGain = MutableStateFlow(1.0f)
    val serverVolume: kotlinx.coroutines.flow.StateFlow<Float> = senderGain

    /** Receiver-side playback gain. */
    private val receiverGain = MutableStateFlow(1.0f)
    val clientVolume: kotlinx.coroutines.flow.StateFlow<Float> = receiverGain

    internal fun restoreVolumes(sender: Float, receiver: Float) {
        senderGain.value = sender
        receiverGain.value = receiver
    }

    fun setServerVolume(value: Float, propagateToSender: Boolean = true) {
        require(value.isFinite()) { "Sender gain must be finite" }
        val safe = value.coerceIn(0f, 2f)
        senderGain.value = safe
        VolumeMemory.save()
        // Remote-authoritative snapshots mirror here without echoing the same
        // value back into the privileged sender and racing another receiver.
        if (propagateToSender && com.cuscus.wifiaudiostreaming.shizuku.ShizukuAudioBridgeManager.isActive())
            com.cuscus.wifiaudiostreaming.shizuku.ShizukuAudioBridgeManager.setVolume(safe)
    }
    private val clientPlaybackTracks =
        java.util.Collections.newSetFromMap(
            java.util.concurrent.ConcurrentHashMap<AudioTrack, Boolean>()
        )

    fun setClientVolume(value: Float) {
        if (!value.isFinite()) return
        val safe = value.coerceIn(0f, 1f)
        receiverGain.value = safe
        VolumeMemory.save()
        clientPlaybackTracks.forEach { track ->
            runCatching { track.setVolume(safe) }
        }
        ClientSessionController.updateMasterVolume()
    }

    private fun registerClientPlaybackTrack(track: AudioTrack) {
        clientPlaybackTracks.add(track)
        runCatching { track.setVolume(clientVolume.value.coerceIn(0f, 1f)) }
    }

    private fun unregisterClientPlaybackTrack(track: AudioTrack?) {
        if (track != null) clientPlaybackTracks.remove(track)
    }

    var isServerStreaming = false

    @Volatile var activePeerIp: String? = null

    val unicastPeerConnected = MutableStateFlow(false)

    val sessionEncryptedLive = MutableStateFlow(false)

    fun sessionUsesUsb(): Boolean = UsbLink.isUsbPeer(activePeerIp)

    private fun peerHostOf(address: io.ktor.network.sockets.SocketAddress): String? =
        NetAddr.literalHost(address)
            ?: NetAddr.literalHostOfText(address.toString())
            ?: WfasPolicy.hostOf(address.toString())


    // Ogni avvio di stream (server o client) apre una generazione. Il blocco
    // finally di un job superato non deve azzerare lo stato di quello nuovo.
    private val streamGenerationCounter = java.util.concurrent.atomic.AtomicLong(0L)
    private val streamGeneration: Long get() = streamGenerationCounter.get()

    private fun openStreamGeneration(): Long = streamGenerationCounter.incrementAndGet()
    @Volatile var serverStreamsMic = false
    // --------------------------------------

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var donationTimerJob: Job? = null

    fun startDonationTimer(context: Context) {
        donationTimerJob?.cancel()
        val appCtx = context.applicationContext
        donationTimerJob = scope.launch {
            delay(3 * 60 * 1000L)
        }
    }
    fun cancelDonationTimer() { donationTimerJob?.cancel(); donationTimerJob = null }

    private var streamingJob: Job? = null
    private var listeningJob: Job? = null
    private var discoveryExpiryJob: Job? = null
    private var broadcastingJob: Job? = null
    private var originalMediaVolume: Int? = null
    private var micStreamingJob: Job? = null
    @Volatile private var rtpPcmQueue: java.util.concurrent.ArrayBlockingQueue<ByteArray>? = null

    /** Byte di audio che la coda RTP non ha accettato: il timestamp li salta. */
    private val rtpSkippedBytes = java.util.concurrent.atomic.AtomicLong(0L)

    /** Mezzo secondo di ritardo e il ritmo si rifa' da adesso, invece di rincorrere. */
    private val rtpPaceResyncNs = 500_000_000L
    private var rtpJob: Job? = null

    @Volatile private var httpPcmQueue: java.util.concurrent.ArrayBlockingQueue<ByteArray>? = null
    @Volatile private var dlnaManager: DlnaSessionManager? = null
    @Volatile private var snapcastManager: com.cuscus.wifiaudiostreaming.snapcast.SnapcastSessionManager? = null
    private val snapcastLifecycleMutex = kotlinx.coroutines.sync.Mutex()
    private val dlnaLifecycleMutex = kotlinx.coroutines.sync.Mutex()
    private const val DLNA_STOP_TIMEOUT_MS = 4000L
    private const val MULTICAST_SILENCE_TIMEOUT_MS = 30_000L
    // How long an issued auth challenge stays answerable. Long enough for a round
    // trip on a bad link, short enough that an unanswered one does not linger as
    // something to answer later.
    private const val CHALLENGE_TTL_MS = 15_000L

    val dlnaTargets: kotlinx.coroutines.flow.StateFlow<List<DlnaTargetState>>
        get() = DlnaStatus.targets

    fun dlnaClientCount(): Int = dlnaManager?.activeClientCount() ?: 0

    val snapcastSession: kotlinx.coroutines.flow.StateFlow<com.cuscus.wifiaudiostreaming.snapcast.SnapcastSessionState>
        get() = com.cuscus.wifiaudiostreaming.snapcast.SnapcastStatus.session

    fun snapcastClientCount(): Int = snapcastManager?.activeClientCount() ?: 0

    private suspend fun stopSnapcastSession() {
        snapcastLifecycleMutex.withLock { stopSnapcastSessionLocked() }
    }

    private fun stopSnapcastSessionLocked() {
        val manager = snapcastManager
        snapcastManager = null
        if (manager != null) runCatching { manager.stop() }
    }

    private suspend fun restartSnapcastSession(
        context: Context,
        config: com.cuscus.wifiaudiostreaming.snapcast.SnapcastServerConfig,
        sampleRate: Int,
        channels: Int,
        networkInterfaceName: String
    ) {
        snapcastLifecycleMutex.withLock {
            stopSnapcastSessionLocked()
            if (!config.enabled) return@withLock
            DlnaMulticastLock.install(context)
            val manager = com.cuscus.wifiaudiostreaming.snapcast.SnapcastSessionManager(
                context = context.applicationContext,
                scope = scope,
                config = config,
                sampleRate = sampleRate,
                channels = channels,
                bitDepth = 16,
                hostName = android.os.Build.MODEL ?: "Android",
                serverVersion = UpdateChecker.currentVersion(context),
                persistenceFile = java.io.File(context.filesDir, "snapcast-state.json"),
                localAddressProvider = { getLocalIpAddress(context) },
                preferredInterfaceProvider = { getWifiNetworkInterface(networkInterfaceName) }
            )
            snapcastManager = manager
            manager.start()
        }
    }

    private suspend fun stopDlnaSession() {
        dlnaLifecycleMutex.withLock { stopDlnaSessionLocked() }
    }

    private suspend fun stopDlnaSessionLocked() {
        val manager = dlnaManager
        dlnaManager = null
        if (manager != null) {
            runCatching { withTimeoutOrNull(DLNA_STOP_TIMEOUT_MS) { manager.stop() } }
        }
    }

    private suspend fun restartDlnaSession(
        context: Context,
        config: DlnaServerConfig,
        sampleRate: Int,
        channels: Int,
        networkInterfaceName: String
    ) {
        dlnaLifecycleMutex.withLock {
            stopDlnaSessionLocked()
            if (!config.enabled) return@withLock
            DlnaMulticastLock.install(context)
            val manager = DlnaSessionManager(
                scope = scope,
                sampleRate = sampleRate,
                channels = channels,
                mediaPort = config.port,
                preference = config.preference,
                selectedUdns = config.selectedUdns,
                streamTitle = config.title,
                localAddressProvider = { getLocalIpAddress(context) },
                preferredInterfaceProvider = { getWifiNetworkInterface(networkInterfaceName) }
            )
            dlnaManager = manager
            manager.start()
        }
    }

    @Volatile private var activeInternalRecord: AudioRecord? = null
    @Volatile private var activeMicRecord: AudioRecord? = null
    private var httpJob: Job? = null
    private var httpServerSocket: java.net.ServerSocket? = null

    private object NetworkSettings {
        const val DISCOVERY_PORT = 9091
        const val DISCOVERY_MESSAGE = "WIFI_AUDIO_STREAMER_DISCOVERY"
        const val CLIENT_HELLO_MESSAGE = "HELLO_FROM_CLIENT"
        const val MULTICAST_GROUP_IP = "239.255.0.1"
        const val INCOMPATIBLE_PREFIX = "WFAS_INCOMPATIBLE"
        const val HELLO_ACK_PREFIX = "HELLO_ACK"
        const val PENDING_MESSAGE = "WFAS_PENDING"
        const val AUTH_REQUIRED_PREFIX = "WFAS_AUTH_REQUIRED"
        const val UNAUTHORIZED_MESSAGE = "WFAS_UNAUTHORIZED"

        const val BUSY_MESSAGE = "WFAS_BUSY"
    }

    const val WFAS_PROTOCOL_VERSION = 2

    const val MULTICAST_GROUP = NetworkSettings.MULTICAST_GROUP_IP

    @Volatile var securityMode: String = "OFF"
    @Volatile var authKey: String = ""
    @Volatile var encryptionEnabled: Boolean = false
    fun configureSecurity(mode: String, key: String, encrypt: Boolean = false) {
        securityMode = mode; authKey = key; encryptionEnabled = encrypt
    }

    @Volatile private var audioPrewarmed = false
    fun prewarmAudio() {
        if (audioPrewarmed) return
        audioPrewarmed = true
        Thread {
            runCatching {
                val sr = 48000
                val ch = AudioFormat.CHANNEL_OUT_STEREO
                val minBuf = AudioTrack.getMinBufferSize(sr, ch, AudioFormat.ENCODING_PCM_16BIT)
                if (minBuf <= 0) return@runCatching
                val builder = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sr)
                            .setChannelMask(ch)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                }
                val track = builder.build()
                track.write(ByteArray(minBuf), 0, minBuf, AudioTrack.WRITE_BLOCKING)
                track.play()
                Thread.sleep(80)
                runCatching { track.stop() }
                track.release()
            }
        }.apply { isDaemon = true; start() }
    }
    // Pre-shared key used only when acting as a CLIENT (scripting). The interactive
    // client leaves this empty and gets the key from the on-connect dialog.
    @Volatile var clientPresharedKey: String = ""
    @Volatile var micSendDir: WfasCrypto.Dir? = null
    var onAuthRequest: ((peer: String) -> Boolean)? = null

    class McastSession {
        @Volatile var dir: WfasCrypto.Dir? = null
        @Volatile var beacon: ByteArray? = null
        @Volatile var epoch: Long = 0L
        @Volatile var key: String = ""
        @Volatile var beaconUrgent: Boolean = false
    }

    data class McastSnapshot(val epoch: Long, val key: String, val encrypted: Boolean)

    val mcastSession = MutableStateFlow<McastSnapshot?>(null)
    @Volatile private var mcastRekey: (suspend (String) -> Unit)? = null

    val pendingEpochMismatch = MutableStateFlow(false)
    fun clearEpochMismatch() { pendingEpochMismatch.value = false }

    @Volatile var clientKeyFromInvite: Boolean = false

    val pendingInviteRejected = MutableStateFlow(false)
    fun clearInviteRejected() { pendingInviteRejected.value = false }

    @Volatile var expectedMcastEpoch: Long? = null

    suspend fun rekeyMulticast(newKey: String): Boolean {
        val fn = mcastRekey ?: return false
        fn(newKey)
        return true
    }

    @Volatile var keyPromptEnabled: Boolean = false
    val pendingKeyRequest = MutableStateFlow<Boolean?>(null)
    private var keyDeferred: CompletableDeferred<String?>? = null
    private val keyPromptMutex = kotlinx.coroutines.sync.Mutex()
    val pendingKeySource = MutableStateFlow("")
    suspend fun requestKeyFromUi(wrong: Boolean, source: String = ""): String? = keyPromptMutex.withLock {
        if (!keyPromptEnabled) return null
        val d = CompletableDeferred<String?>()
        keyDeferred = d
        pendingKeySource.value = source
        pendingKeyRequest.value = wrong
        try { d.await() } finally {
            pendingKeyRequest.value = null
            pendingKeySource.value = ""
            if (keyDeferred === d) keyDeferred = null
        }
    }
    fun submitKey(key: String?) { keyDeferred?.complete(key); keyDeferred = null }

    @Volatile var authPromptEnabled: Boolean = false
    val pendingAuthRequest = MutableStateFlow<String?>(null)
    private var authDeferred: CompletableDeferred<Boolean>? = null
    suspend fun requestAuthFromUi(peer: String): Boolean {
        if (!authPromptEnabled) return true
        val d = CompletableDeferred<Boolean>()
        authDeferred = d
        pendingAuthRequest.value = peer
        return try {
            withTimeoutOrNull(60_000) { d.await() } ?: false
        } finally {
            pendingAuthRequest.value = null
        }
    }
    fun submitAuth(allow: Boolean) { authDeferred?.complete(allow); authDeferred = null }

    private const val MIC_HEADER_SIZE = 10
    private const val MIC_MAGIC_0: Byte = 0x57
    private const val MIC_MAGIC_1: Byte = 0x46

    private fun writeMicHeader(dst: ByteArray, seq: Int, silence: Boolean) {
        dst[0] = MIC_MAGIC_0
        dst[1] = MIC_MAGIC_1
        dst[2] = WFAS_PROTOCOL_VERSION.toByte()
        dst[3] = if (silence) 0x01 else 0x00
        dst[4] = ((seq shr 8) and 0xFF).toByte()
        dst[5] = (seq and 0xFF).toByte()
        dst[6] = 0; dst[7] = 0; dst[8] = 0; dst[9] = 0
    }

    val protocolMismatch = MutableStateFlow<ProtocolMismatch?>(null)

    private fun clientHelloMessage(): String = "${NetworkSettings.CLIENT_HELLO_MESSAGE};v=$WFAS_PROTOCOL_VERSION"
    private fun helloAckMessage(): String = "${NetworkSettings.HELLO_ACK_PREFIX};v=$WFAS_PROTOCOL_VERSION"
    private fun incompatibleMessage(): String = "${NetworkSettings.INCOMPATIBLE_PREFIX};v=$WFAS_PROTOCOL_VERSION"

    private fun parseProtocolVersion(message: String): Int =
        message.split(";").firstOrNull { it.startsWith("v=") }
            ?.removePrefix("v=")?.trim()?.toIntOrNull() ?: 0

    private fun signalProtocolMismatch(remoteVersion: Int, remoteRole: PeerRole) {
        if (protocolMismatch.value == null) {
            protocolMismatch.value =
                ProtocolMismatch(WFAS_PROTOCOL_VERSION, remoteVersion, remoteRole)
        }
    }

    internal fun reportProtocolMismatch(remoteVersion: Int, remoteRole: PeerRole) =
        signalProtocolMismatch(remoteVersion, remoteRole)

    private const val SILENT_PEER_TIMEOUT_MS = 6000L

    fun clearProtocolMismatch() { protocolMismatch.value = null }

    val connectionStatus = MutableStateFlow("")
    val discoveredDevices = MutableStateFlow<Map<String, ServerInfo>>(emptyMap())
    val isStreamingCurrent = MutableStateFlow(false)
    val isMicMuted = MutableStateFlow(false)
    var autoConnectOwnsListening = false
    val lastSeenDevices = mutableMapOf<String, Pair<ServerInfo, Long>>()

    val networkRevision = MutableStateFlow(0)
    val localIpAddress = MutableStateFlow("")
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private data class BroadcastParams(
        val context: Context,
        val isMulticast: Boolean,
        val streamingPort: Int,
        val networkInterfaceName: String,
        val rtpEnabled: Boolean,
        val audioFormat: StreamAudioFormat?
    )
    private var lastBroadcastParams: BroadcastParams? = null

    private fun isTransientNetworkError(e: Throwable): Boolean {
        var cause: Throwable? = e
        var depth = 0
        while (cause != null && depth < 5) {
            when (cause) {
                is java.net.PortUnreachableException -> return true
                is java.net.NoRouteToHostException -> return true
                is java.net.SocketException -> {
                    val m = cause.message?.lowercase().orEmpty()
                    if (m.contains("enetunreach") || m.contains("network is unreachable") ||
                        m.contains("enonet") || m.contains("ehostunreach") ||
                        m.contains("no route to host") || m.contains("enetdown") ||
                        m.contains("network is down") || m.contains("eaddrnotavail") ||
                        m.contains("cannot assign requested address")
                    ) return true
                }
            }
            cause = cause.cause
            depth++
        }
        return false
    }

    private const val PLC_XFADE_FRAMES = 96

    internal fun bestContinuationFrame(ref: ShortArray, channels: Int, refFrames: Int): Int {
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

    /**
     * Riempie un buco ripetendo l'ultimo audio buono, con la giuntura scelta
     * dove si sente meno. La usa anche il client Snapcast: e' la stessa
     * mascheratura, e duplicarla vorrebbe dire farla divergere.
     */
    internal fun rampedConceal(ref: ByteArray, wantedBytes: Int, frameSize: Int): ByteArray? {
        if (frameSize <= 0) return null
        val total = wantedBytes - (wantedBytes % frameSize)
        val refUsable = ref.size - (ref.size % frameSize)
        if (total < frameSize || refUsable < frameSize * 4) return null

        val channels = (frameSize / 2).coerceAtLeast(1)
        val totalFrames = total / frameSize
        val refFrames = refUsable / frameSize

        val src = ShortArray(refUsable / 2)
        ByteBuffer.wrap(ref, 0, refUsable).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(src)

        val offset = bestContinuationFrame(src, channels, refFrames)
        val period = (2 * (refFrames - 1)).coerceAtLeast(1)
        val outFrames = totalFrames + PLC_XFADE_FRAMES
        val out = ByteArray(outFrames * frameSize)
        val dst = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()

        for (i in 0 until outFrames) {
            val p = (offset + i) % period
            val idx = if (p < refFrames) p else period - p
            val gain = if (i < totalFrames) 1f
                       else (1f - (i - totalFrames + 1).toFloat() / PLC_XFADE_FRAMES).coerceAtLeast(0f)
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

    internal fun crossfadeIntoReal(real: ByteArray, off: Int, len: Int, tail: ByteArray, frameSize: Int): ByteArray {
        val out = real.copyOfRange(off, off + len)
        val channels = (frameSize / 2).coerceAtLeast(1)
        val n = minOf(tail.size / frameSize, len / frameSize)
        if (n <= 0) return out
        val tb = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val ob = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
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

    private fun restartBroadcastOnNetworkChange() {
        val p = lastBroadcastParams ?: return
        if (broadcastingJob?.isActive != true) return
        scope.launch {
            val job = broadcastingJob
            broadcastingJob = null
            if (job != null) runCatching { job.cancelAndJoin() }
            Log.d(TAG, "[BROADCAST] rete cambiata: beacon riavviato sull'interfaccia nuova")
            startBroadcastingPresence(
                p.context, p.isMulticast, p.streamingPort,
                p.networkInterfaceName, p.rtpEnabled, p.audioFormat
            )
        }
    }

    fun startNetworkWatch(context: Context) {
        if (networkCallback != null) return
        val app = context.applicationContext
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        localIpAddress.value = getLocalIpAddress(app)
        val cb = object : ConnectivityManager.NetworkCallback() {
            private fun refresh(reason: String) {
                val ip = getLocalIpAddress(app)
                val changed = ip != localIpAddress.value
                localIpAddress.value = ip
                Log.d(TAG, "[NET] $reason ip=$ip changed=$changed")
                // Underlying Wi-Fi can disappear while a VPN/default IP stays
                // unchanged. Receiver sessions compare their own route fingerprints.
                networkRevision.value++
                if (changed) restartBroadcastOnNetworkChange()
            }
            override fun onAvailable(network: android.net.Network) = refresh("available")
            override fun onLost(network: android.net.Network) = refresh("lost")
            override fun onCapabilitiesChanged(
                network: android.net.Network,
                caps: NetworkCapabilities
            ) = refresh("caps")
            override fun onLinkPropertiesChanged(
                network: android.net.Network,
                props: android.net.LinkProperties
            ) = refresh("link")
        }
        networkCallback = cb
        try {
            val request = android.net.NetworkRequest.Builder()
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .build()
            cm.registerNetworkCallback(request, cb)
        } catch (failure: RuntimeException) {
            networkCallback = null
            RuntimeDiagnostics.event("NETWORK_WATCH_FAILED", failure.javaClass.simpleName, true)
            throw failure
        }
    }

    fun stopNetworkWatch(context: Context) {
        val cb = networkCallback ?: return
        networkCallback = null
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        runCatching { cm.unregisterNetworkCallback(cb) }
    }

    // ── Riduzione del rumore lato ricevitore (opt-in, modalita' sviluppatore) ──
    private val noiseReducer = com.cuscus.wifiaudiostreaming.dsp.NoiseReducer()
    @Volatile private var nrEnabled = false
    private var nrScratch = ShortArray(0)

    /** Modificabile a caldo: il flusso in riproduzione non va riavviato. */
    fun setNoiseReduction(enabled: Boolean, strengthPercent: Int) {
        ClientSessionController.setNoiseReduction(enabled, strengthPercent)
        noiseReducer.setStrength(strengthPercent.coerceIn(0, 100) / 100f)
        if (enabled && !nrEnabled) noiseReducer.reset()
        nrEnabled = enabled
        Log.d(TAG, "[NR] enabled=$enabled strength=$strengthPercent%")
    }

    private suspend fun prepareNoiseReducer(context: Context, sampleRate: Int, channels: Int) {
        // suspend, non runBlocking: viene chiamata da dentro una coroutine e
        // bloccare quel thread potrebbe incastrare il dispatcher.
        val s = runCatching { SettingsDataStore(context).settingsFlow.first() }.getOrNull()
        val on = s != null && s.developerMode && s.noiseReductionEnabled
        noiseReducer.init(sampleRate, channels)
        noiseReducer.setStrength((s?.noiseReductionStrength ?: 50).coerceIn(0, 100) / 100f)
        noiseReducer.reset()
        nrEnabled = on
        Log.d(TAG, "[NR] prepare: on=$on sr=$sampleRate ch=$channels")
    }

    /**
     * Applica il denoiser a PCM 16 bit little endian, in place.
     * Non fa nulla se disattivato: il percorso normale resta a costo zero.
     */
    private fun denoiseInPlace(buf: ByteArray, offset: Int, len: Int) {
        if (!nrEnabled || len < 2) return
        val samples = len / 2
        if (nrScratch.size < samples) nrScratch = ShortArray(samples)
        val sc = nrScratch
        var bi = offset
        for (i in 0 until samples) {
            sc[i] = ((buf[bi].toInt() and 0xFF) or (buf[bi + 1].toInt() shl 8)).toShort()
            bi += 2
        }
        noiseReducer.process(sc, 0, samples)
        bi = offset
        for (i in 0 until samples) {
            val v = sc[i].toInt()
            buf[bi] = (v and 0xFF).toByte()
            buf[bi + 1] = ((v shr 8) and 0xFF).toByte()
            bi += 2
        }
    }

    @SuppressLint("DefaultLocale", "MissingPermission")
    fun getLocalIpAddress(context: Context): String {
        UsbLink.activeInterface()?.let { iface ->
            runCatching {
                iface.inetAddresses.toList()
                    .filterIsInstance<java.net.Inet4Address>()
                    .firstOrNull { !it.isLoopbackAddress }
                    ?.hostAddress
            }.getOrNull()?.let { return it }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val transportPriority = listOf(
                    NetworkCapabilities.TRANSPORT_WIFI,
                    NetworkCapabilities.TRANSPORT_ETHERNET,
                    NetworkCapabilities.TRANSPORT_VPN
                )
                for (transport in transportPriority) {
                    for (network in cm.allNetworks) {
                        val nc = cm.getNetworkCapabilities(network) ?: continue
                        if (!nc.hasTransport(transport)) continue
                        val lp = cm.getLinkProperties(network) ?: continue
                        for (la in lp.linkAddresses) {
                            val addr = la.address
                            if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                                return addr.hostAddress ?: continue
                            }
                        }
                    }
                }
            } catch (e: Exception) {}
        } else {
            try {
                @Suppress("DEPRECATION")
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val ipAddress = wifiManager.connectionInfo.ipAddress
                if (ipAddress != 0) {
                    return String.format(
                        "%d.%d.%d.%d",
                        ipAddress and 0xff,
                        ipAddress shr 8 and 0xff,
                        ipAddress shr 16 and 0xff,
                        ipAddress shr 24 and 0xff
                    )
                }
            } catch (e: Exception) {}
        }
        try {
            java.net.NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { intf ->
                if (!intf.isLoopback && intf.isUp) {
                    intf.inetAddresses?.toList()?.forEach { addr ->
                        if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                            val h = addr.hostAddress ?: return@forEach
                            if (!UsbLink.isTetherInterfaceName(intf.name)) {
                                return h
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {}
        return NetAddr.bestLocalAddress()
    }

    @SuppressLint("MissingPermission")
    private fun isVpnActive(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.allNetworks.any { network ->
                cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
        } catch (e: Exception) { false }
    }

    @SuppressLint("MissingPermission")
    private fun getWifiNetworkObject(context: Context): android.net.Network? {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.allNetworks.firstOrNull { network ->
                val nc = cm.getNetworkCapabilities(network) ?: return@firstOrNull false
                nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            }
        } catch (e: Exception) { null }
    }

    suspend fun probeIsMulticast(ip: String, port: Int, context: Context? = null,
                                 networkInterfaceName: String = "Auto"): Boolean? = withContext(Dispatchers.IO) {
        val remote = try { InetAddress.getByName(ip) } catch (failure: Exception) {
            RuntimeDiagnostics.event("MODE_PROBE_ADDRESS_ERROR", failure.javaClass.simpleName, true)
            return@withContext null
        }
        val path = try { context?.let { DestinationNetwork.select(it, remote, networkInterfaceName) } }
        catch (failure: Exception) {
            RuntimeDiagnostics.event("MODE_PROBE_ROUTE_ERROR", failure.javaClass.simpleName, true)
            return@withContext null
        }
        if (context != null && path == null) return@withContext null
        try {
            DatagramSocket(null).use { socket ->
                path?.bind(socket)
                socket.bind(java.net.InetSocketAddress(path?.localAddress, 0))
                socket.connect(remote, port); socket.soTimeout = 1000
                val probe = "MODE_PROBE".toByteArray(Charsets.US_ASCII)
                socket.send(DatagramPacket(probe, probe.size))
                val buffer = ByteArray(256); val reply = DatagramPacket(buffer, buffer.size)
                socket.receive(reply)
                when (String(buffer, 0, reply.length, Charsets.US_ASCII).trim()) {
                    "UNICAST", "WFAS_BUSY" -> return@withContext false
                    "MULTICAST" -> return@withContext true
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            RuntimeDiagnostics.event("MODE_PROBE_NO_REPLY", failure.javaClass.simpleName)
        }
        // Multicast has no handshake. Require a matching sender's real WFAS
        // packet instead of interpreting every timeout as multicast.
        if (context == null) return@withContext null
        val owner = "mode-probe-${System.nanoTime()}"
        WfasMulticastLock.acquire(context, owner)
        try {
            val iface = path?.name?.let { NetworkInterface.getByName(it) }
                ?: getWifiNetworkInterface(networkInterfaceName) ?: return@withContext null
            MulticastSocket(null).use { socket ->
                socket.reuseAddress = true; path?.bind(socket)
                socket.bind(java.net.InetSocketAddress(port)); socket.soTimeout = 200
                socket.joinGroup(java.net.InetSocketAddress(MULTICAST_GROUP, port), iface)
                val deadline = android.os.SystemClock.elapsedRealtime() + 1200
                val bytes = ByteArray(65536); val packet = DatagramPacket(bytes, bytes.size)
                while (android.os.SystemClock.elapsedRealtime() < deadline && currentCoroutineContext().isActive) {
                    try { packet.length = bytes.size; socket.receive(packet) } catch (_: SocketTimeoutException) { continue }
                    if (packet.address == remote && packet.length >= 10 && bytes[0] == 0x57.toByte() && bytes[1] == 0x46.toByte())
                        return@withContext true
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { RuntimeDiagnostics.event("MODE_PROBE_FAILED", failure.javaClass.simpleName, true) }
        finally { WfasMulticastLock.release(owner) }
        null
    }
    private fun CoroutineScope.launchRtpSidecar(
        sampleRate: Int,
        channels: Int,
        port: Int,
        isMulticast: Boolean,
        clientIp: String?, // Usato se siamo in Unicast
        wifiIface: NetworkInterface?
    ) = launch(Dispatchers.IO) {
        val queue = java.util.concurrent.ArrayBlockingQueue<ByteArray>(25)
        rtpPcmQueue = queue
        rtpSkippedBytes.set(0L)

        var sequenceNumber = (Math.random() * 65535).toInt()
        var rtpTimestamp = (Math.random() * Int.MAX_VALUE).toLong()
        val ssrc = (Math.random() * Int.MAX_VALUE).toLong()
        val rtpFrameBytes = channels * 2
        val rtpClockRate = sampleRate.toLong().coerceAtLeast(8000L)

        /** Quando tocca al prossimo pacchetto. 0 = non abbiamo ancora un ritmo. */
        var nextDueNs = 0L
        /** Da mettere sul primo pacchetto dopo un buco, come vuole RFC 3551. */
        var marker = false

        val socket = if (isMulticast) {
            MulticastSocket().apply {
                timeToLive = 4
                MulticastNet.chooseSendInterface(wifiIface)?.let { networkInterface = it }
            }
        } else DatagramSocket()

        val destAddress = if (isMulticast) {
            InetAddress.getByName("239.255.0.1")
        } else {
            InetAddress.getByName(clientIp ?: "255.255.255.255")
        }

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

                    // Conversione Little-Endian -> Big-Endian (Network Byte Order)
                    val leBuf = java.nio.ByteBuffer.wrap(pcmLeBytes, offset, chunkSize).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val beBuf = buf.asShortBuffer()
                    while (leBuf.hasRemaining()) beBuf.put(leBuf.get())

                    val samplesInChunk = chunkSize / 2 / channels

                    // Il ritmo: ogni pacchetto parte quando gli tocca.
                    //
                    // Il resto del server manda a raffica, e per WFAS va bene:
                    // il suo client sa a quale campione va suonato ogni
                    // pacchetto e ha una coda che assorbe le ondate. Un
                    // ricevitore RTP no -- RTP e' un flusso e basta, e chi lo
                    // ascolta si aspetta che arrivi al ritmo con cui si suona.
                    // Un blocco intero di pacchetti insieme, e poi silenzio
                    // fino al blocco dopo, vuol dire una coda che si svuota
                    // fino in fondo a ogni giro.
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

                    sequenceNumber = (sequenceNumber + 1) and 0xFFFF
                    rtpTimestamp += samplesInChunk
                    offset += chunkSize
                }
            }
        } catch (e: Exception) {
            if (e !is CancellationException) e.printStackTrace()
        } finally {
            socket.close()
            rtpPcmQueue = null
        }
    }

    private fun getAllLocalIpAddresses(): Set<String> {
        val ipSet = mutableSetOf<String>()
        try {
            NetworkInterface.getNetworkInterfaces().toList().forEach { iface ->
                iface.inetAddresses.toList().forEach { addr ->
                    if (!addr.isLoopbackAddress) {
                        val h = addr.hostAddress ?: return@forEach
                        ipSet.add(h)
                        ipSet.add(h.substringBefore('%'))
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return ipSet
    }

    fun getWifiNetworkInterface(preferredName: String = "Auto"): NetworkInterface? {
        return try {
            if (preferredName == "Auto") {
                UsbLink.activeInterface()?.let { return it }
            }
            val interfaces = NetworkInterface.getNetworkInterfaces().toList()

            if (preferredName != "Auto") {
                // Se l'utente ha forzato una scheda, ignoriamo i filtri (fondamentale per le VPN)
                interfaces.firstOrNull { it.displayName == preferredName || it.name == preferredName }
            } else {
                val usable = interfaces.filter { isStreamingInterface(it) }
                usable.maxByOrNull { streamingInterfaceScore(it) }
                    ?: interfaces.firstOrNull { iface ->
                        runCatching {
                            iface.isUp &&
                                    !iface.isLoopback &&
                                    !iface.isVirtual &&
                                    !UsbLink.isTetherInterfaceName(iface.name) &&
                                    iface.inetAddresses.toList().any { !it.isLoopbackAddress }
                        }.getOrDefault(false)
                    }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun isStreamingInterface(iface: NetworkInterface): Boolean = runCatching {
        iface.isUp &&
                !iface.isLoopback &&
                !iface.isVirtual &&
                !UsbLink.isTetherInterfaceName(iface.name) &&
                iface.inetAddresses.toList().any {
                    !it.isLoopbackAddress && !it.isLinkLocalAddress
                }
    }.getOrDefault(false)

    private fun streamingInterfaceScore(iface: NetworkInterface): Int {
        val name = iface.name?.lowercase().orEmpty()
        var s = 0
        if (name.startsWith("wlan") || name.startsWith("wifi") || name.startsWith("ap")) s += 200
        if (name.startsWith("eth") || name.startsWith("en")) s += 120
        if (name.startsWith("rmnet") || name.startsWith("ccmni") ||
            name.startsWith("pdp") || name.startsWith("radio")
        ) s -= 150
        if (name.startsWith("dummy") || name.startsWith("tun") ||
            name.startsWith("ppp") || name.startsWith("sit") || name.startsWith("clat")
        ) s -= 400
        if (runCatching { iface.supportsMulticast() }.getOrDefault(false)) s += 60
        if (NetAddr.interfaceHasV4(iface)) s += 80
        if (NetAddr.interfaceHasV6(iface)) s += 20
        return s
    }

    fun startListeningForDevices(context: Context, networkInterfaceName: String = "Auto") {
        if (isListeningActive()) return
        discoveredDevices.value = emptyMap()
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val multicastLock = wifiManager.createMulticastLock("wifi_audio_streaming_discovery_lock")
        multicastLock.setReferenceCounted(true)

        // Un server che sparisce senza dire BYE (app chiusa, WiFi staccato, crash)
        // resterebbe in lista per sempre. Il beacon arriva ogni 3s: dopo DISCOVERY_TTL_MS
        // senza notizie lo consideriamo andato.
        discoveryExpiryJob?.cancel()
        discoveryExpiryJob = scope.launch {
            while (isActive) {
                delay(2000)
                val now = System.currentTimeMillis()
                val current = discoveredDevices.value
                val alive = current.filterValues { now - it.lastSeen <= DISCOVERY_TTL_MS }
                if (alive.size != current.size) {
                    val gone = current.keys - alive.keys
                    Log.d(TAG, "[DISCOVERY] Scaduti (nessun beacon da ${DISCOVERY_TTL_MS}ms): $gone")
                    discoveredDevices.value = alive
                }
            }
        }

        listeningJob = scope.launch {
            multicastLock.acquire()
            try {
                while (isActive) {
                    var socket: MulticastSocket? = null
                    try {
                        val localIps = getAllLocalIpAddresses()
                        val groupAddress = InetAddress.getByName(NetworkSettings.MULTICAST_GROUP_IP)

                        socket = MulticastSocket(NetworkSettings.DISCOVERY_PORT).apply {
                            reuseAddress = true
                            getWifiNetworkInterface(networkInterfaceName)?.let { networkInterface = it }
                            MulticastNet.joinAllGroups(this)
                            soTimeout = 5000
                        }

                        val buffer = ByteArray(1024)
                        val packet = DatagramPacket(buffer, buffer.size)

                        while (isActive) {
                            try {
                                packet.length = buffer.size
                                socket.receive(packet)
                                val remoteIp = packet.address.hostAddress
                                val message = String(packet.data, 0, packet.length).trim()

                                if (remoteIp != null && remoteIp !in localIps && message.startsWith(NetworkSettings.DISCOVERY_MESSAGE)) {
                                    Log.d(TAG, "[DISCOVERY] Received from $remoteIp: $message")
                                    val parts = message.split(";")
                                    if (parts.size >= 4) {
                                        val hostname = parts[1]

                                        if (message.contains("BYE")) {
                                            Log.d(TAG, "[DISCOVERY] BYE from $hostname, removing from list")
                                            discoveredDevices.value = discoveredDevices.value - hostname
                                        } else {
                                            val isMulticast = parts[2].equals("MULTICAST", ignoreCase = true)
                                            val port = parts[3].toIntOrNull() ?: continue
                                            val authMode = parts.firstOrNull { it.startsWith("auth=") }
                                                ?.removePrefix("auth=")?.uppercase()
                                            val encrypted = parts.firstOrNull { it.startsWith("enc=") }
                                                ?.removePrefix("enc=") == "1"
                                            val micTok = parts.firstOrNull { it.startsWith("mic=") }?.removePrefix("mic=")
                                            val advertisedFormat = StreamAudioFormat.fromBeaconParts(parts)
                                            val serverInfo = ServerInfo(
                                                ip = remoteIp, isMulticast = isMulticast, port = port,
                                                hostname = hostname,
                                                securityMode = authMode, encrypted = encrypted,
                                                serverSendsMic = micTok?.contains("tx") == true,
                                                serverWantsMic = micTok?.contains("rx") == true,
                                                fromBeacon = true,
                                                audioFormat = advertisedFormat,
                                                viaUsb = UsbLink.isUsbPeerDetected(remoteIp),
                                                lastSeen = System.currentTimeMillis()
                                            )
                                            Log.d(TAG, "[DISCOVERY] Found server: hostname=$hostname ip=$remoteIp isMulticast=$isMulticast port=$port")

                                            val currentMap = discoveredDevices.value
                                            val known = currentMap[hostname]

                                            // Con la discovery dual stack lo stesso server
                                            // annuncia su IPv4 e IPv6: senza arbitrato
                                            // vincerebbe l'ultimo beacon arrivato, spesso un
                                            // link-local IPv6 verso cui la connessione fallisce.
                                            if (!MulticastNet.shouldAdoptPeer(
                                                    known?.ip, known?.lastSeen ?: 0L,
                                                    remoteIp, serverInfo.lastSeen
                                                )
                                            ) {
                                                Log.d(TAG, "[DISCOVERY] $hostname: tengo ${known?.ip}, ignoro $remoteIp")
                                                continue
                                            }

                                            // lastSeen cambia a ogni beacon: confrontarlo
                                            // farebbe riemettere la lista ogni 3s. Si
                                            // aggiorna quando cambia qualcosa di reale, o
                                            // periodicamente per tenere vivo il TTL.
                                            val sameData = known != null &&
                                                known.copy(lastSeen = serverInfo.lastSeen) == serverInfo
                                            val staleTimestamp = known != null &&
                                                serverInfo.lastSeen - known.lastSeen > DISCOVERY_TTL_MS / 3
                                            if (!sameData || staleTimestamp) {
                                                discoveredDevices.value = currentMap + (hostname to serverInfo)
                                            }
                                        }
                                    }
                                }
                            } catch (e: SocketTimeoutException) {
                                continue
                            } catch (e: Exception) {
                                if (e !is CancellationException) break
                            }
                        }
                    } catch (e: Exception) {
                        delay(5000)
                    } finally {
                        try {
                            socket?.let { MulticastNet.leaveAllGroups(it) }
                            socket?.close()
                        } catch (e: Exception) {}
                    }
                }
            } finally {
                if (multicastLock.isHeld) multicastLock.release()
            }
        }
    }

    fun startBroadcastingPresence(
        context: Context,
        isMulticast: Boolean,
        streamingPort: Int,
        networkInterfaceName: String = "Auto",
        rtpEnabled: Boolean = false,
        audioFormat: StreamAudioFormat? = null
    ) {
        lastBroadcastParams = BroadcastParams(
            context.applicationContext, isMulticast, streamingPort,
            networkInterfaceName, rtpEnabled, audioFormat
        )
        if (broadcastingJob?.isActive == true) return
        Log.d(TAG, "[BROADCAST] startBroadcastingPresence: isMulticast=$isMulticast port=$streamingPort iface=$networkInterfaceName rtp=$rtpEnabled")
        broadcastingJob = scope.launch {
            val deviceName = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                    Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
                        ?: "${Build.MANUFACTURER} ${Build.MODEL}"
                } else {
                    "${Build.MANUFACTURER} ${Build.MODEL}"
                }
            } catch (e: Exception) { "${Build.MANUFACTURER} ${Build.MODEL}" }

            val mode = if (isMulticast) "MULTICAST" else "UNICAST"
            val protocolsStr = if (rtpEnabled) "protocols=WFAS,RTP" else "protocols=WFAS"
            val micStr = if (serverStreamsMic) ";mic=tx" else ""
            // Il formato va annunciato: senza, un ricevitore conforme non puo'
            // adottarlo e ricadrebbe sulle proprie impostazioni locali.
            val audioStr = audioFormat?.let {
                ";sr=${it.sampleRate};ch=${it.channels};bd=${it.bitDepth}"
            } ?: ""
            val staticPrefix = "${NetworkSettings.DISCOVERY_MESSAGE};$deviceName;$mode;$streamingPort;$protocolsStr$audioStr"

            val groupAddress = InetAddress.getByName(NetworkSettings.MULTICAST_GROUP_IP)
            val wifiIface = getWifiNetworkInterface(networkInterfaceName)

            var socket: MulticastSocket? = null
            try {
                socket = MulticastSocket().apply {
                    timeToLive = 4
                    wifiIface?.let { networkInterface = it }
                }

                while (isActive) {
                    try {
                        val encOn = encryptionEnabled && SecurityMode.requiresKey(securityMode)
                        val announcedAuth = SecurityMode.fromStringSafe(securityMode).name
                        val secStr = ";auth=$announcedAuth;enc=${if (encOn) 1 else 0}"
                        val message = "$staticPrefix$secStr$micStr"
                        val messageBytes = message.toByteArray()
                        val packet = DatagramPacket(
                            messageBytes,
                            messageBytes.size,
                            groupAddress,
                            NetworkSettings.DISCOVERY_PORT
                        )
                        val sent = MulticastNet.sendAll(socket, messageBytes, NetworkSettings.DISCOVERY_PORT, wifiIface) { WfasPolicy.enabledOn(it) }
                        Log.d(TAG, "[BROADCAST] Sent: $message  interfaces=$sent")
                    } catch (e: Exception) {
                        if (e !is CancellationException) println("Broadcasting error: ${e.message}")
                    }
                    delay(3000)
                }
            } catch (e: Exception) {
                if (e !is CancellationException) println("Broadcasting setup error: ${e.message}")
            } finally {
                socket?.close()
            }
        }
    }

    fun stopBroadcastingPresence() {
        broadcastingJob?.cancel()
        broadcastingJob = null
    }

    /**
     * Manda subito un beacon BYE: i client tolgono il server dalla lista senza
     * aspettare la scadenza. Best effort, un pacchetto perso non e' un problema
     * perche' comunque il TTL fa il resto.
     */
    fun announceServerGone(context: Context, networkInterfaceName: String = "Auto") {
        scope.launch {
            runCatching {
                val deviceName = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
                            ?: "${Build.MANUFACTURER} ${Build.MODEL}"
                    } else {
                        "${Build.MANUFACTURER} ${Build.MODEL}"
                    }
                } catch (e: Exception) { "${Build.MANUFACTURER} ${Build.MODEL}" }

                val message = "${NetworkSettings.DISCOVERY_MESSAGE};$deviceName;BYE;0"
                val bytes = message.toByteArray()
                val group = InetAddress.getByName(NetworkSettings.MULTICAST_GROUP_IP)
                MulticastSocket().use { sock ->
                    sock.timeToLive = 4
                    val preferred = getWifiNetworkInterface(networkInterfaceName)
                    preferred?.let { sock.networkInterface = it }
                    val packet = DatagramPacket(bytes, bytes.size, group, NetworkSettings.DISCOVERY_PORT)
                    repeat(2) {
                        MulticastNet.sendAll(sock, bytes, NetworkSettings.DISCOVERY_PORT, preferred)
                        delay(120)
                    }
                }
                Log.d(TAG, "[BROADCAST] BYE annunciato per '$deviceName'")
            }.onFailure { Log.w(TAG, "[BROADCAST] BYE non inviato: ${it.message}") }
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun CoroutineScope.launchMicSenderJob(
        context: Context,
        serverInfo: ServerInfo,
        sampleRate: Int,
        channelConfig: String,
        bufferSize: Int,
        micPort: Int
    ) = launch {
        var micRecord: AudioRecord? = null
        var socket: DatagramSocket? = null
        var aec: AcousticEchoCanceler? = null
        var ns: NoiseSuppressor? = null
        var agc: AutomaticGainControl? = null

        try {
            val channelConfigIn = if (channelConfig == "STEREO") AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfigIn, AudioFormat.ENCODING_PCM_16BIT)
            var micBufferSize = bufferSize.coerceAtLeast(minBufferSize)

            val frameSize = if (channelConfig == "STEREO") 4 else 2
            if (micBufferSize % frameSize != 0) {
                micBufferSize += frameSize - (micBufferSize % frameSize)
            }

            fun buildRecord(source: Int): AudioRecord? = runCatching {
                val rec = AudioRecord(source, sampleRate, channelConfigIn, AudioFormat.ENCODING_PCM_16BIT, micBufferSize)
                if (rec.state == AudioRecord.STATE_INITIALIZED) rec
                else { runCatching { rec.release() }; null }
            }.getOrNull()

            micRecord = buildRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                ?: buildRecord(MediaRecorder.AudioSource.MIC)
                ?: throw IllegalStateException("AudioRecord init failed")

            val sessionId = micRecord.audioSessionId
            if (AcousticEchoCanceler.isAvailable()) {
                aec = runCatching { AcousticEchoCanceler.create(sessionId)?.apply { enabled = true } }.getOrNull()
            }
            if (NoiseSuppressor.isAvailable()) {
                ns = runCatching { NoiseSuppressor.create(sessionId)?.apply { enabled = true } }.getOrNull()
            }
            if (AutomaticGainControl.isAvailable()) {
                agc = runCatching { AutomaticGainControl.create(sessionId)?.apply { enabled = true } }.getOrNull()
            }
            Log.d(TAG, "[MIC_SEND] session=$sessionId aec=${aec?.enabled} ns=${ns?.enabled} agc=${agc?.enabled}")

            socket = DatagramSocket().apply { sendBufferSize = 1 shl 20 }
            val destinationAddress = InetAddress.getByName(serverInfo.ip)

            micRecord.startRecording()
            println("Invio microfono a ${serverInfo.ip}:$micPort")

            val chunkBytes = SettingsDataStore(context).settingsFlow.first().maxPayloadBytes.coerceIn(256, 1400 - MIC_HEADER_SIZE - WfasCrypto.AEAD_OVERHEAD)
            val buffer = ByteArray(micBufferSize)
            val packetBuffer = ByteArray(MIC_HEADER_SIZE + chunkBytes)
            var seq = 0
            var lastMutedSent = false
            fun sendMicPacket(silence: Boolean, src: ByteArray?, off: Int, len: Int) {
                val md = micSendDir
                if (md != null) {
                    val payload = if (!silence && src != null && len > 0) src.copyOfRange(off, off + len) else ByteArray(0)
                    val enc = WfasCrypto.encryptPacket(md, seq, 0, silence, payload)
                    runCatching { socket!!.send(DatagramPacket(enc, enc.size, destinationAddress, micPort)) }
                } else {
                    writeMicHeader(packetBuffer, seq, silence)
                    val plen = if (silence) MIC_HEADER_SIZE else MIC_HEADER_SIZE + len
                    if (!silence && src != null && len > 0) System.arraycopy(src, off, packetBuffer, MIC_HEADER_SIZE, len)
                    runCatching { socket!!.send(DatagramPacket(packetBuffer, plen, destinationAddress, micPort)) }
                }
                seq = (seq + 1) and 0xFFFF
            }
            while (isActive) {
                if (isMicMuted.value) {
                    if (!lastMutedSent) {
                        sendMicPacket(true, null, 0, 0)
                        lastMutedSent = true
                    }
                    micRecord.read(buffer, 0, buffer.size)
                    continue
                }
                lastMutedSent = false
                val bytesRead = micRecord.read(buffer, 0, buffer.size)
                if (bytesRead > 0) {
                    var offset = 0
                    while (offset < bytesRead) {
                        val remaining = bytesRead - offset
                        var chunk = if (remaining > chunkBytes) chunkBytes else remaining
                        chunk -= chunk % 2
                        if (chunk <= 0) break
                        sendMicPacket(false, buffer, offset, chunk)
                        offset += chunk
                    }
                }
            }
        } catch (e: SecurityException) {
            connectionStatus.value = context.getString(R.string.status_mic_permission_denied)
        } catch (e: Exception) {
            if (e !is CancellationException) {
                connectionStatus.value = context.getString(R.string.status_mic_send_error, e.message)
            }
        } finally {
            println("Invio microfono terminato.")
            runCatching { aec?.release() }
            runCatching { ns?.release() }
            runCatching { agc?.release() }
            runCatching { micRecord?.stop() }
            runCatching { micRecord?.release() }
            socket?.close()
        }
    }

    // =========================================================
    // FIX 2 (server side) — setupAudioRecorders viene chiamato
    // SUBITO in modalità unicast, prima di aspettare il client,
    // così i recorder sono già "caldi" quando arriva l'HELLO.
    // =========================================================
    @RequiresApi(Build.VERSION_CODES.Q)
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startServerAudio(
        context: Context,
        projection: MediaProjection?,
        streamInternal: Boolean,
        streamMic: Boolean,
        sampleRate: Int,
        channelConfig: String,
        bufferSize: Int,
        isMulticast: Boolean,
        streamingPort: Int,
        networkInterfaceName: String = "Auto",
        rtpEnabled: Boolean = false,
        rtpPort: Int = 9094,
        httpEnabled: Boolean = false,
        httpPort: Int = 8080,
        dlnaConfig: DlnaServerConfig? = null,
        snapcastConfig: com.cuscus.wifiaudiostreaming.snapcast.SnapcastServerConfig? = null,
        muteRender: Boolean = true,
        persist: Boolean = false,
        onClientDisconnected: (() -> Unit)? = null
    ) {
        if (streamingJob?.isActive == true) return
        serverStreamsMic = streamMic
        startDonationTimer(context)
        Log.d(TAG, "[SERVER] startServerAudio: isMulticast=$isMulticast port=$streamingPort sr=$sampleRate ch=$channelConfig buf=$bufferSize streamInternal=$streamInternal streamMic=$streamMic")
        val serverFormat = StreamAudioFormat(
            sampleRate = sampleRate,
            channels = if (channelConfig == "STEREO") 2 else 1,
            bitDepth = 16
        )
        startBroadcastingPresence(context, isMulticast, streamingPort, networkInterfaceName, rtpEnabled, serverFormat)

        // Il volume salvato serve solo se lo abbassiamo davvero: con muteRender
        // spento il ripristino nel finally non deve toccare nulla, altrimenti
        // riscriverebbe un volume che l'utente puo' aver cambiato nel frattempo.
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (streamInternal && muteRender) {
            originalMediaVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        } else {
            originalMediaVolume = null
        }

        val generation = openStreamGeneration()
        streamingJob = scope.launch {
            isServerStreaming = true
            Log.d(TAG, "[SERVER] streamingJob started")
            var internalRecord: AudioRecord? = null
            var micRecord: AudioRecord? = null
            var sendSocket: BoundDatagramSocket? = null
            var hasError = false

            try {
                val selectorManager = SelectorManager(Dispatchers.IO)
                val channelConfigIn = if (channelConfig == "STEREO") AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
                val audioFormat = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfigIn)
                    .build()

                val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfigIn, AudioFormat.ENCODING_PCM_16BIT)
                var safeBufferSize = bufferSize.coerceAtLeast(minBufferSize)
                val frameSize = if (channelConfig == "STEREO") 4 else 2
                if (safeBufferSize % frameSize != 0) {
                    safeBufferSize += frameSize - (safeBufferSize % frameSize)
                }

                fun setupAudioRecorders(bufSize: Int) {
                    Log.d(TAG, "[SERVER] setupAudioRecorders: bufSize=$bufSize streamInternal=$streamInternal streamMic=$streamMic projectionNull=${projection == null}")
                    if (streamInternal && projection != null) {
                        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                            .addMatchingUsage(AudioAttributes.USAGE_GAME)
                            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                            .build()
                        activeInternalRecord = AudioRecord.Builder()
                            .setAudioFormat(audioFormat)
                            .setBufferSizeInBytes(bufSize)
                            .setAudioPlaybackCaptureConfig(config)
                            .build()
                        activeInternalRecord?.startRecording()
                        Log.d(TAG, "[SERVER] internalRecord state=${activeInternalRecord?.state} recordingState=${activeInternalRecord?.recordingState}")
                    }
                    if (streamMic) {
                        activeMicRecord = AudioRecord.Builder()
                            .setAudioSource(MediaRecorder.AudioSource.MIC)
                            .setAudioFormat(audioFormat)
                            .setBufferSizeInBytes(bufSize)
                            .build()
                        activeMicRecord?.startRecording()
                        Log.d(TAG, "[SERVER] micRecord state=${activeMicRecord?.state} recordingState=${activeMicRecord?.recordingState}")
                    }
                    if (!streamInternal && !streamMic) {
                        Log.w(TAG, "[SERVER] ATTENZIONE: nessuna sorgente audio abilitata!")
                    }
                }

                val channels = if (channelConfig == "STEREO") 2 else 1
                val wifiIface = getWifiNetworkInterface(networkInterfaceName)

                // ── Queue: producer → UDP sender ───────────────────────────────────────
                val udpAudioQueue = java.util.concurrent.ArrayBlockingQueue<Pair<ByteArray, Int>>(UDP_QUEUE_SLOTS)

                fun launchAudioProducer(): Job = launch {
                    val internalBuf = if (streamInternal) ByteArray(safeBufferSize) else null
                    val micBuf      = if (streamMic)      ByteArray(safeBufferSize) else null
                    val mixedBuf    = ByteArray(safeBufferSize)
                    val fSize       = frameSize
                    val bufMs       = (safeBufferSize.toLong() * 1000L) / (sampleRate.toLong() * channels * 2)
                    var producerLoopCount = 0L
                    var producerTotalBytes = 0L
                    var producerDropped = 0L
                    var zeroReads = 0L
                    var silentBuffers = 0L
                    var limitedSamples = 0L
                    var wouldClipSamples = 0L
                    var lastCaptureReport = android.os.SystemClock.elapsedRealtime()
                    var lastCaptureRead = lastCaptureReport
                    var maxCaptureReadGap = 0L
                    var lastPositiveRead = lastCaptureReport
                    var mixClippedSamples = 0L

                    Log.d(TAG, "[PRODUCER] avviato: streamInternal=$streamInternal projNull=${projection == null} internalRecordState=${activeInternalRecord?.state} micRecordState=${activeMicRecord?.state} safeBufferSize=$safeBufferSize bufMs=$bufMs")

                    while (isActive) {
                        val iBytes = activeInternalRecord?.read(internalBuf!!, 0, internalBuf.size, AudioRecord.READ_NON_BLOCKING) ?: 0
                        val mBytes = activeMicRecord?.read(micBuf!!, 0, micBuf.size, AudioRecord.READ_NON_BLOCKING) ?: 0

                        if (iBytes < 0 || mBytes < 0) {
                            RuntimeDiagnostics.event("CAPTURE_READ_ERROR", "internal=$iBytes microphone=$mBytes", true)
                            throw IllegalStateException("AudioRecord read failed: internal=$iBytes microphone=$mBytes")
                        }

                        val readAt = android.os.SystemClock.elapsedRealtime()
                        maxCaptureReadGap = maxOf(maxCaptureReadGap, readAt - lastCaptureRead)
                        lastCaptureRead = readAt
                        if (iBytes > 0 || mBytes > 0) lastPositiveRead = readAt
                        if (iBytes == 0 && mBytes == 0) zeroReads++
                        if (readAt - lastCaptureReport >= 10_000) {
                            RuntimeDiagnostics.event("CAPTURE_STATS", "bytes=$producerTotalBytes zeroReads=$zeroReads silentBuffers=$silentBuffers " +
                                "limitedSamples=$limitedSamples wouldClipSamples=$wouldClipSamples mixClippedSamples=$mixClippedSamples " +
                                "maxReadGapMs=$maxCaptureReadGap lastPositiveReadAgeMs=${readAt - lastPositiveRead} queueDropped=$producerDropped gain=${serverVolume.value}")
                            maxCaptureReadGap = 0L
                            lastCaptureReport = readAt
                        }

                        val ei = iBytes.coerceAtLeast(0)
                        val em = mBytes.coerceAtLeast(0)
                        if (ei == 0 && em == 0) {
                            delay(bufMs.coerceAtLeast(10L))
                            continue
                        }

                        val bothOk = streamInternal && streamMic && ei > 0 && em > 0
                        val n = when {
                            bothOk                   -> minOf(ei, em)
                            streamInternal && ei > 0 -> ei
                            streamMic      && em > 0 -> em
                            else                     -> 0
                        }
                        if (n == 0) continue
                        val aligned = n - (n % fSize)
                        if (aligned == 0) continue

                        val raw: ByteArray = if (bothOk) {
                            val iShorts = ByteBuffer.wrap(internalBuf!!, 0, aligned).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val mShorts = ByteBuffer.wrap(micBuf!!,      0, aligned).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            for (i in 0 until aligned / 2) {
                                val mixed = iShorts[i].toInt() + mShorts[i].toInt()
                                if (mixed !in Short.MIN_VALUE.toInt()..Short.MAX_VALUE.toInt()) mixClippedSamples++
                                val s = mixed.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                                mixedBuf[2 * i]     = (s.toInt() and 0xff).toByte()
                                mixedBuf[2 * i + 1] = (s.toInt() shr 8 and 0xff).toByte()
                            }
                            mixedBuf
                        } else if (streamInternal && ei > 0) internalBuf!! else micBuf!!

                        val vol = serverVolume.value
                        // Count actual silent PCM independently from a nonblocking
                        // read with no data, before gain=0 can turn sound into zero.
                        if (PcmSilence.isZero(raw, 0, aligned)) silentBuffers++
                        wouldClipSamples += PcmGainLimiter.countWouldClip(raw, 0, aligned, vol)
                        limitedSamples += PcmGainLimiter.applyInPlace(raw, 0, aligned, vol)

                        val chunk = raw.copyOf(aligned)
                        httpPcmQueue?.let { if (it.remainingCapacity() > 0) it.offer(chunk) }
                        rtpPcmQueue?.let {
                            if (it.remainingCapacity() <= 0 || !it.offer(chunk)) {
                                rtpSkippedBytes.addAndGet(chunk.size.toLong())
                            }
                        }
                        dlnaManager?.submitPcm(chunk)
                        snapcastManager?.submitPcm(chunk)
                        // Feed ambient spectrum visualizer (server side)
                        com.cuscus.wifiaudiostreaming.dsp.AmbientSpectrumAnalyzer
                            .feedFrame(chunk, 0, aligned, channels, sampleRate)
                        var dropped = 0
                        while (udpAudioQueue.remainingCapacity() == 0) {
                            if (udpAudioQueue.poll() == null) break
                            dropped++
                        }
                        val offered = udpAudioQueue.offer(Pair(chunk, aligned))
                        producerDropped += dropped

                        producerLoopCount++
                        producerTotalBytes += aligned
                        if (producerLoopCount == 1L || producerLoopCount % 300L == 0L) {
                            Log.d(TAG, "[PRODUCER] loop #$producerLoopCount iBytes=$ei mBytes=$em aligned=$aligned offered=$offered queueSize=${udpAudioQueue.size} totalBytes=$producerTotalBytes dropped=$producerDropped")
                        }
                    }
                    Log.d(TAG, "[PRODUCER] loop terminato dopo $producerLoopCount iterazioni, totalBytes=$producerTotalBytes dropped=$producerDropped")
                }

                // ── UDP SENDER ────────────────────────────────────────────────────────
                // Legge dalla coda prodotta dall'AudioProducer, incapsula nel formato
                // WFAS e invia via socket UDP. Non tocca AudioRecord direttamente.
                suspend fun streamingLoop(
                    targetAddress: SocketAddress,
                    clientAlive: java.util.concurrent.atomic.AtomicBoolean? = null,
                    sendDir: WfasCrypto.Dir? = null,
                    beacon: ByteArray? = null,
                    mcast: McastSession? = null,
                    rebind: (suspend () -> SocketAddress?)? = null
                ) {
                    val fSz = channels * 2
                    val safeMtuSize = 1400
                    val headerSize = 10
                    var maxBytesPerPacket = SettingsDataStore(context).settingsFlow.first().maxPayloadBytes.coerceIn(256, safeMtuSize - headerSize)
                    maxBytesPerPacket -= (maxBytesPerPacket % fSz)
                    if (sendDir != null || mcast != null) {
                        maxBytesPerPacket -= WfasCrypto.AEAD_OVERHEAD
                        maxBytesPerPacket -= (maxBytesPerPacket % fSz)
                        if (maxBytesPerPacket < fSz) maxBytesPerPacket = fSz
                    }
                    var seqNumber = 0
                    var samplePosition = 0L
                    var loopCount = 0L
                    var sentPackets = 0L
                    var lastBeacon = 0L
                    var linkDown = false
                    var target = targetAddress
                    var netRev = networkRevision.value
                    var failedSends = 0

                    Log.d(TAG, "[UDP_SENDER] streamingLoop avviato verso $target maxBytesPerPacket=$maxBytesPerPacket")

                    suspend fun tryRebind(reason: String): Boolean {
                        val fn = rebind ?: return false
                        val nt = runCatching { fn() }.getOrNull() ?: return false
                        target = nt
                        failedSends = 0
                        Log.i(TAG, "[UDP_SENDER] socket ricreato ($reason), nuovo target=$target")
                        return true
                    }

                    while (isActive && clientAlive?.get() != false) {
                        if (rebind != null && networkRevision.value != netRev) {
                            netRev = networkRevision.value
                            if (tryRebind("cambio di rete") && linkDown) {
                                linkDown = false
                                connectionStatus.value = context.getString(R.string.status_link_restored)
                            }
                        }
                        val beaconNow = mcast?.beacon ?: beacon
                        val beaconUrgent = mcast?.beaconUrgent == true
                        if (beaconNow != null &&
                            (beaconUrgent || System.currentTimeMillis() - lastBeacon >= 400L)
                        ) {
                            runCatching { sendSocket?.send(Datagram(buildPacket { writeFully(beaconNow) }, target)) }
                            lastBeacon = System.currentTimeMillis()
                            if (beaconUrgent) mcast?.beaconUrgent = false
                        }
                        val (pcmData, pcmLen) = udpAudioQueue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                        loopCount++
                        try {
                            var offset = 0
                            while (offset < pcmLen) {
                                val chunkSize = minOf(maxBytesPerPacket, pcmLen - offset)
                                val activeDir = mcast?.dir ?: sendDir
                                val packet = if (activeDir != null) {
                                    val enc = WfasCrypto.encryptPacket(
                                        activeDir, seqNumber, samplePosition, false,
                                        pcmData.copyOfRange(offset, offset + chunkSize)
                                    )
                                    buildPacket { writeFully(enc) }
                                } else {
                                    buildPacket {
                                        writeByte(0x57.toByte())
                                        writeByte(0x46.toByte())
                                        writeByte(WFAS_PROTOCOL_VERSION.toByte())
                                        writeByte(0x00.toByte())
                                        writeByte((seqNumber shr 8).toByte())
                                        writeByte(seqNumber.toByte())
                                        writeInt((samplePosition and 0xFFFFFFFFL).toInt())
                                        writeFully(pcmData, offset, chunkSize)
                                    }
                                }
                                sendSocket?.send(Datagram(packet, target))
                                sentPackets++
                                if (sentPackets == 1L || sentPackets % 500L == 0L) {
                                    Log.d(TAG, "[UDP_SENDER] pkt #$sentPackets seq=$seqNumber chunkSize=$chunkSize verso $target")
                                }
                                seqNumber = (seqNumber + 1) and 0xFFFF
                                samplePosition += (chunkSize / 2 / channels).toLong()
                                offset += chunkSize
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            if (isTransientNetworkError(e)) {
                                if (!linkDown) {
                                    linkDown = true
                                    Log.w(TAG, "[UDP_SENDER] rete non raggiungibile ($target): " +
                                            "${e.message}. Tengo aperto lo stream e aspetto che torni.")
                                    connectionStatus.value = context.getString(R.string.status_link_lost_waiting)
                                }
                                failedSends++
                                if (failedSends >= 6) tryRebind("socket non più valido")
                                delay(500)
                                continue
                            }
                            Log.e(TAG, "[UDP_SENDER] eccezione inviando a $target: ${e.message}")
                            clientAlive?.set(false)
                            break
                        }
                        failedSends = 0
                        if (linkDown) {
                            linkDown = false
                            Log.i(TAG, "[UDP_SENDER] rete tornata, stream ripreso verso $target")
                            connectionStatus.value = context.getString(R.string.status_link_restored)
                        }
                    }
                    Log.d(TAG, "[UDP_SENDER] streamingLoop terminato: sentPackets=$sentPackets loopCount=$loopCount clientAlive=${clientAlive?.get()}")
                }

                httpJob?.cancel()
                httpPcmQueue?.clear()
                httpPcmQueue = null
                try { httpServerSocket?.close() } catch (_: Exception) {}
                httpServerSocket = null

                if (httpEnabled) {
                    httpJob = scope.launchHttpSidecar(sampleRate, channels, httpPort)
                }

                val wfasOnNetwork = WfasPolicy.enabledOnNetwork()
                if (!wfasOnNetwork) {
                    val summary = ProtocolStatus.summary(
                        wfas = false,
                        rtp = rtpEnabled,
                        http = httpEnabled,
                        dlna = dlnaConfig?.enabled == true,
                        conjunction = context.getString(R.string.list_and),
                        snapcast = snapcastConfig?.enabled == true
                    )
                    if (summary.isNotEmpty()) {
                        connectionStatus.value = context.getString(R.string.status_serving_protocols, summary)
                    }
                }

                scope.launch {
                    runCatching {
                        restartDlnaSession(
                            context = context,
                            config = dlnaConfig ?: DlnaServerConfig(),
                            sampleRate = sampleRate,
                            channels = channels,
                            networkInterfaceName = networkInterfaceName
                        )
                    }.onFailure { Log.e(TAG, "[DLNA] avvio sessione fallito: ${it.message}", it) }
                }

                scope.launch {
                    runCatching {
                        restartSnapcastSession(
                            context = context,
                            config = snapcastConfig
                                ?: com.cuscus.wifiaudiostreaming.snapcast.SnapcastServerConfig(),
                            sampleRate = sampleRate,
                            channels = channels,
                            networkInterfaceName = networkInterfaceName
                        )
                    }.onFailure { Log.e(TAG, "[Snapcast] avvio sessione fallito: ${it.message}", it) }
                }

                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val wifiNet = getWifiNetworkObject(context)
                val vpnActive = isVpnActive(context)
                val usbActive = UsbLink.isReady()

                fun applyProcessNetwork() {
                    if (usbActive) {
                        runCatching { cm.bindProcessToNetwork(null) }
                        Log.d(TAG, "[SERVER] link USB attivo, nessun bind di processo")
                    } else if (!vpnActive) {
                        wifiNet?.let { runCatching { cm.bindProcessToNetwork(it) } }
                    }
                }

                if (isMulticast) {
                    fun bindMulticastSocket(): InetSocketAddress {
                        val iface = MulticastNet.chooseSendInterface(getWifiNetworkInterface(networkInterfaceName)) {
                            WfasPolicy.enabledOn(it)
                        }
                        val group = MulticastNet.audioGroup(iface)
                        val v6 = group is java.net.Inet6Address
                        val host = group.hostAddress?.substringBefore('%')
                            ?: NetworkSettings.MULTICAST_GROUP_IP
                        val scoped = if (v6 && iface != null) "$host%${iface.name}" else host
                        val local = iface?.inetAddresses?.toList()
                            ?.filter { !it.isLoopbackAddress }
                            ?.firstOrNull { if (v6) it is java.net.Inet6Address else it is java.net.Inet4Address }
                            ?.hostAddress?.substringBefore('%')
                            ?: if (v6) "::" else "0.0.0.0"
                        applyProcessNetwork()
                        runCatching { sendSocket?.close() }
                        Log.d(TAG, "[SERVER][MULTICAST] bindando socket su $local:0 (groupV6=$v6) iface=${iface?.name}")
                        sendSocket = aSocket(selectorManager).udp().bind(InetSocketAddress(local, 0))
                        return InetSocketAddress(scoped, streamingPort)
                    }

                    val targetAddress = bindMulticastSocket()
                    Log.d(TAG, "[SERVER][MULTICAST] modalità multicast, target=$targetAddress vpnActive=$vpnActive usbActive=$usbActive wifiNet=$wifiNet")
                    Log.d(TAG, "[SERVER][MULTICAST] socket bound, setupAudioRecorders...")
                    delay(500)
                    setupAudioRecorders(safeBufferSize)

                    if (rtpEnabled) {
                        rtpJob = scope.launchRtpSidecar(
                            sampleRate = sampleRate,
                            channels = channels,
                            port = rtpPort,
                            isMulticast = true,
                            clientIp = null,
                            wifiIface = wifiIface
                        )
                    }

                    launchAudioProducer()

                    val mcSec = SettingsDataStore(context).settingsFlow.first()
                    configureSecurity(mcSec.securityMode, mcSec.authKey, mcSec.encryptionEnabled)

                    val session = McastSession()
                    val store = SettingsDataStore(context)

                    suspend fun applyGroupKey(key: String) {
                        if (encryptionEnabled && SecurityMode.requiresKey(securityMode) && key.isBlank()) {
                            connectionStatus.value = context.getString(R.string.status_key_required)
                            throw IllegalStateException("encryption requested without a key")
                        }
                        if (!(encryptionEnabled && SecurityMode.requiresKey(securityMode))) {
                            session.dir = null
                            session.beacon = null
                            session.key = key
                            session.epoch = 0L
                            mcastSession.value = McastSnapshot(0L, key, false)
                            sessionEncryptedLive.value = false
                            return
                        }
                        val salt = ByteArray(WfasCrypto.SALT_BYTES)
                            .also { java.security.SecureRandom().nextBytes(it) }
                        val epoch = store.nextMcastEpoch()
                        val beaconBytes = WfasCrypto
                            .buildMcastBeacon(key, epoch, System.currentTimeMillis() / 1000, salt)
                            .toByteArray(Charsets.US_ASCII)
                        session.beacon = beaconBytes
                        session.beaconUrgent = true
                        session.dir = WfasCrypto.deriveMulticast(key, salt)
                        session.epoch = epoch
                        session.key = key
                        mcastSession.value = McastSnapshot(epoch, key, true)
                        sessionEncryptedLive.value = true
                    }

                    applyGroupKey(mcSec.authKey)

                    mcastRekey = { newKey -> applyGroupKey(newKey) }

                    try {
                        streamingLoop(
                            targetAddress,
                            mcast = session,
                            rebind = { runCatching { bindMulticastSocket() }.getOrNull() }
                        )
                    } finally {
                        mcastRekey = null
                        mcastSession.value = null
                        withContext(NonCancellable) {
                            try {
                                repeat(3) {
                                    val byePacket = buildPacket { writeText("BYE") }
                                    sendSocket?.send(Datagram(byePacket, targetAddress))
                                }
                                println("--- Sent BYE to multicast group ---")
                            } catch (_: Exception) {}
                        }
                    }
                } else {
                    Log.d(TAG, "[SERVER][UNICAST] modalità unicast, in ascolto su porta $streamingPort")
                    if (wfasOnNetwork) {
                        connectionStatus.value = context.getString(R.string.status_waiting_for_client, streamingPort)
                    }
                    delay(500)
                    setupAudioRecorders(safeBufferSize)

                    val localAddress = InetSocketAddress(NetAddr.wildcardHost(), streamingPort)
                    applyProcessNetwork()
                    sendSocket = aSocket(SelectorManager(Dispatchers.IO)).udp().bind(localAddress) { reuseAddress = true }
                    Log.d(TAG, "[SERVER][UNICAST] socket UDP bound su $localAddress, vpnActive=$vpnActive usbActive=$usbActive wifiNet=$wifiNet")

                    // Il producer parte subito: HTTP/RTP ricevono audio anche prima che
                    // arrivi qualsiasi client UDP.
                    launchAudioProducer()

                    val sec = SettingsDataStore(context).settingsFlow.first()
                    configureSecurity(sec.securityMode, sec.authKey, sec.encryptionEnabled)
                    // A challenge belongs to the peer it was issued to, and expires on
                    // its own. A single shared slot meant the newest HELLO on the socket
                    // overwrote whatever the previous caller was still answering - and the
                    // session keys were then derived from whichever pair of nonces
                    // happened to be left in it.
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
                        startBroadcastingPresence(context, isMulticast = false, streamingPort, networkInterfaceName, rtpEnabled, serverFormat)
                        if (wfasOnNetwork) {
                            connectionStatus.value = context.getString(R.string.status_waiting_for_client, streamingPort)
                        }

                        val clientDatagram = sendSocket.receive()
                        val rxAt = System.currentTimeMillis()
                        val clientAddress = clientDatagram.address
                        val message = clientDatagram.packet.readText().trim()
                        Log.d(TAG, "[SERVER][UNICAST] datagram from $clientAddress kind=${message.substringBefore(';')}")

                        if (message == "MODE_PROBE") {
                            Log.d(TAG, "[SERVER][UNICAST] rispondo UNICAST a MODE_PROBE da $clientAddress")
                            sendSocket.send(Datagram(buildPacket { writeText("UNICAST") }, clientAddress))
                            continue
                        }

                        if (!message.startsWith(NetworkSettings.CLIENT_HELLO_MESSAGE)) {
                            Log.w(TAG, "[SERVER][UNICAST] ignored non-HELLO kind=${message.substringBefore(';')}")
                            continue
                        }

                        val peerHost = peerHostOf(clientAddress)
                        // Challenges are filed under the peer. When the address does not
                        // resolve to a literal host we still want a stable key, so the raw
                        // socket address stands in rather than one shared bucket that every
                        // unresolved peer would land in.
                        val challengeKey = peerHost ?: clientAddress.toString()
                        Log.d(TAG, "[SERVER][TIMING] messaggio da $peerHost, parse+policy a +${System.currentTimeMillis() - rxAt}ms")
                        if (!WfasPolicy.enabledForPeer(peerHost)) {
                            Log.w(TAG, "[SERVER][UNICAST] WFAS non attivo per peer=$peerHost " +
                                    "(usbPeer=${UsbLink.isUsbPeer(peerHost)} mode=${WfasPolicy.mode}), handshake rifiutato")
                            sendSocket.send(Datagram(buildPacket { writeText(NetworkSettings.UNAUTHORIZED_MESSAGE) }, clientAddress))
                            continue
                        }

                        val clientVersion = parseProtocolVersion(message)
                        if (clientVersion != WFAS_PROTOCOL_VERSION) {
                            Log.w(TAG, "[SERVER][UNICAST] client incompatibile v=$clientVersion (mio v=$WFAS_PROTOCOL_VERSION), rifiuto $clientAddress")
                            sendSocket.send(Datagram(buildPacket { writeText(incompatibleMessage()) }, clientAddress))
                            signalProtocolMismatch(clientVersion, PeerRole.RECEIVER)
                            continue
                        }

                        when (SecurityMode.fromStringSafe(securityMode)) {
                            SecurityMode.KEY -> {
                                val cproof = WfasAuth.getToken(message, "cproof")
                                val cnonce = WfasAuth.getToken(message, "cnonce") ?: ""
                                if (cproof == null) {
                                    val snonce = WfasAuth.nonceHex()
                                    challenges[challengeKey] = PendingChallenge(cnonce, snonce, System.currentTimeMillis())
                                    val sproof = WfasAuth.proof(authKey, 'S', cnonce, snonce)
                                    sendSocket.send(Datagram(buildPacket { writeText("${NetworkSettings.AUTH_REQUIRED_PREFIX};snonce=$snonce;sproof=$sproof") }, clientAddress))
                                    continue
                                }
                                // A proof is only an answer to a challenge we issued to this
                                // peer and have not seen answered yet. Without one there is
                                // nothing to check it against, and falling back to nonces the
                                // caller supplied would check it against its own input.
                                val pending = challengeFor(challengeKey)
                                if (pending == null) {
                                    Log.w(TAG, "[SERVER][UNICAST] proof with no pending challenge from $clientAddress")
                                    sendSocket.send(Datagram(buildPacket { writeText(NetworkSettings.UNAUTHORIZED_MESSAGE) }, clientAddress))
                                    continue
                                }
                                val expected = WfasAuth.proof(authKey, 'C', pending.cnonce, pending.snonce)
                                if (!WfasAuth.constantTimeEquals(cproof, expected)) {
                                    Log.w(TAG, "[SERVER][UNICAST] auth failed for $clientAddress")
                                    sendSocket.send(Datagram(buildPacket { writeText(NetworkSettings.UNAUTHORIZED_MESSAGE) }, clientAddress))
                                    continue
                                }
                                // Single use: a replayed proof finds nothing waiting.
                                challenges.remove(challengeKey)
                                pendCnonce = pending.cnonce
                                pendSnonce = pending.snonce
                                Log.d(TAG, "[SERVER][UNICAST] auth OK for $clientAddress")
                            }
                            SecurityMode.ASK -> {
                                sendSocket.send(Datagram(buildPacket { writeText(NetworkSettings.PENDING_MESSAGE) }, clientAddress))
                                val keepAlive = launch {
                                    while (isActive) {
                                        delay(2000)
                                        runCatching { sendSocket.send(Datagram(buildPacket { writeText(NetworkSettings.PENDING_MESSAGE) }, clientAddress)) }
                                    }
                                }
                                val allow = try {
                                    requestAuthFromUi(clientAddress.toString())
                                } finally {
                                    keepAlive.cancel()
                                }
                                if (!allow) {
                                    sendSocket.send(Datagram(buildPacket { writeText(NetworkSettings.UNAUTHORIZED_MESSAGE) }, clientAddress))
                                    continue
                                }
                            }
                            SecurityMode.OFF -> { }
                        }

                        val encrypting = encryptionEnabled &&
                            SecurityMode.requiresKey(securityMode)
                        val sendDir: WfasCrypto.Dir? =
                            if (encrypting) WfasCrypto.deriveUnicast(authKey, pendCnonce, pendSnonce).second else null

                        activePeerIp = peerHostOf(clientAddress)
                        unicastPeerConnected.value = true
                        sessionEncryptedLive.value = sendDir != null
                        Log.d(TAG, "[SERVER][UNICAST] HELLO ricevuto da $clientAddress, invio HELLO_ACK")
                        connectionStatus.value = context.getString(R.string.status_client_connected, clientAddress)

                        // Svuota la coda: il client riceve solo audio fresco
                        udpAudioQueue.clear()

                        val volumeControl = RemoteVolumeProtocol.offer(message,
                            if (SecurityMode.requiresKey(securityMode)) authKey else "", pendCnonce, pendSnonce,
                            object : RemoteVolumeProtocol.Volume {
                                override fun getPercent() = VolumeScale.percent(serverVolume.value, 2f)
                                override fun setPercent(value: Int) { setServerVolume(VolumeScale.gain(value, 2f)) }
                            })
                        val ackText = helloAckMessage() + (if (encrypting) ";enc=1" else "") +
                            (volumeControl?.ackSuffix() ?: "")
                        val ackPacket = buildPacket { writeText(ackText) }
                        sendSocket.send(Datagram(ackPacket, clientAddress))
                        Log.d(TAG, "[SERVER][TIMING] HELLO_ACK inviato a $clientAddress a +${System.currentTimeMillis() - rxAt}ms dalla ricezione")

                        // In unicast il server serve un client solo: finche' e' occupato
                        // non deve annunciarsi, altrimenti un terzo dispositivo lo vede
                        // libero e prova a connettersi. Il beacon riparte da solo al
                        // prossimo giro del while, cioe' quando il client si stacca.
                        Log.d(TAG, "[SERVER][UNICAST] occupato: sospendo l'annuncio in discovery")
                        stopBroadcastingPresence()
                        announceServerGone(context, networkInterfaceName)

                        val clientAlive = java.util.concurrent.atomic.AtomicBoolean(true)
                        val clientPongCapable = java.util.concurrent.atomic.AtomicBoolean(false)
                        val lastClientHeartbeatAt = java.util.concurrent.atomic.AtomicLong(
                            System.currentTimeMillis()
                        )

                        if (rtpEnabled) {
                            rtpJob?.cancel()

                            val clientInetAddress = peerHostOf(clientAddress)

                            rtpJob = scope.launchRtpSidecar(
                                sampleRate = sampleRate,
                                channels = channels,
                                port = rtpPort,
                                isMulticast = false,
                                clientIp = clientInetAddress,
                                wifiIface = wifiIface
                            )
                        }

                        val pingJob = launch {
                            var failures = 0
                            var pingCount = 0L
                            while (isActive && clientAlive.get()) {
                                delay(1000)
                                try {
                                    sendSocket.send(Datagram(buildPacket { writeText("PING") }, clientAddress))
                                    volumeControl?.snapshot()?.let { update ->
                                        sendSocket.send(Datagram(buildPacket { writeText(update) }, clientAddress))
                                    }
                                    pingCount++
                                    if (pingCount == 1L || pingCount % 10L == 0L) {
                                        Log.d(
                                            TAG,
                                            "[SERVER][UNICAST] PING #$pingCount inviato a " +
                                                "$clientAddress failures=$failures pong=" +
                                                clientPongCapable.get()
                                        )
                                    }
                                    failures = 0

                                    // New receivers answer PING with PONG. Only enable
                                    // heartbeat eviction after capability was observed,
                                    // so older clients remain compatible.
                                    if (
                                        clientPongCapable.get() &&
                                        System.currentTimeMillis() -
                                            lastClientHeartbeatAt.get() > 8_000L
                                    ) {
                                        Log.w(
                                            TAG,
                                            "[SERVER][UNICAST] client heartbeat stale; " +
                                                "releasing $clientAddress for reconnect"
                                        )
                                        clientAlive.set(false)
                                        break
                                    }
                                } catch (e: Exception) {
                                    failures++
                                    Log.w(
                                        TAG,
                                        "[SERVER][UNICAST] PING fallito ($failures/8): ${e.message}"
                                    )
                                    if (failures >= 8) {
                                        clientAlive.set(false)
                                    }
                                }
                            }
                        }

                        val clientByeJob = launch {
                            try {
                                while (isActive && clientAlive.get()) {
                                    val datagram = sendSocket.receive()
                                    val msg = datagram.packet.readText().trim()
                                    Log.d(TAG, "[SERVER][UNICAST] control kind=${msg.substringBefore(';')} from ${datagram.address}")
                                    // Solo il client collegato puo' pilotare questa sessione:
                                    // senza questo controllo un CLIENT_BYE di un terzo
                                    // dispositivo chiuderebbe la connessione altrui.
                                    if (datagram.address != clientAddress) {
                                        if (msg.startsWith(NetworkSettings.CLIENT_HELLO_MESSAGE) || msg == "MODE_PROBE") {
                                            Log.w(TAG, "[SERVER][UNICAST] ${datagram.address} rifiutato: occupato con $clientAddress")
                                            runCatching {
                                                sendSocket.send(Datagram(
                                                    buildPacket { writeText(NetworkSettings.BUSY_MESSAGE) },
                                                    datagram.address
                                                ))
                                            }
                                        } else {
                                            Log.w(TAG, "[SERVER][UNICAST] datagram da ${datagram.address} ignorato: sessione occupata")
                                        }
                                        continue
                                    }
                                    if (msg.startsWith(RemoteVolumeProtocol.GET) || msg.startsWith(RemoteVolumeProtocol.SET)) {
                                        volumeControl?.receive(msg)?.let { reply ->
                                            sendSocket.send(Datagram(buildPacket { writeText(reply) }, clientAddress))
                                            lastClientHeartbeatAt.set(System.currentTimeMillis())
                                        }
                                        continue
                                    }
                                    // An ACK can be lost while audio is already flowing. Only
                                    // repeat it for this exact endpoint and negotiated session.
                                    if (msg.startsWith(NetworkSettings.CLIENT_HELLO_MESSAGE) &&
                                        (volumeControl == null || volumeControl.acceptsHello(msg))) {
                                        sendSocket.send(Datagram(buildPacket { writeText(ackText) }, clientAddress))
                                        continue
                                    }
                                    when (msg) {
                                        "PONG" -> {
                                            clientPongCapable.set(true)
                                            lastClientHeartbeatAt.set(System.currentTimeMillis())
                                        }
                                        "CLIENT_BYE" -> {
                                            lastClientHeartbeatAt.set(System.currentTimeMillis())
                                            Log.d(
                                                TAG,
                                                "[SERVER][UNICAST] CLIENT_BYE ricevuto, disconnessione pulita"
                                            )
                                            println("--- Received CLIENT_BYE from $clientAddress ---")
                                            clientAlive.set(false)
                                            break
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "[SERVER][UNICAST] clientByeJob eccezione: ${e.message}")
                            }
                        }

                        var clientDisconnectedUnexpectedly = false
                        try {
                            streamingLoop(clientAddress, clientAlive, sendDir)
                        } finally {
                            unicastPeerConnected.value = false
                            sessionEncryptedLive.value = false
                            clientByeJob.cancel()
                            pingJob.cancel()
                            if (clientAlive.get()) {
                                withContext(NonCancellable) {
                                    try {
                                        repeat(3) {
                                            sendSocket.send(Datagram(buildPacket { writeText("BYE") }, clientAddress))
                                        }
                                        println("--- Sent BYE to $clientAddress ---")
                                    } catch (_: Exception) {}
                                }
                            } else if (isActive) {
                                clientDisconnectedUnexpectedly = true
                            }
                        }
                        if (clientDisconnectedUnexpectedly) {
                            if (persist) {
                                // Persist: e' finita la sessione, non il server. Azzero lo
                                // stato del peer appena uscito e torno in cima al while, che
                                // ricomincia ad annunciarsi e aspetta il prossimo client
                                // sullo stesso socket.
                                pendCnonce = ""
                                pendSnonce = ""
                                challenges.clear()
                                activePeerIp = null
                                Log.d(TAG, "[SERVER][UNICAST] persist: sessione finita, aspetto il prossimo client")
                            } else {
                                isStreamingCurrent.value = false
                                scope.launch(Dispatchers.Main) { onClientDisconnected?.invoke() }
                                break
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    hasError = true
                    Log.e(TAG, "[SERVER] ECCEZIONE FATALE: ${e.message}", e)
                    connectionStatus.value = context.getString(R.string.status_server_error, e.message)
                }
            } finally {
                Log.d(TAG, "[SERVER] finally: cleanup, hasError=$hasError isServerStreaming=$isServerStreaming")
                // Reset ambient visualizer so the background fades cleanly
                com.cuscus.wifiaudiostreaming.dsp.AmbientSpectrumAnalyzer.reset()
                // Se nel frattempo e' partito un altro stream questo job e' superato:
                // azzerare lo stato globale spegnerebbe quello nuovo.
                if (streamGeneration == generation) {
                    isServerStreaming = false
                    isStreamingCurrent.value = false
                }
                runCatching {
                    (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).bindProcessToNetwork(null)
                }

                try { activeInternalRecord?.stop() } catch (_: Exception) {}
                try { activeInternalRecord?.release() } catch (_: Exception) {}
                activeInternalRecord = null

                try { activeMicRecord?.stop() } catch (_: Exception) {}
                try { activeMicRecord?.release() } catch (_: Exception) {}
                activeMicRecord = null

                try { sendSocket?.close() } catch (_: Exception) {}

                originalMediaVolume?.let {
                    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0)
                    originalMediaVolume = null
                }

                stopBroadcastingPresence()
                announceServerGone(context, networkInterfaceName)
                if (isActive && !hasError) connectionStatus.value = context.getString(R.string.status_server_stopped)
            }
        }
    }

    fun stopStreaming(context: Context) {
        ClientSessionController.stopTransports()
        openStreamGeneration() // Invalidate pending old receiver finalizers immediately.
        activePeerIp = null
        unicastPeerConnected.value = false
        sessionEncryptedLive.value = false
        // Va letto prima dell'azzeramento: serve a sapere se eravamo noi il server.
        val wasServing = isServerStreaming
        isServerStreaming = false
        cancelDonationTimer()

        try { activeInternalRecord?.stop() } catch (_: Exception) {}
        try { activeMicRecord?.stop() } catch (_: Exception) {}

        streamingJob?.cancel()
        micStreamingJob?.cancel()
        rtpJob?.cancel()
        httpJob?.cancel()
        scope.launch { stopDlnaSession() }
        scope.launch { stopSnapcastSession() }

        try { activeInternalRecord?.release() } catch (_: Exception) {}
        activeInternalRecord = null

        try { activeMicRecord?.release() } catch (_: Exception) {}
        activeMicRecord = null

        streamingJob = null
        micStreamingJob = null
        rtpJob = null
        httpJob = null
        try { httpServerSocket?.close() } catch (failure: Exception) {
            Log.w(TAG, "HTTP socket close failed", failure)
        }
        httpServerSocket = null

        originalMediaVolume?.let {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0)
            originalMediaVolume = null
        }

        stopBroadcastingPresence()
        if (wasServing) announceServerGone(context)

        connectionStatus.value = context.getString(R.string.status_idle)
        isStreamingCurrent.value = false
    }

    private var receiverMicOwner: String? = null
    internal fun startReceiverMicrophone(context: Context, sourceId: String, peer: ServerInfo,
                                        settings: com.cuscus.wifiaudiostreaming.data.AppSettings,
                                        direction: WfasCrypto.Dir?) {
        if (receiverMicOwner != null) return
        receiverMicOwner = sourceId
        micSendDir = direction
        micStreamingJob = scope.launchMicSenderJob(context, peer, settings.sampleRate,
            settings.channelConfig, settings.bufferSize, settings.micPort)
    }

    internal fun stopReceiverMicrophone(sourceId: String) {
        if (receiverMicOwner != sourceId) return
        micStreamingJob?.cancel(); micStreamingJob = null
        micSendDir = null; receiverMicOwner = null
    }

    fun playConnectionSound(context: Context) {
        // Resource/player initialization must not stall the source's UDP loop.
        val app = context.applicationContext
        scope.launch { ConnectionSoundPlayback.play(app) }
    }

    fun playDisconnectionSound(context: Context) {
        try {
            val player = android.media.MediaPlayer.create(context, R.raw.disconnection_sound)
            player?.setOnCompletionListener { it.release() }
            player?.start()
        } catch (_: Exception) {}
    }

    fun stopListeningForDevices() {
        listeningJob?.cancel()
        listeningJob = null
        discoveryExpiryJob?.cancel()
        discoveryExpiryJob = null
    }

    suspend fun restartListeningForDevices(context: Context, networkInterfaceName: String = "Auto") {
        val job = listeningJob
        val expiryJob = discoveryExpiryJob
        listeningJob = null
        discoveryExpiryJob = null
        if (job != null) runCatching { job.cancelAndJoin() }
        expiryJob?.cancel()
        discoveredDevices.value = emptyMap()
        startListeningForDevices(context, networkInterfaceName)
    }

    fun isListeningActive(): Boolean = listeningJob?.isActive == true

    fun stopAll() {
        stopBroadcastingPresence()
        stopListeningForDevices()
        scope.cancel()
    }

    private fun addADTStoPacket(packet: ByteArray, packetLen: Int, sampleRate: Int, channels: Int) {
        val profile = 2 // AAC LC
        val freqIdx = when (sampleRate) {
            96000 -> 0; 88200 -> 1; 64000 -> 2; 48000 -> 3; 44100 -> 4; 32000 -> 5;
            24000 -> 6; 22050 -> 7; 16000 -> 8; 12000 -> 9; 11025 -> 10; 8000 -> 11; else -> 3
        }
        packet[0] = 0xFF.toByte()
        packet[1] = 0xF9.toByte()
        packet[2] = (((profile - 1) shl 6) + (freqIdx shl 2) + (channels shr 2)).toByte()
        packet[3] = (((channels and 3) shl 6) + (packetLen shr 11)).toByte()
        packet[4] = ((packetLen and 0x7FF) shr 3).toByte()
        packet[5] = (((packetLen and 7) shl 5) + 0x1F).toByte()
        packet[6] = 0xFC.toByte()
    }

    fun getCurrentSsid(context: Context): String {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        val info = wifiManager.connectionInfo
        return info.ssid?.removePrefix("\"")?.removeSuffix("\"") ?: ""
    }

    suspend fun updateWidgetState(context: Context, isStreaming: Boolean, isServer: Boolean) {
        val manager = androidx.glance.appwidget.GlanceAppWidgetManager(context)

        // Aggiorna ServerWidget
        manager.getGlanceIds(ServerWidget::class.java).forEach { id ->
            updateAppWidgetState(context, id) { prefs ->
                prefs[WidgetKeys.IS_STREAMING] = isStreaming
                prefs[WidgetKeys.IS_SERVER] = isServer
            }
            ServerWidget().update(context, id)
        }

        // Aggiorna ClientWidget
        manager.getGlanceIds(ClientWidget::class.java).forEach { id ->
            updateAppWidgetState(context, id) { prefs ->
                prefs[WidgetKeys.IS_STREAMING] = isStreaming
                prefs[WidgetKeys.IS_SERVER] = isServer
            }
            ClientWidget().update(context, id)
        }
    }

    @SuppressLint("MissingPermission")
    private fun CoroutineScope.launchHttpSidecar(
        sampleRate: Int,
        channels: Int,
        port: Int
    ) = launch(Dispatchers.IO) {
        val queue = java.util.concurrent.ArrayBlockingQueue<ByteArray>(50)
        httpPcmQueue = queue

        var serverSocket: java.net.ServerSocket? = null
        try {
            serverSocket = java.net.ServerSocket(port)
            httpServerSocket = serverSocket
            println("HTTP Server avviato sulla porta $port")

            while (isActive) {
                val client = serverSocket.accept()
                launch(Dispatchers.IO) {
                    try {
                        val input = java.io.BufferedReader(java.io.InputStreamReader(client.inputStream))
                        val output = client.outputStream
                        val requestLine = input.readLine() ?: return@launch

                        if (requestLine.startsWith("GET /stream ")) {
                            // --- IL BROWSER VUOLE L'AUDIO ---
                            output.write("HTTP/1.1 200 OK\r\nContent-Type: audio/aac\r\nConnection: keep-alive\r\nCache-Control: no-cache\r\n\r\n".toByteArray())
                            output.flush()

                            val format = android.media.MediaFormat.createAudioFormat(android.media.MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
                                setInteger(android.media.MediaFormat.KEY_AAC_PROFILE, android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                                setInteger(android.media.MediaFormat.KEY_BIT_RATE, 128000)
                                // FIX 2: Obblighiamo il chip ad accettare input giganti senza andare in overflow
                                setInteger(android.media.MediaFormat.KEY_MAX_INPUT_SIZE, 100000)
                            }

                            val codec = android.media.MediaCodec.createEncoderByType(android.media.MediaFormat.MIMETYPE_AUDIO_AAC)
                            codec.configure(format, null, null, android.media.MediaCodec.CONFIGURE_FLAG_ENCODE)
                            codec.start()

                            val bufferInfo = android.media.MediaCodec.BufferInfo()
                            try {
                                while (isActive && !client.isClosed) {
                                    val pcmData = queue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                                    if (pcmData != null) {
                                        var offset = 0
                                        while (offset < pcmData.size && isActive && !client.isClosed) {
                                            val inputIndex = codec.dequeueInputBuffer(10000)
                                            if (inputIndex >= 0) {
                                                val inputBuffer = codec.getInputBuffer(inputIndex)
                                                inputBuffer?.clear()
                                                val capacity = inputBuffer?.capacity() ?: pcmData.size
                                                val chunk = minOf(pcmData.size - offset, capacity)
                                                inputBuffer?.put(pcmData, offset, chunk)
                                                codec.queueInputBuffer(inputIndex, 0, chunk, System.nanoTime() / 1000, 0)
                                                offset += chunk
                                            }
                                        }
                                    }

                                    var outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
                                    while (outputIndex >= 0) {
                                        val outputBuffer = codec.getOutputBuffer(outputIndex)

                                        // --- FIX 1: IGNORA IL METADATO DI SISTEMA CHE FA CRASHARE IL BROWSER! ---
                                        if ((bufferInfo.flags and android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                            bufferInfo.size = 0
                                        }
                                        // ------------------------------------------------------------------------

                                        if (outputBuffer != null && bufferInfo.size > 0) {
                                            val outData = ByteArray(bufferInfo.size + 7)
                                            addADTStoPacket(outData, outData.size, sampleRate, channels)
                                            outputBuffer.position(bufferInfo.offset)
                                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                            outputBuffer.get(outData, 7, bufferInfo.size)

                                            output.write(outData)
                                            output.flush()
                                        }
                                        codec.releaseOutputBuffer(outputIndex, false)
                                        outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                                    }
                                }
                            } catch (e: Exception) {
                                println("Client HTTP disconnesso regolarmente (Pipe rotta).")
                            } finally {
                                codec.stop()
                                codec.release()

                            }
                        } else {
                            // --- LA PAGINA WEB ---
                            val html = """
                                <!DOCTYPE html>
                                <html lang="en">
                                <head>
                                    <meta charset="UTF-8">
                                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                                    <title>AudioBridge</title>
                                    <style>
                                        :root { --bg: #0f0f0f; --surface: #1e1e1e; --primary: #BB86FC; --text: #e0e0e0; --text-mut: #888; }
                                        body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; background: var(--bg); color: var(--text); display: flex; flex-direction: column; align-items: center; justify-content: center; min-height: 100vh; margin: 0; padding: 20px; box-sizing: border-box; }
                                        .card { background: var(--surface); padding: 32px; border-radius: 28px; box-shadow: 0 8px 32px rgba(0,0,0,0.5); text-align: center; max-width: 400px; width: 100%; border: 1px solid #2a2a2a; }
                                        .icon { font-size: 48px; margin-bottom: 12px; }
                                        h2 { margin: 0 0 8px 0; font-size: 22px; font-weight: 600; color: #fff; }
                                        p.subtitle { color: var(--text-mut); margin: 0 0 24px 0; font-size: 14px; }
                                        audio { width: 100%; border-radius: 50px; outline: none; display: none; }
                                        .play-btn { background: var(--primary); color: #000; border: none; padding: 16px 32px; border-radius: 50px; font-size: 16px; font-weight: bold; cursor: pointer; transition: transform 0.1s, opacity 0.2s; width: 100%; margin-bottom: 16px; }
                                        .play-btn:active { transform: scale(0.96); }
                                        .links { display: flex; flex-direction: column; gap: 10px; margin-top: 24px; }
                                        .links a { text-decoration: none; color: var(--text); background: rgba(255,255,255,0.05); padding: 14px; border-radius: 16px; font-size: 14px; transition: background 0.2s; border: 1px solid rgba(255,255,255,0.05); font-weight: 500; }
                                        .links a:hover { background: rgba(255,255,255,0.1); }
                                    </style>
                                </head>
                                <body>
                                    <div class="card">
                                        <div class="icon">🎧</div>
                                        <h2>AudioBridge</h2>
                                        <p class="subtitle">Codec AAC</p>
                                        
                                        <audio id="player" controls src="/stream"></audio>
                                        <button id="playBtn" class="play-btn" onclick="document.getElementById('player').style.display='block'; document.getElementById('player').play(); this.style.display='none';">▶ PLAY AUDIO</button>

                                        <div class="links">
                                            <a href="https://github.com/mu23XR/AudioBridge/releases" target="_blank">⬇ AudioBridge Releases</a>
                                            <a href="https://github.com/mu23XR/AudioBridge" target="_blank">⌘ AudioBridge Source</a>
                                        </div>
                                    </div>
                                </body>
                                </html>
                            """.trimIndent()
                            val response = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\nContent-Length: ${html.toByteArray().size}\r\nConnection: close\r\n\r\n$html"
                            output.write(response.toByteArray())
                            output.flush()
                            client.close()
                        }
                    } catch (e: Exception) {
                        client.close()
                    }
                }
            }
        } catch (e: Exception) {
            if (e !is CancellationException) println("HTTP Server error: ${e.message}")
        } finally {
            serverSocket?.close()
            if (httpPcmQueue == queue) {
                httpPcmQueue = null
            }
        }
    }
}
