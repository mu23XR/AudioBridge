import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.*
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.sound.sampled.SourceDataLine

data class DesktopReceiverSourceState(
    val id: String,
    val peer: ServerInfo,
    val status: String = "",
    val connected: Boolean = false,
    val needsAttention: Boolean = false,
    val paused: Boolean = true,
    val blocked: Boolean = false,
    val gain: Float = 1f,
    val muted: Boolean = false,
    val adaptive: Boolean = true,
    val manualLatencyMs: Int = 40,
    val targetMs: Int = 20,
    val bufferedMs: Int = 0,
    val playedFrames: Long = 0,
    val metrics: LinkMetrics.Snapshot = LinkMetrics.Snapshot(),
    val remoteVolume: RemoteVolumeState = RemoteVolumeState()
)

/** Platform hooks stay outside transport ownership; each request belongs to one source. */
data class DesktopReceiverRequest(
    val peer: ServerInfo,
    val audio: AudioSettings_V1,
    val openOutput: (AudioSettings_V1, Int) -> SourceDataLine,
    val interfaceName: String = "Auto",
    val key: String = "",
    val fromInvite: Boolean = false,
    val expectedEpoch: Long? = null,
    val connectionSoundEnabled: Boolean = true,
    val remoteVolumeEnabled: Boolean = false,
    val requestKey: suspend (Boolean) -> String? = { null },
    val onConnectionCue: () -> Unit = {},
    val onProtocolMismatch: (Int) -> Unit = {},
    val onStatus: (String, Array<out Any>) -> Unit = { _, _ -> },
    val onFrame: ((ShortArray) -> Unit)? = null,
    val onMicrophoneReady: (WfasCrypto.Dir?) -> Job? = { null },
    val onTargetChanged: (Int?) -> Unit = {},
    val masterGain: () -> Float = { 1f },
    val latestPeer: () -> ServerInfo? = { null }
)

internal class DesktopSourceFailure(val key: String, val detail: String,
                                    val retryable: Boolean = true) : Exception(detail)

/** Independent sockets, jitter workers and backoff; JavaSound mixes the outputs. */
object DesktopReceiverSessions {
    const val MAX_SOURCES = 4
    private class Entry(val id: String, var request: DesktopReceiverRequest) {
        var state = DesktopReceiverSourceState(id, request.peer, adaptive = request.audio.adaptiveLatency,
            manualLatencyMs = request.audio.latencyMs)
        var job: Job? = null
        var transport: DesktopWfasReceiver? = null
        var generation = 0L
        var attempts = 0
        var playbackFailures = 0
        var key = request.key
        var blocked = false
        var paused = false
        val cue = ConnectionCuePolicy()
    }
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val entries = LinkedHashMap<String, Entry>()
    // An intermediate retry can be cancelled while still waiting for an older
    // native close. Keep the original owner until it actually completes; the
    // most recently cancelled coroutine is not necessarily the device owner.
    private val closingJobs = LinkedHashMap<String, MutableSet<Job>>()
    private val ownedOutputs = LinkedHashMap<String, MutableSet<DesktopWfasReceiver>>()
    private val _sources = MutableStateFlow<List<DesktopReceiverSourceState>>(emptyList())
    val sources: StateFlow<List<DesktopReceiverSourceState>> = _sources
    private val delays = longArrayOf(1000, 2000, 5000, 10000)

