package com.cuscus.wifiaudiostreaming

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.cuscus.wifiaudiostreaming.data.SettingsDataStore
import com.cuscus.wifiaudiostreaming.diagnostics.RuntimeDiagnostics
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.util.UUID

data class ReceiverSourceState(
    val id: String, val peer: ServerInfo, val status: String = "",
    val connected: Boolean = false, val needsAttention: Boolean = false,
    val volume: Float = 1f, val muted: Boolean = false,
    val adaptive: Boolean = true, val manualLatencyMs: Int = 40,
    val targetMs: Int = 20, val bufferedMs: Int = 0,
    val playedFrames: Long = 0,
    val metrics: LinkMetrics.Snapshot = LinkMetrics.Snapshot(),
    val remoteVolume: RemoteVolumeState = RemoteVolumeState()
)

/** Durable receiver intent with independent transport ownership for every source. */
object ClientSessionController {
    private const val PREFS = "wfas_client_runtime"
    private const val MAX_SOURCES = 4
    private class Entry(var state: ReceiverSourceState) {
        var transport: ReceiverTransport? = null
        var job: Job? = null
        var retry: Job? = null
        var closing: Job? = null
        var generation = 0L
        var attempts = 0
        var playbackFailures = 0
        var key = ""
        var fromInvite = false
        var epoch: Long? = null
        var adaptiveOverride: Boolean? = null
        var latencyOverride: Int? = null
        val connectionCue = ConnectionCuePolicy()
        var settingsOverride: com.cuscus.wifiaudiostreaming.data.AppSettings? = null
        var blocked = false
        var waitingNetwork = false
        var credentialSaved = false
        var keySave: Job? = null
        var credentialError = false
    }
    private val entries = LinkedHashMap<String, Entry>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _sources = MutableStateFlow<List<ReceiverSourceState>>(emptyList())
    val sources: StateFlow<List<ReceiverSourceState>> = _sources
    private var app: Context? = null
    private var loaded = false
    private var paused = false
    private var savingFailed = false
    private var microphoneSource: String? = null
    private val delays = longArrayOf(1000, 2000, 5000, 10000)

    fun wantsConnection(): Boolean = !paused && _sources.value.isNotEmpty()
    fun hasReconnectIntent(context: Context): Boolean { initialize(context); return entries.isNotEmpty() }
    fun hasRememberedTarget(context: Context): Boolean = hasReconnectIntent(context)