    fun hasSources(): Boolean = synchronized(lock) { entries.isNotEmpty() }
    fun hasActiveSources(): Boolean = synchronized(lock) { entries.values.any { !it.paused && !it.blocked } }
    internal fun credentialForPersistence(id: String): String? = synchronized(lock) { entries[id]?.key }
    fun restore(request: DesktopReceiverRequest, id: String = UUID.randomUUID().toString(),
                gain: Float = 1f, muted: Boolean = false, adaptive: Boolean = request.audio.adaptiveLatency,
                manualMs: Int = request.audio.latencyMs, paused: Boolean = true): String = synchronized(lock) {
        UUID.fromString(id)
        require(gain.isFinite() && gain in 0f..1f && manualMs in 20..400)
        require(entries.size < MAX_SOURCES || entries.containsKey(id))
        entries.remove(id)?.let { stopLocked(it, DesktopReceiverStopReason.RESTART) }
        val e = Entry(id, request.copy(audio = request.audio.copy(adaptiveLatency = adaptive, latencyMs = manualMs)))
        e.state = e.state.copy(gain = gain, muted = muted, adaptive = adaptive, manualLatencyMs = manualMs,
            paused = paused)
        e.paused = paused; entries[id] = e
        if (!paused) startLocked(e)
        publishLocked(); id
    }
    fun connect(request: DesktopReceiverRequest): String = synchronized(lock) {
        require(request.peer.port in 1..65535 && request.peer.ip.isNotBlank()) { "Invalid source endpoint" }
        val existing = entries.values.firstOrNull {
            it.request.peer.ip == request.peer.ip && it.request.peer.port == request.peer.port &&
                it.request.peer.isMulticast == request.peer.isMulticast
        }
        require(existing != null || entries.size < MAX_SOURCES) { "At most $MAX_SOURCES audio sources are supported" }
        val e = existing ?: Entry(UUID.randomUUID().toString(), request).also { entries[it.id] = it }
        stopLocked(e, DesktopReceiverStopReason.RESTART)
        val audio = if (existing == null) request.audio else request.audio.copy(
            adaptiveLatency = e.state.adaptive, latencyMs = e.state.manualLatencyMs)
        e.request = request.copy(audio = audio)
        e.key = request.key
        e.state = e.state.copy(peer = request.peer, adaptive = audio.adaptiveLatency,
            manualLatencyMs = audio.latencyMs, connected = false, needsAttention = false,
            paused = false, blocked = false)
        e.cue.reset(); e.blocked = false; e.paused = false; e.attempts = 0; e.playbackFailures = 0
        startLocked(e)
        publishLocked()
        e.id
    }
    fun retry(id: String) = synchronized(lock) {
        entries[id]?.let { e ->
            stopLocked(e, DesktopReceiverStopReason.RESTART)
            e.cue.reset(); e.blocked = false; e.paused = false; e.attempts = 0; e.playbackFailures = 0
            e.state = e.state.copy(paused = false, blocked = false, needsAttention = false)
            startLocked(e); publishLocked()
        }
        Unit
    }
    fun remove(id: String) = synchronized(lock) {
        entries.remove(id)?.let { stopLocked(it, DesktopReceiverStopReason.USER_STOP) }; publishLocked()
    }
    fun pauseAll() = synchronized(lock) {
        entries.values.forEach {
            it.paused = true; stopLocked(it, DesktopReceiverStopReason.USER_STOP); it.state = it.state.copy(paused = true)
        }
        publishLocked()
    }
    suspend fun pauseAllAndJoin() {
        val jobs = synchronized(lock) {
            pauseAll()
            closingJobs.values.flatMap { it.toList() }.distinct()
        }
        awaitCleanup(jobs)
    }
    /** Explicit OFF deletes runtime intent. Configuration persistence belongs to the UI. */
    fun stopAll(): List<Job> = synchronized(lock) {
        entries.values.forEach { stopLocked(it, DesktopReceiverStopReason.USER_STOP) }
        val jobs = closingJobs.values.flatMap { it.toList() }.distinct()
        entries.clear(); publishLocked(); jobs
    }
    suspend fun stopAllAndJoin() {
        awaitCleanup(stopAll())
    }
    private suspend fun awaitCleanup(jobs: List<Job>) {
        if (withTimeoutOrNull(4000) {
            jobs.joinAll()
            while (synchronized(lock) { outputsPendingLocked() }) delay(10)
            true
        } != true)
            throw IllegalStateException("Receiver cleanup exceeded 4 seconds; refusing overlapping audio ownership")
    }
    private fun outputsPendingLocked(id: String? = null): Boolean {
        val selected = if (id == null) ownedOutputs.keys.toList() else listOf(id)
        for (source in selected) ownedOutputs[source]?.let { outputs ->
            outputs.removeAll { it.isCleanupComplete() }
            if (outputs.isEmpty()) ownedOutputs.remove(source)
        }
        return if (id == null) ownedOutputs.isNotEmpty() else ownedOutputs.containsKey(id)
    }
    private fun blockingOutputsPendingLocked(id: String): Boolean {
        outputsPendingLocked()
        return ownedOutputs.keys.any { source ->
            val owner = entries[source]
            source == id || owner == null || owner.paused || owner.blocked || owner.job?.isActive != true
        }
    }
    fun setGain(id: String, gain: Float) = synchronized(lock) {
        require(gain.isFinite() && gain in 0f..1f)
        entries[id]?.let { it.state = it.state.copy(gain = gain) }; publishLocked()
    }
    fun setMuted(id: String, muted: Boolean) = synchronized(lock) {
        entries[id]?.let { it.state = it.state.copy(muted = muted) }; publishLocked()
    }
    fun setLatency(id: String, adaptive: Boolean, manualMs: Int) = synchronized(lock) {
        require(manualMs in 20..400)
        entries[id]?.let { e ->
            e.state = e.state.copy(adaptive = adaptive, manualLatencyMs = manualMs)
            e.request = e.request.copy(audio = e.request.audio.copy(adaptiveLatency = adaptive, latencyMs = manualMs))
            stopLocked(e, DesktopReceiverStopReason.RESTART); e.blocked = false
            e.state = e.state.copy(blocked = false, needsAttention = false)
            startLocked(e)
        }
        publishLocked()
    }
    fun setRemoteVolume(id: String, percent: Int): Boolean = synchronized(lock) {
        require(percent in 0..100)
        entries[id]?.transport?.setRemoteVolume(percent) == true
    }
    fun setRemoteVolumeEnabled(enabled: Boolean) = synchronized(lock) {
        entries.values.forEach { e ->
            if (e.request.remoteVolumeEnabled != enabled) {
                e.request = e.request.copy(remoteVolumeEnabled = enabled)
                if (!e.paused && !e.blocked) {
                    // This capability is authenticated by the HELLO exchange;
                    // a live flag cannot negotiate a new nonce/session id.
                    stopLocked(e, DesktopReceiverStopReason.RESTART)
                    startLocked(e)
                } else e.state = e.state.copy(remoteVolume = RemoteVolumeState())
            }
        }
        publishLocked()
    }
    private fun publishLocked() { _sources.value = entries.values.map { it.state } }
    private fun owns(e: Entry, token: Long) = entries[e.id] === e && e.generation == token
    private fun stopLocked(e: Entry, reason: DesktopReceiverStopReason) {
        e.generation++
        e.transport?.stop(reason); e.transport = null
        e.job?.let { job ->
            closingJobs.getOrPut(e.id) { LinkedHashSet() }.add(job)
            job.invokeOnCompletion {
                synchronized(lock) {
                    closingJobs[e.id]?.let { jobs ->
                        jobs.remove(job)
                        if (jobs.isEmpty()) closingJobs.remove(e.id)
                    }
                }
            }
            job.cancel()
        }
        e.job = null
        e.state = e.state.copy(connected = false, remoteVolume = RemoteVolumeState())
    }
    private fun startLocked(e: Entry) {
        if (e.job?.isActive == true || e.blocked || e.paused) return
        val token = ++e.generation
        val previous = closingJobs[e.id]?.toList().orEmpty()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            if (withTimeoutOrNull(3500) {
                previous.joinAll()
                while (synchronized(lock) { blockingOutputsPendingLocked(e.id) }) delay(10)
                true
            } != true) {
                // Starting a second output while the previous one still owns the
                // device would mix two playbacks. Block the entry instead of
                // publishing a status that the next retry would immediately undo.
                synchronized(lock) {
                    if (!owns(e, token)) return@launch
                    e.blocked = true
                    e.state = e.state.copy(status = Strings.get("source_output_cleanup_pending"),
                        needsAttention = true, blocked = true, connected = false)
                    publishLocked()
                }
                return@launch
            }
            while (isActive) {
                val request = synchronized(lock) { if (!owns(e, token)) return@launch else e.request }
                val peer = request.latestPeer()?.takeIf {
                    it.port == request.peer.port && it.isMulticast == request.peer.isMulticast
                } ?: request.peer
                val transport = DesktopWfasReceiver(request.copy(peer = peer, key = e.key),
                    cue = e.cue.beginAttempt(request.connectionSoundEnabled),
                    currentGain = {
                        synchronized(lock) {
                            if (!owns(e, token) || e.state.muted) 0f else {
                                val total = entries.values.sumOf {
                                    if (it.paused || it.blocked || !it.state.connected || it.state.muted) 0.0
                                    else it.state.gain.toDouble()
                                }
                                request.masterGain().coerceIn(0f, 1f) * e.state.gain / maxOf(1f, total.toFloat())
                            }
                        }
                    },
                    remoteControlAllowed = { synchronized(lock) { owns(e, token) && e.request.remoteVolumeEnabled } },
                    onKey = { key -> synchronized(lock) { if (owns(e, token)) e.key = key } },
                    onState = { key, args, connected ->
                        if (update(e, token) { it.copy(status = Strings.get(key, *args), connected = connected) })
                            request.onStatus(key, args)
                    },
                    onMetrics = { metrics, target, buffered, frames, remote ->
                        update(e, token) { it.copy(metrics = metrics, targetMs = target,
                            bufferedMs = buffered, playedFrames = frames, remoteVolume = remote) }
                    })
                synchronized(lock) {
                    if (!owns(e, token)) return@launch
                    e.transport = transport
                    ownedOutputs.getOrPut(e.id) { LinkedHashSet() }.add(transport)
                }
                val failure = try { transport.run(); null }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (problem: DesktopSourceFailure) { problem }
                catch (problem: PlaybackOutputFailure) {
                    DesktopSourceFailure("playback_failed", problem.message ?: "Audio output failed")
                }
                catch (problem: Exception) { DesktopSourceFailure("status_error_client", "${problem.javaClass.simpleName}: ${problem.message}") }
                synchronized(lock) {
                    if (!owns(e, token)) return@launch
                    e.transport = null
                    val unreleased = outputsPendingLocked(e.id)
                    if (failure == null && !unreleased) return@launch
                    if (failure?.key == "playback_failed") e.playbackFailures++
                    e.blocked = unreleased || failure?.retryable == false || e.playbackFailures >= 3
                    e.state = e.state.copy(connected = false, needsAttention = e.blocked,
                        blocked = e.blocked,
                        status = if (unreleased) Strings.get("source_output_cleanup_pending") else failure!!.detail,
                        remoteVolume = RemoteVolumeState())
                    publishLocked()
                    if (e.blocked) return@launch
                }
                AppDebug.log("[SOURCE] retry id=${e.id} cause=${failure?.key} attempt=${e.attempts}")
                val wait = delays[(e.attempts++).coerceAtMost(delays.lastIndex)]
                request.onStatus("playback_reconnecting", arrayOf((wait / 1000).toInt()))
                delay(wait)
            }
        }
        e.job = job; job.start()
    }
    private fun update(e: Entry, token: Long, change: (DesktopReceiverSourceState) -> DesktopReceiverSourceState): Boolean = synchronized(lock) {
        if (!owns(e, token)) false else { e.state = change(e.state); publishLocked(); true }
    }
}