    private fun initialize(context: Context) {
        app = context.applicationContext
        if (loaded) return
        loaded = true
        val prefs = app!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        try {
            val saved = prefs.getString("sources_v2", null)
            if (saved != null) {
                require(saved.length <= 16384) { "Receiver configuration too large" }
                val array = JSONArray(saved)
                require(array.length() <= MAX_SOURCES) { "Too many persisted sources" }
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val peer = decodePeer(item)
                    val id = item.getString("id")
                    UUID.fromString(id)
                    require(!entries.containsKey(id)) { "Duplicate persisted source identity" }
                    require(item.optDouble("volume", 1.0).isFinite()) { "Invalid persisted source gain" }
                    val e = Entry(ReceiverSourceState(id, peer,
                        volume = item.optDouble("volume", 1.0).toFloat().coerceIn(0f, 1f),
                        muted = item.optBoolean("muted", false)))
                    if (item.has("adaptive")) e.adaptiveOverride = item.getBoolean("adaptive")
                    if (item.has("latency")) e.latencyOverride = item.getInt("latency").coerceIn(20, 400)
                    e.credentialSaved = item.optBoolean("credential", false)
                    e.fromInvite = item.optBoolean("invite", false)
                    e.epoch = if (item.has("epoch")) item.getLong("epoch") else null
                    entries[e.state.id] = e
                }
            } else if (prefs.getBoolean("desired", false)) {
                // Migrate the exact old target; no global buffer/mode preference changes.
                val format = if (prefs.getBoolean("has_audio_format", false))
                    StreamAudioFormat(prefs.getInt("audio_sample_rate", 48000),
                        prefs.getInt("audio_channels", 2), prefs.getInt("audio_bit_depth", 16)) else null
                val peer = ServerInfo(prefs.getString("ip", "").orEmpty(), prefs.getBoolean("multicast", false),
                    prefs.getInt("port", 9090), prefs.getString("hostname", "").orEmpty(),
                    prefs.getString("security_mode", null), prefs.getBoolean("encrypted", false),
                    prefs.getBoolean("server_sends_mic", false), prefs.getBoolean("server_wants_mic", false),
                    prefs.getBoolean("from_beacon", false), format, prefs.getBoolean("via_usb", false))
                require(peer.ip.isNotBlank() && peer.port in 1..65535) { "Invalid old receiver target" }
                val id = UUID.randomUUID().toString()
                entries[id] = Entry(ReceiverSourceState(id, peer))
                persist()
            }
        } catch (failure: Exception) {
            savingFailed = true
            RuntimeDiagnostics.event("SOURCE_CONFIG_INVALID", failure.javaClass.simpleName, true)
            NetworkManager.connectionStatus.value = app!!.getString(R.string.source_storage_error)
        }
        publish()
    }

    fun enterReceiverMode(context: Context) {
        initialize(context); paused = false
        RoleSelectionGate.initialize(app!!); RoleSelectionGate.selectReceiver(app!!)
        NetworkManager.startNetworkWatch(app!!)
        if (ensureClientService()) resumeIfNeeded(app!!)
    }

    fun connect(context: Context, serverInfo: ServerInfo, presharedKey: String? = null,
                settingsOverride: com.cuscus.wifiaudiostreaming.data.AppSettings? = null,
                keyFromInvite: Boolean = presharedKey != null) {
        initialize(context)
        if (NetworkManager.isServerStreaming) NetworkManager.stopStreaming(app!!)
        RoleSelectionGate.selectReceiver(app!!)
        StreamingActionReceiver.clearTaskRemovedStop(app!!)
        paused = false
        val old = entries.values.firstOrNull {
            it.state.peer.ip == serverInfo.ip && it.state.peer.port == serverInfo.port &&
                it.state.peer.isMulticast == serverInfo.isMulticast
        }
        if (old == null && entries.size >= MAX_SOURCES) {
            NetworkManager.connectionStatus.value = app!!.getString(R.string.source_capacity, MAX_SOURCES)
            return
        }
        val e = old ?: Entry(ReceiverSourceState(UUID.randomUUID().toString(), serverInfo))
        stopEntry(e)
        e.connectionCue.reset()
        e.state = e.state.copy(peer = serverInfo, connected = false, needsAttention = false,
            remoteVolume = RemoteVolumeState())
        e.settingsOverride = settingsOverride
        if (presharedKey != null) { e.key = presharedKey; e.fromInvite = keyFromInvite }
        else e.fromInvite = false
        e.epoch = NetworkManager.expectedMcastEpoch.takeIf { presharedKey != null && serverInfo.isMulticast }
        e.blocked = false; e.attempts = 0; e.playbackFailures = 0
        entries[e.state.id] = e
        persist()
        NetworkManager.startNetworkWatch(app!!)
        if (ensureClientService()) startAttempt(e)
        publish()
    }

    fun restartCurrent(context: Context) {
        initialize(context); paused = false
        RoleSelectionGate.selectReceiver(app!!); StreamingActionReceiver.clearTaskRemovedStop(app!!)
        for (e in entries.values) {
            stopEntry(e); e.connectionCue.reset()
            e.blocked = false; e.playbackFailures = 0; e.attempts = 0
        }
        NetworkManager.startNetworkWatch(app!!)
        if (ensureClientService()) resumeIfNeeded(app!!)
    }

    fun retry(id: String) {
        val e = entries[id] ?: return
        stopEntry(e); e.connectionCue.reset()
        e.blocked = false; e.playbackFailures = 0; e.attempts = 0
        if (ensureClientService()) startAttempt(e)
    }

    fun resumeIfNeeded(context: Context) {
        initialize(context)
        if (paused || !RoleSelectionGate.isReceiverSelected()) return
        for (e in entries.values) {
            if (e.blocked) continue
            val path = pathFor(e)
            if (e.transport != null && path?.fingerprint != e.transport!!.networkFingerprint) stopEntry(e)
            if (e.retry?.isActive == true && e.waitingNetwork && path != null) { e.retry?.cancel(); e.retry = null }
            if (e.job?.isActive != true) startAttempt(e)
        }
    }

    private fun pathFor(e: Entry): DestinationNetwork.Path? {
        val context = app ?: return null
        val iface = e.settingsOverride?.networkInterface ?: currentInterface
        return try { DestinationNetwork.select(context, InetAddress.getByName(e.state.peer.ip), iface) }
        catch (failure: Exception) {
            RuntimeDiagnostics.event("SOURCE_ROUTE_ERROR", failure.javaClass.simpleName, true)
            null
        }
    }
    private var currentInterface = "Auto"

    private fun startAttempt(e: Entry) {
        val context = app ?: return
        // A failed attempt publishes streaming=false, which wakes ClientService.
        // That observer must not bypass the retry job's backoff deadline.
        if (paused || e.blocked || e.job?.isActive == true || e.retry?.isActive == true ||
            !RoleSelectionGate.isReceiverSelected()) return
        val token = ++e.generation
        e.job = scope.launch(start = CoroutineStart.LAZY) {
            try {
            if (withTimeoutOrNull(2500) { e.closing?.join(); true } != true || ReceiverMixer.hasOutput(e.state.id)) {
                e.blocked = true; e.job = null
                e.state = e.state.copy(status = context.getString(R.string.source_playback_error), needsAttention = true)
                publish(); return@launch
            }
            e.closing = null
            val settings = e.settingsOverride ?: SettingsDataStore(context).settingsFlow.first()
            if (e.key.isEmpty() && e.credentialSaved) {
                e.key = withContext(Dispatchers.IO) { SourceCredentials.read(context, e.state.id) }.orEmpty()
            }
            if (!owns(e, token)) return@launch
            if (e.settingsOverride == null) currentInterface = settings.networkInterface
            val matches = NetworkManager.discoveredDevices.value.values.filter {
                e.state.peer.hostname.isNotBlank() && it.hostname == e.state.peer.hostname &&
                    it.port == e.state.peer.port && it.isMulticast == e.state.peer.isMulticast
            }
            if (matches.size == 1 && matches[0].lastSeen > e.state.peer.lastSeen)
                e.state = e.state.copy(peer = matches[0])
            val path = pathFor(e)
            if (path == null) {
                e.waitingNetwork = true
                e.job = null
                e.state = e.state.copy(status = context.getString(R.string.source_wait_network), connected = false)
                schedule(e, token, offline = true); publish(); return@launch
            }
            e.waitingNetwork = false
            val auto = e.adaptiveOverride ?: settings.adaptiveLatency
            val latency = e.latencyOverride ?: if (e.state.peer.viaUsb || UsbLink.isUsbPeer(e.state.peer.ip))
                UsbLink.effectiveLatencyMs(settings.latencyMs) else settings.latencyMs
            e.state = e.state.copy(adaptive = auto, manualLatencyMs = latency,
                status = context.getString(R.string.notif_connecting), needsAttention = false,
                remoteVolume = RemoteVolumeState())
            val transport = ReceiverTransport(context, e.state.id, e.state.peer, settings, auto, latency,
                e.key, e.fromInvite, e.epoch, path,
                onStatus = { status, connected -> scope.launch {
                    if (owns(e, token)) {
                        if (connected) e.attempts = 0
                        e.state = e.state.copy(status = status, connected = connected); publish()
                    }
                } },
                onKey = { key -> scope.launch {
                    if (owns(e, token)) {
                        e.key = key
                        e.keySave = scope.launch {
                            try {
                                withContext(Dispatchers.IO) { SourceCredentials.save(context, e.state.id, key) }
                                if (owns(e, token)) { e.credentialSaved = true; e.credentialError = false; persist() }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) {
                                e.credentialError = true
                                RuntimeDiagnostics.event("SOURCE_CREDENTIAL_SAVE_FAILED", "source=${e.state.id} type=${failure.javaClass.simpleName}", true)
                            }
                            publish()
                        }
                    }
                } },
                onStats = { metrics, target, buffered, frames -> scope.launch {
                    if (owns(e, token)) {
                        e.state = e.state.copy(metrics = metrics, targetMs = target, bufferedMs = buffered,
                            playedFrames = frames); publish()
                    }
                } },
                onFinished = { problem ->
                    if (owns(e, token)) {
                        NetworkManager.stopReceiverMicrophone(e.state.id)
                        if (microphoneSource == e.state.id) microphoneSource = null
                        e.transport = null; e.job = null
                        if (problem.reason.startsWith("PLAYBACK")) e.playbackFailures++
                        e.blocked = !problem.retryable || e.playbackFailures >= 3
                        e.state = e.state.copy(status = if (problem.reason.startsWith("PLAYBACK"))
                            context.getString(R.string.source_playback_error) else problem.message,
                            connected = false, needsAttention = e.blocked, remoteVolume = RemoteVolumeState())
                        if (!e.blocked) schedule(e, token)
                        publish()
                    }
                },
                onMicrophoneReady = { direction -> scope.launch {
                    if (owns(e, token) && settings.sendClientMicrophone && microphoneSource == null && e.state.peer.acceptsClientMic) {
                        microphoneSource = e.state.id
                        NetworkManager.startReceiverMicrophone(context, e.state.id, e.state.peer, settings, direction)
                    }
                } },
                onRemoteVolume = { value -> scope.launch {
                    if (owns(e, token)) { e.state = e.state.copy(remoteVolume = value); publish() }
                } }, connectionCue = e.connectionCue.beginAttempt(settings.connectionSoundEnabled))
            e.transport = transport
            persist(); publish()
            withContext(Dispatchers.IO) { transport.run() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (owns(e, token)) {
                    e.transport?.stop(); e.transport = null; e.job = null; e.blocked = true
                    e.state = e.state.copy(status = context.getString(R.string.source_start_error),
                        connected = false, needsAttention = true)
                    RuntimeDiagnostics.event("SOURCE_START_FAILED",
                        "source=${e.state.id} type=${failure.javaClass.simpleName} detail=${failure.message}", true)
                    publish()
                }
            }
        }
        e.job!!.start()
    }

    private fun owns(e: Entry, token: Long) = entries[e.state.id] === e && e.generation == token && !paused
    private fun schedule(e: Entry, token: Long, offline: Boolean = false) {
        if (!owns(e, token) || e.blocked || e.retry?.isActive == true) return
        val wait = if (offline) 10_000L else delays[e.attempts.coerceAtMost(delays.lastIndex)]
        if (!offline) e.attempts++
        RuntimeDiagnostics.event("SOURCE_RETRY_SCHEDULED", "source=${e.state.id} generation=$token delayMs=$wait offline=$offline")
        e.retry = scope.launch {
            delay(wait)
            e.retry = null
            if (owns(e, token)) startAttempt(e)
        }
    }

    fun pauseKeepTarget(context: Context? = null) {
        context?.let { initialize(it) }
        paused = true
        entries.values.forEach(::stopEntry)
        publish()
    }
    fun userDisconnect(context: Context? = null) {
        context?.let { initialize(it) }
        pauseKeepTarget()
        entries.values.forEach(::removeCredential)
        entries.clear(); persist(); publish()
    }
    fun remove(id: String) {
        val e = entries.remove(id) ?: return
        stopEntry(e); removeCredential(e); persist(); publish()
    }
    fun setVolume(id: String, volume: Float, save: Boolean = true) {
        val e = entries[id] ?: return
        if (!volume.isFinite()) return
        e.state = e.state.copy(volume = volume.coerceIn(0f, 1f))
        if (save) persist()
        publish()
    }
    fun setMuted(id: String, muted: Boolean) {
        val e = entries[id] ?: return
        e.state = e.state.copy(muted = muted); persist(); publish()
    }
    fun setRemoteVolume(id: String, percent: Int) {
        require(percent in 0..100)
        val e = entries[id] ?: return
        if (e.transport?.setRemoteVolume(percent) != true) {
            e.state = e.state.copy(remoteVolume = e.state.remoteVolume.copy(
                pending = false, error = "UNAVAILABLE"))
            publish()
        }
    }
    fun setLatency(id: String, adaptive: Boolean, manualMs: Int) {
        val e = entries[id] ?: return
        require(manualMs in 20..400)
        e.adaptiveOverride = adaptive; e.latencyOverride = manualMs
        e.state = e.state.copy(adaptive = adaptive, manualLatencyMs = manualMs)
        persist()
        e.transport?.configureLatency(adaptive, manualMs)
        publish()
    }
    fun updateMasterVolume() { publish() }
    fun setNoiseReduction(enabled: Boolean, strength: Int) {
        entries.values.forEach { it.transport?.setNoiseReduction(enabled, strength) }
    }
    fun stopTransports() { entries.values.forEach(::stopEntry); publish() }
    fun foregroundUnavailable(context: Context, message: String) {
        initialize(context)
        for (e in entries.values) {
            stopEntry(e); e.blocked = true
            e.state = e.state.copy(status = message, needsAttention = true)
        }
        publish()
    }

    private fun stopEntry(e: Entry) {
        e.generation++
        e.transport?.stop(); e.transport = null
        if (e.job != null) { e.closing = e.job; e.job?.cancel(); e.job = null }
        e.retry?.cancel(); e.retry = null
        NetworkManager.stopReceiverMicrophone(e.state.id)
        if (microphoneSource == e.state.id) microphoneSource = null
        e.state = e.state.copy(connected = false, remoteVolume = RemoteVolumeState())
    }
    private fun publish() {
        _sources.value = entries.values.map {
            if (it.credentialError) it.state.copy(status = app?.getString(R.string.source_credential_error).orEmpty())
            else it.state
        }
        ReceiverMixer.setWeights(entries.values.associate {
            it.state.id to if (it.state.muted) 0f else it.state.volume
        }, NetworkManager.clientVolume.value)
        if (NetworkManager.isServerStreaming) return
        val states = _sources.value
        NetworkManager.isStreamingCurrent.value = !paused && entries.values.any { it.transport != null }
        NetworkManager.unicastPeerConnected.value = states.any { it.connected && !it.peer.isMulticast }
        NetworkManager.activePeerIp = states.firstOrNull { it.connected }?.peer?.ip
        NetworkManager.sessionEncryptedLive.value = entries.values.any { it.transport?.isEncrypted == true }
        LinkMetrics.publishSources(states.map { it.metrics })
        val context = app ?: return
        NetworkManager.connectionStatus.value = when {
            savingFailed -> context.getString(R.string.source_storage_error)
            states.isEmpty() || paused -> context.getString(R.string.status_idle)
            ReceiverMixer.focusGain == 0f -> context.getString(R.string.source_audio_focus_wait)
            states.size == 1 -> states[0].status
            else -> context.getString(R.string.source_summary, states.count { it.connected }, states.size)
        }
    }

    private fun persist() {
        val context = app ?: return
        val array = JSONArray()
        for (e in entries.values) {
            val json = encodePeer(e.state.peer).put("id", e.state.id)
                .put("volume", e.state.volume.toDouble()).put("muted", e.state.muted)
                .put("credential", e.credentialSaved).put("invite", e.fromInvite)
            e.epoch?.let { json.put("epoch", it) }
            e.adaptiveOverride?.let { json.put("adaptive", it) }
            e.latencyOverride?.let { json.put("latency", it) }
            array.put(json)
        }
        // Explicit remove/stop must reach disk before a process restart can
        // restore stale intent. This small bounded configuration has no keys.
        savingFailed = !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("sources_v2", array.toString()).remove("desired").commit()
        if (savingFailed) RuntimeDiagnostics.event("SOURCE_CONFIG_WRITE_FAILED", critical = true)
    }
    private fun encodePeer(p: ServerInfo): JSONObject = JSONObject().put("ip", p.ip).put("port", p.port)
        .put("multicast", p.isMulticast).put("hostname", p.hostname).put("security", p.securityMode)
        .put("encrypted", p.encrypted).put("sendsMic", p.serverSendsMic).put("wantsMic", p.serverWantsMic)
        .put("beacon", p.fromBeacon).put("usb", p.viaUsb).apply {
            p.audioFormat?.let { put("rate", it.sampleRate); put("channels", it.channels); put("bits", it.bitDepth) }
        }
    private fun decodePeer(j: JSONObject): ServerInfo {
        val ip = j.getString("ip"); val port = j.getInt("port")
        require(ip.isNotBlank() && port in 1..65535)
        val format = if (j.has("rate")) StreamAudioFormat(j.getInt("rate"), j.getInt("channels"), j.getInt("bits")) else null
        return ServerInfo(ip, j.getBoolean("multicast"), port, j.optString("hostname"),
            if (j.isNull("security")) null else j.optString("security"), j.optBoolean("encrypted"),
            j.optBoolean("sendsMic"), j.optBoolean("wantsMic"), j.optBoolean("beacon"), format, j.optBoolean("usb"))
    }
    private fun ensureClientService(): Boolean {
        val context = app ?: return false
        if (!NotificationCenter.canPost(context)) {
            val message = context.getString(R.string.source_notification_permission)
            RuntimeDiagnostics.event("RECEIVER_NOTIFICATION_PERMISSION_DENIED", critical = true)
            foregroundUnavailable(context, message)
            NetworkManager.connectionStatus.value = message
            return false
        }
        try {
            val intent = Intent(context, ClientService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
            return true
        } catch (failure: Exception) {
            if (Build.VERSION.SDK_INT < 31 || failure !is android.app.ForegroundServiceStartNotAllowedException) throw failure
            entries.values.forEach { it.blocked = true; it.state = it.state.copy(
                needsAttention = true, status = context.getString(R.string.source_foreground_denied)) }
            RuntimeDiagnostics.event("RECEIVER_FOREGROUND_DENIED", failure.javaClass.simpleName, true)
            publish(); return false
        }
    }
    private fun removeCredential(e: Entry) {
        if (!e.credentialSaved && e.keySave == null) return
        val context = app ?: return
        val pending = e.keySave
        pending?.cancel()
        scope.launch {
            pending?.join()
            try { withContext(Dispatchers.IO) { SourceCredentials.remove(context, e.state.id) } }
            catch (failure: Exception) {
                savingFailed = true
                RuntimeDiagnostics.event("SOURCE_CREDENTIAL_REMOVE_FAILED", failure.javaClass.simpleName, true)
                publish()
            }
        }
    }
}