/** A source socket is created/negotiated before opening a possibly stalled sound device. */
internal class DesktopWfasReceiver(
    private val request: DesktopReceiverRequest,
    private val cue: ConnectionCuePolicy.Attempt,
    private val currentGain: () -> Float,
    private val remoteControlAllowed: () -> Boolean,
    private val onKey: (String) -> Unit,
    private val onState: (String, Array<out Any>, Boolean) -> Unit,
    private val onMetrics: (LinkMetrics.Snapshot, Int, Int, Long, RemoteVolumeState) -> Unit,
    private val liveness: DesktopReceiverLiveness = DesktopReceiverLiveness(),
    private val routeProbe: (InetAddress, String) -> String? = { address, preferred ->
        DesktopDestinationPath.select(address, preferred)?.fingerprint
    },
    private val routeCheckIntervalMs: Long = 2500,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val storeGroupEpoch: (String, Long) -> Unit = SettingsRepository::setMcastClientEpoch
) {
    init { require(routeCheckIntervalMs > 0) }
    @Volatile private var stopped = false
    @Volatile private var notifyPeerOnExit = false
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var remoteVolume: RemoteVolumeProtocol.Client? = null
    private var key = request.key
    private var encrypted = request.peer.capabilities?.encrypted == true
    private var receiveDirection: WfasCrypto.Dir? = null
    private var replay = WfasCrypto.ReplayWindow()
    private var badBeacons = 0
    private var epoch = request.expectedEpoch?.minus(1) ?: SettingsRepository.getMcastClientEpoch(request.peer.ip)
    private var salt: ByteArray? = null
    private var microphone: Job? = null
    @Volatile private var player: DesktopJitterAudioPlayer? = null
    @Volatile private var connected = false
    @Volatile private var pendingOutput: SourceDataLine? = null
    private val pendingInitializations = AtomicInteger()
    private val initializationCleanupFailed = AtomicBoolean()
    private fun now() = clockMs()
    fun stop(reason: DesktopReceiverStopReason = DesktopReceiverStopReason.RESTART) {
        if (reason == DesktopReceiverStopReason.USER_STOP) notifyPeerOnExit = true
        stopped = true
    }
    fun isCleanupComplete(): Boolean = !initializationCleanupFailed.get() && pendingInitializations.get() == 0 &&
        pendingOutput == null && (player?.isCleanupComplete() != false)
    fun setRemoteVolume(percent: Int): Boolean {
        if (!remoteControlAllowed()) return false
        val control = remoteVolume ?: return false
        control.setPercent(percent); return true
    }
    private fun status(key: String, connected: Boolean = false, vararg args: Any) = onState(key, args, connected)
    private fun fail(key: String, retryable: Boolean = false, vararg args: Any): Nothing =
        throw DesktopSourceFailure(key, Strings.get(key, *args), retryable)

    suspend fun run() = coroutineScope {
        val peer = request.peer
        val format = peer.serverAudioSettings ?: request.audio
        if (format.bitDepth != 16 || format.channels !in 1..2 || format.sampleRate.toInt() !in 4000..192000)
            fail("status_unsupported_format", false, "${format.sampleRate} Hz / ${format.channels} ch / ${format.bitDepth} bit")
        val remote = InetSocketAddress(InetAddress.getByName(peer.ip), peer.port)
        val endpoint = DesktopPeerEndpoint(remote, peer.isMulticast)
        val path = DesktopDestinationPath.select(remote.address, request.interfaceName)
            ?: throw DesktopSourceFailure("status_error_client", "No usable local route for the selected source/interface")
        val sock = if (peer.isMulticast) MulticastSocket(null) else DatagramSocket(null)
        socket = sock
        var handshakeComplete = false
        var routeWatch: Job? = null
        try {
            if (sock is MulticastSocket) {
                sock.reuseAddress = true
                sock.bind(InetSocketAddress(peer.port))
                val iface = path.networkInterface
                    ?: throw DesktopSourceFailure("status_error_client", "No multicast interface")
                sock.networkInterface = iface
                sock.joinGroup(InetSocketAddress(NetworkHandler_v1.MULTICAST_GROUP_IP, peer.port), iface)
            } else sock.bind(InetSocketAddress(path.address, 0))
            sock.soTimeout = 250; sock.receiveBufferSize = 1 shl 18
            if (!peer.isMulticast) { handshake(sock, remote, endpoint); handshakeComplete = true }
            else encrypted = encrypted || key.isNotEmpty() || request.fromInvite || request.expectedEpoch != null
            endpoint.bind()
            ensureRunning()
            val usb = peer.viaUsb || UsbLink.isUsbPeer(peer.ip)
            val automatic = request.audio.adaptiveLatency && !usb
            val target = if (usb) minOf(request.audio.latencyMs, UsbLink.latencyMs)
                else if (automatic) 20 else request.audio.latencyMs
            val health = PlaybackHealth(automatic, target, if (usb) UsbLink.MIN_USB_LATENCY_MS else 20)
            val open = {
                pendingInitializations.incrementAndGet()
                DesktopAudioInitialization.open(onFinished = { pendingInitializations.decrementAndGet() },
                    onCleanupFailure = { initializationCleanupFailed.set(true) }) {
                    request.openOutput(format, DesktopPlayoutLimits.nativeCapacityMs(health))
                }
            }
            val line = open().also { pendingOutput = it }
            ensureRunning()
            player = DesktopJitterAudioPlayer(line, format.sampleRate.toInt(), format.channels,
                prebufferMs = target, maxBufferMs = DesktopPlayoutLimits.QUEUE_CAPACITY_MS, health = health, lineFactory = open,
                onRecover = { status("playback_recovering", connected) },
                onRecovered = {
                    if (connected) status(if (peer.isMulticast) "status_multicast_streaming"
                        else "status_connected_streaming_from", true, peer.ip)
                },
                currentVolume = currentGain, onTargetChanged = request.onTargetChanged)
            pendingOutput = null // Player now owns the line, including a failure from start().
            player!!.start()
            val metrics = DesktopPacketMetrics(format.sampleRate.toInt())
            val continuity = DesktopPcmContinuity(format.sampleRate.toInt(), format.channels)
            val timing = DesktopReceiveTiming()
            val buffer = ByteArray(65536)
            val packet = DatagramPacket(buffer, buffer.size)
            liveness.onValidActivity()
            var reportAt = now()
            var metricsLogAt = reportAt
            val pathChanged = AtomicBoolean()
            // Windows adapter enumeration can take hundreds of milliseconds.
            // Keep a single owned probe off the PCM/PING receive path; scope
            // completion also waits for an in-flight query during shutdown.
            routeWatch = launch(Dispatchers.IO) {
                delay(routeCheckIntervalMs)
                while (isActive && !stopped) {
                    val startedAt = now()
                    if (routeProbe(remote.address, request.interfaceName) != path.fingerprint) {
                        pathChanged.set(true)
                        break
                    }
                    delay((routeCheckIntervalMs - (now() - startedAt)).coerceAtLeast(1))
                }
            }
            while (currentCoroutineContext().isActive && !stopped) {
                player!!.throwIfFailed()
                if (liveness.hasExpired()) fail("status_server_disconnected", true)
                if (pathChanged.get())
                    throw DesktopSourceFailure("status_server_disconnected", "Source network path changed")
                remoteVolume?.nextMessage(now())?.let {
                    try { send(sock, remote, it) } catch (problem: java.io.IOException) {
                        AppDebug.log("[SOURCE] optional volume send failed: ${problem.javaClass.simpleName}")
                    }
                }
                try { packet.length = buffer.size; sock.receive(packet) }
                catch (_: SocketTimeoutException) {
                    if (liveness.hasExpired()) fail("status_server_disconnected", true)
                    if (now() - reportAt >= 1000) {
                        report(metrics, health); reportAt = now()
                    }
                    continue
                }
                ensureRunning()
                if (!endpoint.accepts(packet)) continue
                val length = packet.length
                if (length <= 0) continue
                if (!WfasDatagram.isAudio(buffer, length, peer.isMulticast)) {
                    val message = String(buffer, 0, length, Charsets.US_ASCII).trim()
                    when {
                        message == "PING" && !peer.isMulticast -> { send(sock, remote, "PONG"); liveness.onValidActivity() }
                        message == "BYE" -> fail("status_server_disconnected", true)
                        !peer.isMulticast && message.startsWith(RemoteVolumeProtocol.STATE + ";") ->
                            if (remoteVolume?.receive(message) == true) liveness.onValidActivity()
                        peer.isMulticast && message.startsWith(WfasCrypto.MSG_MCAST_ENC + ";") ->
                            if (groupBeacon(message)) liveness.onValidActivity()
                    }
                    continue
                }
                val version = buffer[2].toInt() and 255
                if (version != NetworkHandler_v1.WFAS_PROTOCOL_VERSION) {
                    request.onProtocolMismatch(version); fail("status_protocol_incompatible")
                }
                val flags: Int; val seq: Int; val position: Long; val pcm: ByteArray
                if ((buffer[3].toInt() and WfasCrypto.FLAG_ENCRYPTED) != 0) {
                    val dir = receiveDirection ?: continue
                    val decoded = WfasCrypto.decryptPacket(dir, replay, buffer, length)
                    if (decoded !is WfasCrypto.Decrypted.Ok) continue
                    flags = decoded.header.flags; seq = decoded.header.seq; position = decoded.header.samplePos; pcm = decoded.pcm
                } else {
                    if (encrypted) continue
                    flags = buffer[3].toInt() and 255
                    seq = ((buffer[4].toInt() and 255) shl 8) or (buffer[5].toInt() and 255)
                    position = ((buffer[6].toLong() and 255) shl 24) or ((buffer[7].toLong() and 255) shl 16) or
                        ((buffer[8].toLong() and 255) shl 8) or (buffer[9].toLong() and 255)
                    pcm = buffer.copyOfRange(10, length)
                }
                if (pcm.size % (format.channels * 2) != 0 || (pcm.isEmpty() && flags and 1 == 0)) continue
                val silence = flags and 1 != 0 || pcm.all { it == 0.toByte() }
                liveness.onValidActivity() // Only compatible, peer-filtered and authenticated frames prove liveness.
                val fresh = metrics.observe(seq, position, silence)
                if (!fresh) continue
                val audio = continuity.accept(seq, position, silence, pcm)
                if (!audio.accepted) continue
                timing.observe(now(), pcm.size / (format.channels * 2), silence)
                if (!connected) {
                    connected = true
                    status(if (peer.isMulticast) "status_multicast_streaming" else "status_connected_streaming_from", true, peer.ip)
                }
                if (cue.onValidFrame()) request.onConnectionCue()
                if (audio.silence) player!!.noteAudioFlow(false) else {
                    audio.concealment?.let { player!!.submitConcealment(it) }
                    val realPcm = checkNotNull(audio.pcm)
                    player!!.submit(realPcm, 0, realPcm.size, audio.discontinuity)
                    request.onFrame?.let { callback ->
                        val samples = ShortArray(realPcm.size / 2)
                        java.nio.ByteBuffer.wrap(realPcm).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
                        callback(samples)
                    }
                }
                if (now() - reportAt >= 1000) { report(metrics, health); reportAt = now() }
                if (now() - metricsLogAt >= 10_000) {
                    DesktopDiagnostics.event("RECEIVE", metrics.snapshot(if (usb) "USB" else "WIFI").format() +
                        " " + timing.snapshotAndReset().format())
                    metricsLogAt = now()
                }
            }
        } finally {
            routeWatch?.cancel()
            // Android may stop a non-persistent sending bridge on CLIENT_BYE.
            // Failed output/network attempts and local reconfiguration only
            // release this receiver; their retry must leave that sender alive.
            if (notifyPeerOnExit && handshakeComplete && !peer.isMulticast && !sock.isClosed) {
                try { send(sock, remote, "CLIENT_BYE") } catch (problem: Exception) {
                    AppDebug.log("[SOURCE] BYE unavailable: ${problem.javaClass.simpleName}")
                }
            }
            sock.close(); socket = null
            microphone?.cancel(); microphone = null
            try { player?.stop() }
            finally {
                if (player?.isCleanupComplete() != false) player = null
                // Cancellation can arrive while the native device is opening,
                // before a player exists to take ownership of the returned line.
                pendingOutput?.let { output -> output.close(); pendingOutput = null }
            }
        }
    }
    private fun report(metrics: DesktopPacketMetrics, health: PlaybackHealth) {
        onMetrics(metrics.snapshot(if (request.peer.viaUsb) "USB" else "WIFI"), health.targetMs,
            player?.bufferedMs() ?: 0, player?.playedFrames() ?: 0,
            remoteVolume?.state() ?: RemoteVolumeState(RemoteVolumeSupport.UNSUPPORTED))
    }
    private suspend fun ensureRunning() {
        currentCoroutineContext().ensureActive()
        if (stopped) throw CancellationException("Source stopped")
    }
    private suspend fun obtainKey(wrong: Boolean) {
        if (wrong && request.fromInvite) { NetworkHandler_v1.pendingInviteRejected.value = true; fail("status_unauthorized") }
        key = withTimeoutOrNull(60_000) { request.requestKey(wrong) }?.takeIf { it.isNotBlank() }
            ?: fail("status_key_required")
    }
    private suspend fun handshake(sock: DatagramSocket, remote: InetSocketAddress, endpoint: DesktopPeerEndpoint) {
        status("status_waiting_ack")
        val nonce = WfasAuth.nonceHex(); val volumeNonce = RemoteVolumeProtocol.nonce()
        val base = "HELLO_FROM_CLIENT;v=${NetworkHandler_v1.WFAS_PROTOCOL_VERSION};cnonce=$nonce" +
            if (request.remoteVolumeEnabled) RemoteVolumeProtocol.helloSuffix(volumeNonce) else ""
        var hello = base; var serverNonce = ""; var proved = false
        val start = now(); var deadline = start + 30_000; var lastReply = start; var anyReply = false; var lastSend = -1000L
        val buffer = ByteArray(4096); val packet = DatagramPacket(buffer, buffer.size)
        while (now() < deadline && now() - start < 120_000) {
            ensureRunning()
            if (!anyReply && now() - start >= 6000) fail("status_server_silent_maybe_outdated", true)
            if (anyReply && now() - lastReply >= 6000) fail("status_server_disconnected", true)
            if (now() - lastSend >= 1000) { send(sock, remote, hello); lastSend = now() }
            try { packet.length = buffer.size; sock.receive(packet) } catch (_: SocketTimeoutException) { continue }
            if (!endpoint.accepts(packet, handshake = true)) continue
            val text = String(buffer, 0, packet.length, Charsets.US_ASCII).trim()
            val kind = text.substringBefore(';')
            if (kind !in setOf("HELLO_ACK", "WFAS_PENDING", "WFAS_AUTH_REQUIRED", "WFAS_UNAUTHORIZED", "WFAS_INCOMPATIBLE", "WFAS_BUSY")) continue
            AppDebug.log("[SOURCE] handshake kind=$kind bytes=${packet.length}")
            anyReply = true; lastReply = now()
            when (kind) {
                "WFAS_INCOMPATIBLE" -> { request.onProtocolMismatch(version(text)); fail("status_protocol_incompatible") }
                "WFAS_BUSY" -> fail("status_server_busy", true)
                "WFAS_PENDING" -> { status("status_awaiting_approval"); deadline = minOf(start + 120_000, now() + 6000) }
                "WFAS_UNAUTHORIZED" -> {
                    obtainKey(true); proved = false; hello = base
                    // Human input is not network inactivity. Start the next reply
                    // budget after it completes; the absolute attempt cap stays fixed.
                    lastReply = now(); deadline = now() + 30_000; lastSend = -1000
                }
                "WFAS_AUTH_REQUIRED" -> {
                    if (key.isEmpty()) obtainKey(false)
                    serverNonce = WfasAuth.getToken(text, "snonce").orEmpty()
                    val proof = WfasAuth.getToken(text, "sproof").orEmpty()
                    if (!WfasAuth.constantTimeEquals(proof, WfasAuth.proof(key, 'S', nonce, serverNonce))) {
                        obtainKey(true); proved = false; hello = base
                    } else {
                        proved = true; hello = "$base;cproof=${WfasAuth.proof(key, 'C', nonce, serverNonce)}"
                    }
                    lastReply = now(); deadline = now() + 30_000; lastSend = -1000
                }
                "HELLO_ACK" -> {
                    if (version(text) != NetworkHandler_v1.WFAS_PROTOCOL_VERSION) {
                        request.onProtocolMismatch(version(text)); fail("status_protocol_incompatible")
                    }
                    if ((request.fromInvite || key.isNotEmpty()) && !proved) fail("status_unauthorized")
                    encrypted = WfasAuth.getToken(text, "enc") == "1"
                    if (encrypted && !proved) fail("status_unauthorized")
                    val directions = if (proved) WfasCrypto.deriveUnicast(key, nonce, serverNonce) else null
                    receiveDirection = if (encrypted) directions?.second else null
                    remoteVolume = if (request.remoteVolumeEnabled)
                        RemoteVolumeProtocol.accept(text, volumeNonce, if (proved) key else "", nonce, serverNonce) else null
                    remoteVolume?.query() // Query is inert for audio and never applies a receiver default.
                    if (proved) onKey(key)
                    microphone = request.onMicrophoneReady(if (encrypted) directions?.first else null)
                    return
                }
            }
        }
        fail("status_handshake_failed", true)
    }
    internal suspend fun groupBeacon(message: String): Boolean {
        if (key.isEmpty()) obtainKey(false)
        val beacon = WfasCrypto.parseMcastBeacon(key, message, -1L)
        if (beacon == null) {
            if (++badBeacons >= 6) {
                if (receiveDirection != null) fail("status_group_rekeyed")
                obtainKey(true); badBeacons = 0
                // A deliberate key correction starts a fresh activity budget;
                // the rejected beacons themselves never keep a source alive.
                liveness.onValidActivity()
            }
            return false
        }
        badBeacons = 0
        if (request.expectedEpoch != null && request.expectedEpoch != beacon.epoch) {
            NetworkHandler_v1.pendingEpochMismatch.value = true; fail("status_group_rekeyed")
        }
        if (beacon.epoch < epoch) return false
        if (receiveDirection != null && beacon.epoch == epoch) {
            if (!beacon.salt.contentEquals(salt)) fail("status_group_rekeyed")
            return true
        }
        epoch = beacon.epoch; salt = beacon.salt
        receiveDirection = WfasCrypto.deriveMulticast(key, beacon.salt); replay = WfasCrypto.ReplayWindow(); encrypted = true
        if (!request.fromInvite) storeGroupEpoch(request.peer.ip, epoch)
        onKey(key); return true
    }
    private fun version(message: String) = WfasAuth.getToken(message, "v")?.toIntOrNull() ?: 0
    private fun send(sock: DatagramSocket, remote: InetSocketAddress, text: String) {
        val bytes = text.toByteArray(Charsets.US_ASCII); sock.send(DatagramPacket(bytes, bytes.size, remote))
    }
}

/** A timed-out native open must close its late result, rather than orphaning a device. */
internal object DesktopAudioInitialization {
    fun <T : javax.sound.sampled.Line> open(timeoutMs: Long = 3000, onFinished: () -> Unit = {},
                                           onCleanupFailure: (Throwable) -> Unit = {}, operation: () -> T): T {
        val ownership = Any()
        var abandoned = false
        var offered: T? = null
        val future = CompletableFuture<T>()
        fun closeUnclaimed(line: T) {
            try { line.close() }
            catch (failure: Throwable) {
                onCleanupFailure(failure)
                AppDebug.log("[SOURCE] Unclaimed audio output cleanup failed: ${failure.javaClass.simpleName}")
                throw failure
            }
        }
        Thread({
            try {
                val line = operation()
                val release = synchronized(ownership) {
                    if (abandoned) true else {
                        offered = line
                        if (future.complete(line)) false else { offered = null; true }
                    }
                }
                if (release) closeUnclaimed(line)
            } catch (problem: Throwable) { future.completeExceptionally(problem) }
            finally { onFinished() }
        }, "wfas-output-open").apply { isDaemon = true; start() }
        try {
            val line = future.get(timeoutMs, TimeUnit.MILLISECONDS)
            synchronized(ownership) { offered = null }
            return line
        }
        catch (problem: Exception) {
            val completed = synchronized(ownership) {
                abandoned = true
                future.completeExceptionally(problem)
                offered.also { offered = null }
            }
            try { completed?.let(::closeUnclaimed) }
            catch (cleanup: Throwable) { problem.addSuppressed(cleanup) }
            if (problem is InterruptedException) Thread.currentThread().interrupt()
            throw PlaybackOutputFailure("Audio output initialization failed or exceeded $timeoutMs ms: ${problem.javaClass.simpleName}", problem)
        }
    }
}

internal object DesktopPeerFilter {
    fun accepts(packet: DatagramPacket, remote: InetSocketAddress, multicast: Boolean): Boolean =
        packet.address == remote.address && (multicast || packet.port == remote.port)
}

internal data class DesktopDestinationPath(val address: InetAddress?, val networkInterface: NetworkInterface?) {
    val fingerprint: String get() = "${networkInterface?.name}:${address?.hostAddress}"
    companion object {
        fun select(peer: InetAddress, preferred: String): DesktopDestinationPath? {
            if (peer.isLoopbackAddress) return DesktopDestinationPath(InetAddress.getLoopbackAddress(), null)
            val explicit = preferred.isNotBlank() && !preferred.equals("Auto", true)
            val candidates = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback && (!explicit || it.name == preferred) }
            val direct = candidates.flatMap { iface -> iface.interfaceAddresses.mapNotNull { link ->
                if (sameSubnet(peer, link.address, link.networkPrefixLength.toInt()))
                    Triple(iface, link.address, link.networkPrefixLength.toInt()) else null
            } }.maxByOrNull { it.third }
            if (direct != null) return DesktopDestinationPath(direct.second, direct.first)
            if (explicit) return candidates.firstOrNull()?.let { iface ->
                iface.inetAddresses.toList().firstOrNull { it.address.size == peer.address.size }?.let { DesktopDestinationPath(it, iface) }
            }
            // The OS route handles gateways. Connect sends no UDP data and reveals its chosen local address.
            return DatagramSocket().use { probe ->
                probe.connect(peer, 9)
                val local = probe.localAddress.takeUnless { it.isAnyLocalAddress } ?: return null
                DesktopDestinationPath(local, NetworkInterface.getByInetAddress(local))
            }
        }
        fun sameSubnet(left: InetAddress, right: InetAddress, prefix: Int): Boolean {
            if (left.address.size != right.address.size || prefix !in 1..left.address.size * 8) return false
            for (i in left.address.indices) {
                val bits = (prefix - i * 8).coerceIn(0, 8); val mask = (255 shl (8 - bits)) and 255
                if ((left.address[i].toInt() and mask) != (right.address[i].toInt() and mask)) return false
            }
            return true
        }
    }
}
