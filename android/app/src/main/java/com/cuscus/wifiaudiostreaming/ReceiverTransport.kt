package com.cuscus.wifiaudiostreaming

import android.content.Context
import android.os.SystemClock
import com.cuscus.wifiaudiostreaming.data.AppSettings
import com.cuscus.wifiaudiostreaming.data.SettingsDataStore
import com.cuscus.wifiaudiostreaming.diagnostics.RuntimeDiagnostics
import kotlinx.coroutines.*
import java.net.*

internal class SourceFailure(val reason: String, override val message: String, val retryable: Boolean = true) : Exception(message)

/** One authenticated source, with its own socket, cipher, counters and player. */
internal class ReceiverTransport(
    private val context: Context, val id: String, private val peer: ServerInfo,
    private val settings: AppSettings, @Volatile private var adaptive: Boolean, @Volatile private var latencyMs: Int,
    private var key: String, private val fromInvite: Boolean, private val expectedEpoch: Long?,
    private val path: DestinationNetwork.Path,
    private val onStatus: (String, Boolean) -> Unit,
    private val onKey: (String) -> Unit,
    private val onStats: (LinkMetrics.Snapshot, Int, Int, Long) -> Unit,
    private val onFinished: (SourceFailure) -> Unit,
    private val onMicrophoneReady: (WfasCrypto.Dir?) -> Unit,
    private val onRemoteVolume: (RemoteVolumeState) -> Unit
) {
    val networkFingerprint get() = path.fingerprint
    @Volatile private var socket: DatagramSocket? = null
    private var remoteEndpoint: InetSocketAddress? = null
    @Volatile private var remoteVolume: RemoteVolumeProtocol.Client? = null
    private var lastRemoteState: RemoteVolumeState? = null
    private var rejectedEndpoints = 0L
    @Volatile private var stopped = false
    @Volatile private var failure: SourceFailure? = null
    private var player: ReceiverPlayout? = null
    private var received = false
    private var encrypted = peer.encrypted
    private var recvDir: WfasCrypto.Dir? = null
    private var replay = WfasCrypto.ReplayWindow()
    private var groupEpoch = -1L
    private var badBeacons = 0
    private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null
    private var lastMetrics: PacketMetrics? = null
    private var discardedLatePcm = 0L
    private var lastGroupSalt: ByteArray? = null
    val isEncrypted get() = encrypted
    @Volatile private var nrEnabled = settings.developerMode && settings.noiseReductionEnabled
    @Volatile private var nrStrength = settings.noiseReductionStrength
    private val denoiser = com.cuscus.wifiaudiostreaming.dsp.NoiseReducer()
    fun setNoiseReduction(enabled: Boolean, strength: Int) {
        nrEnabled = enabled; nrStrength = strength.coerceIn(0, 100)
    }
    fun configureLatency(automatic: Boolean, manual: Int) {
        adaptive = automatic; latencyMs = manual
        player?.configure(automatic, manual)
    }

    private fun now() = SystemClock.elapsedRealtime()
    private fun text(resource: Int) = context.getString(resource)
    private fun status(message: String, connected: Boolean = received) = onStatus(message, connected)
    private fun reject(reason: String, resource: Int, retry: Boolean = false): Nothing =
        throw SourceFailure(reason, text(resource), retry)

    suspend fun run() {
        var outcome = SourceFailure("CANCELLED", text(R.string.status_idle), false)
        val format = peer.audioFormat ?: StreamAudioFormat(settings.sampleRate,
            if (settings.channelConfig == "STEREO") 2 else 1, 16)
        RuntimeDiagnostics.event("SOURCE_SESSION_START", "source=$id multicast=${peer.isMulticast}", true)
        try {
            if (!format.isPlayable) throw SourceFailure("UNSUPPORTED_FORMAT",
                context.getString(R.string.status_unsupported_format, format.describe()), false)
            val remote = InetAddress.getByName(peer.ip)
            remoteEndpoint = InetSocketAddress(remote, peer.port)
            if (peer.isMulticast) {
                val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                multicastLock = wifi.createMulticastLock("wfas:source:$id").apply { setReferenceCounted(false); acquire() }
            }
            val sock = if (peer.isMulticast) MulticastSocket(null).apply {
                reuseAddress = true
                path.bind(this)
                bind(InetSocketAddress(peer.port))
                val iface = NetworkInterface.getByName(path.name)
                    ?: NetworkManager.getWifiNetworkInterface(settings.networkInterface)
                    ?: throw SourceFailure("NETWORK_UNAVAILABLE", text(R.string.source_wait_network))
                networkInterface = iface
                joinGroup(InetSocketAddress(NetworkManager.MULTICAST_GROUP, peer.port), iface)
            } else DatagramSocket(null).apply {
                path.bind(this)
                bind(InetSocketAddress(path.localAddress, 0))
            }
            socket = sock
            RuntimeDiagnostics.event("SOURCE_SOCKET_READY",
                "source=$id interface=${path.name} network=${path.network?.networkHandle ?: 0} local=${sock.localSocketAddress} peer=${peer.ip}:${peer.port}", true)
            // Socket exists before output initialization: a stalled device call
            // cannot prevent handshake/control packets from being processed.
            sock.receiveBufferSize = 1 shl 18
            sock.soTimeout = 250
            if (!peer.isMulticast) handshake(sock)
            else {
                publishRemoteVolume(RemoteVolumeState(RemoteVolumeSupport.UNSUPPORTED))
                groupEpoch = expectedEpoch?.minus(1) ?: SettingsDataStore(context).getMcastClientEpoch(peer.ip)
                encrypted = encrypted || key.isNotEmpty() || fromInvite || expectedEpoch != null
            }
            val metrics = PacketMetrics(format.sampleRate)
            lastMetrics = metrics
            player = ReceiverPlayout(id, format, adaptive, latencyMs) { detail ->
                failure = SourceFailure("PLAYBACK_FAILURE", detail)
                sock.close()
            }
            val buffer = ByteArray(65536)
            val packet = DatagramPacket(buffer, buffer.size)
            var lastActivity = now()
            var lastReport = lastActivity
            var lastJournal = lastActivity
            var connectedAnnounced = false
            var inSilence = true
            var previousPcm: ByteArray? = null
            var previousSeq = -1
            fun report() {
                val snapshot = metrics.snapshot(if (peer.viaUsb) "USB" else "WIFI")
                onStats(snapshot, player!!.targetMs, player!!.bufferedMs, player!!.playedFrames)
                if (now() - lastJournal >= 10_000) {
                    RuntimeDiagnostics.event("SOURCE_NETWORK_STATS",
                        "source=$id ${snapshot.format()} duplicates=${snapshot.duplicates} latePcmDiscard=$discardedLatePcm rejectedEndpoints=$rejectedEndpoints")
                    lastJournal = now()
                }
                lastReport = now()
            }
            while (currentCoroutineContext().isActive && !stopped) {
                tickRemoteVolume(sock)
                failure?.let { throw it }
                player?.blockedReason()?.let { throw SourceFailure("PLAYBACK_OPERATION_TIMEOUT", it) }
                try {
                    packet.length = buffer.size
                    sock.receive(packet)
                } catch (_: SocketTimeoutException) {
                    if (now() - lastActivity > 30_000) reject("NETWORK_ACTIVITY_TIMEOUT", R.string.status_server_disconnected, true)
                    if (now() - lastReport >= 1000) {
                        report()
                    }
                    continue
                }
                // A multicast group can contain several senders. Each source
                // must only consume its selected sender, not mix the group twice.
                if (!isPeer(packet)) continue
                val length = packet.length
                if (length <= 0) continue
                if (!WfasDatagram.isAudio(buffer, length, peer.isMulticast)) {
                    val control = String(buffer, 0, length, Charsets.US_ASCII).trim()
                    when {
                        control == "PING" && !peer.isMulticast -> { send(sock, "PONG"); lastActivity = now() }
                        control == "BYE" -> reject("SERVER_BYE", R.string.status_server_disconnected, true)
                        !peer.isMulticast && control.startsWith(RemoteVolumeProtocol.STATE) -> {
                            if (remoteVolume?.receive(control) == true) {
                                lastActivity = now()
                                remoteVolume?.state()?.let(::publishRemoteVolume)
                            }
                        }
                        peer.isMulticast && control.startsWith(WfasCrypto.MSG_MCAST_ENC + ";") -> {
                            if (groupBeacon(control)) lastActivity = now()
                        }
                    }
                    continue
                }
                val version = buffer[2].toInt() and 255
                if (version != NetworkManager.WFAS_PROTOCOL_VERSION) {
                    NetworkManager.reportProtocolMismatch(version, PeerRole.SENDER)
                    reject("PROTOCOL_MISMATCH", R.string.status_protocol_incompatible)
                }
                val flags: Int
                val seq: Int
                val samplePosition: Long
                val pcm: ByteArray
                if ((buffer[3].toInt() and WfasCrypto.FLAG_ENCRYPTED) != 0) {
                    val dir = recvDir ?: continue
                    val result = WfasCrypto.decryptPacket(dir, replay, buffer, length)
                    if (result !is WfasCrypto.Decrypted.Ok) continue
                    flags = result.header.flags; seq = result.header.seq
                    samplePosition = result.header.samplePos; pcm = result.pcm
                } else {
                    if (encrypted) continue // Never downgrade an authenticated/encrypted source.
                    flags = buffer[3].toInt() and 255
                    seq = ((buffer[4].toInt() and 255) shl 8) or (buffer[5].toInt() and 255)
                    samplePosition = ((buffer[6].toLong() and 255) shl 24) or
                        ((buffer[7].toLong() and 255) shl 16) or
                        ((buffer[8].toLong() and 255) shl 8) or (buffer[9].toLong() and 255)
                    pcm = buffer.copyOfRange(10, length)
                }
                if (pcm.size % (format.channels * 2) != 0) continue
                val silence = (flags and 1) != 0 || PcmSilence.isZero(pcm, 0, pcm.size)
                lastActivity = now()
                val fresh = metrics.observe(seq, samplePosition, silence)
                if (!fresh && !silence) discardedLatePcm++
                if (!received) {
                    received = true
                    status(text(R.string.status_streaming), true)
                }
                if (!silence && fresh) {
                    val gap = if (previousSeq < 0) 0 else (seq - previousSeq - 1) and 65535
                    if (gap in 1..3 && previousPcm != null) {
                        // Cover only a brief hole; long losses never repeat old
                        // audio. The FIFO's duration bound also applies to PLC.
                        val old = previousPcm!!
                        val frame = format.channels * 2
                        val frames = minOf(old.size / frame * gap, format.sampleRate / 100)
                        val tail = ByteArray(frames * frame)
                        for (i in 0 until frames) for (ch in 0 until format.channels) {
                            val at = old.size - frame + ch * 2
                            val value = ((old[at].toInt() and 255) or (old[at + 1].toInt() shl 8)).toShort().toInt()
                            val scaled = value * (frames - i) / maxOf(1, frames)
                            val out = i * frame + ch * 2
                            tail[out] = scaled.toByte(); tail[out + 1] = (scaled shr 8).toByte()
                        }
                        player!!.offer(tail)
                        inSilence = true
                    }
                    if (nrEnabled) {
                        denoiser.init(format.sampleRate, format.channels)
                        denoiser.setStrength(nrStrength / 100f)
                        val samples = ShortArray(pcm.size / 2) { at ->
                            ((pcm[at * 2].toInt() and 255) or (pcm[at * 2 + 1].toInt() shl 8)).toShort()
                        }
                        denoiser.process(samples, 0, samples.size)
                        for (at in samples.indices) {
                            pcm[at * 2] = samples[at].toByte()
                            pcm[at * 2 + 1] = (samples[at].toInt() shr 8).toByte()
                        }
                    }
                    if (!connectedAnnounced && settings.connectionSoundEnabled) {
                        // Controller passes false for an established logical source's retry.
                        NetworkManager.playConnectionSound(context); connectedAnnounced = true
                    }
                    if (inSilence) fadeIn(pcm, format.channels)
                    player!!.offer(pcm)
                    previousPcm = pcm
                    inSilence = false
                } else if (silence) { inSilence = true; previousPcm = null }
                if (fresh) previousSeq = seq
                if (now() - lastReport >= 1000) {
                    report()
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (problem: SourceFailure) { outcome = problem }
        catch (problem: Exception) {
            outcome = failure ?: SourceFailure("TRANSPORT_ERROR",
                context.getString(R.string.source_transport_error, problem.javaClass.simpleName))
            RuntimeDiagnostics.event("SOURCE_TRANSPORT_ERROR", "source=$id type=${problem.javaClass.simpleName} detail=${problem.message}", true)
        } finally {
            if (!peer.isMulticast) socket?.let { s ->
                try { if (!s.isClosed) send(s, "CLIENT_BYE") } catch (_: Exception) { /* Already closed/lost. */ }
            }
            socket?.close(); socket = null
            multicastLock?.takeIf { it.isHeld }?.release(); multicastLock = null
            player?.stop()
            withContext(NonCancellable) {
                if (player?.awaitStopped() == false) outcome = SourceFailure(
                    "PLAYBACK_CLEANUP_TIMEOUT", text(R.string.source_playback_error), false)
            }
            RuntimeDiagnostics.event("SOURCE_SESSION_END", "source=$id reason=${outcome.reason} received=$received metrics=${lastMetrics?.snapshot()?.format()} latePcmDiscard=$discardedLatePcm rejectedEndpoints=$rejectedEndpoints", true)
            withContext(NonCancellable + Dispatchers.Main.immediate) { if (!stopped) onFinished(outcome) }
        }
    }

    private suspend fun obtainKey(wrong: Boolean): String {
        if (fromInvite && wrong) {
            NetworkManager.pendingInviteRejected.value = true
            reject("AUTH_REJECTED", R.string.status_unauthorized)
        }
        status(text(if (wrong) R.string.key_dialog_wrong else R.string.status_key_required), false)
        val answer = withTimeoutOrNull(60_000) {
            NetworkManager.requestKeyFromUi(wrong, peer.hostname.ifBlank { peer.ip })
        }
        if (answer.isNullOrBlank()) reject("KEY_REQUIRED", R.string.status_key_required)
        key = answer
        return answer
    }

    private suspend fun handshake(sock: DatagramSocket) {
        status(text(R.string.status_waiting_for_ack), false)
        val nonce = WfasAuth.nonceHex()
        val volumeNonce = RemoteVolumeProtocol.nonce()
        val helloBase = "HELLO_FROM_CLIENT;v=${NetworkManager.WFAS_PROTOCOL_VERSION};cnonce=$nonce" +
            RemoteVolumeProtocol.helloSuffix(volumeNonce)
        var hello = helloBase
        var serverNonce = ""
        var proved = false
        val start = now()
        var deadline = start + 30_000
        var lastReply = start
        var anyReply = false
        var lastSend = 0L
        val buffer = ByteArray(2048)
        val packet = DatagramPacket(buffer, buffer.size)
        while (currentCoroutineContext().isActive && !stopped && now() < deadline && now() < start + 120_000) {
            if (!anyReply && now() - start > 6000) reject("NO_ACK", R.string.status_server_silent_maybe_outdated, true)
            if (anyReply && now() - lastReply > 6000) reject("ACK_ACTIVITY_TIMEOUT", R.string.status_server_disconnected, true)
            if (now() - lastSend >= 1000) {
                send(sock, hello); lastSend = now()
                if (lastSend - start < 1000) RuntimeDiagnostics.event("SOURCE_HELLO_SENT", "source=$id", true)
            }
            try { packet.length = buffer.size; sock.receive(packet) } catch (_: SocketTimeoutException) { continue }
            if (!isPeer(packet)) continue
            val reply = String(buffer, 0, packet.length, Charsets.US_ASCII).trim()
            // Record only known message kinds, never raw authentication payloads.
            val replyKind = reply.substringBefore(';').takeIf {
                it in setOf("HELLO_ACK", "WFAS_INCOMPATIBLE", "WFAS_BUSY", "WFAS_PENDING", "WFAS_UNAUTHORIZED", "WFAS_AUTH_REQUIRED")
            }
            if (!anyReply || replyKind == "HELLO_ACK") RuntimeDiagnostics.event("SOURCE_HANDSHAKE_REPLY",
                "source=$id kind=${replyKind ?: "OTHER"} bytes=${packet.length} peer=${packet.socketAddress}", true)
            anyReply = true; lastReply = now()
            when {
                reply.startsWith("WFAS_INCOMPATIBLE") -> {
                    NetworkManager.reportProtocolMismatch(version(reply), PeerRole.SENDER)
                    reject("PROTOCOL_MISMATCH", R.string.status_protocol_incompatible)
                }
                reply == "WFAS_BUSY" -> reject("SERVER_BUSY", R.string.status_server_busy)
                reply == "WFAS_PENDING" -> {
                    status(text(R.string.status_awaiting_approval), false)
                    deadline = minOf(start + 120_000, now() + 6000)
                }
                reply == "WFAS_UNAUTHORIZED" -> {
                    obtainKey(true); proved = false
                    hello = helloBase
                    send(sock, hello); lastReply = now(); deadline = now() + 30_000
                }
                reply.startsWith("WFAS_AUTH_REQUIRED") -> {
                    if (key.isEmpty()) obtainKey(false)
                    serverNonce = WfasAuth.getToken(reply, "snonce").orEmpty()
                    val proof = WfasAuth.getToken(reply, "sproof").orEmpty()
                    if (!WfasAuth.constantTimeEquals(proof, WfasAuth.proof(key, 'S', nonce, serverNonce))) {
                        obtainKey(true); proved = false
                        hello = helloBase
                    } else {
                        hello = "$helloBase;cproof=${WfasAuth.proof(key, 'C', nonce, serverNonce)}"
                        proved = true
                    }
                    send(sock, hello); lastSend = now(); lastReply = now(); deadline = now() + 30_000
                }
                reply.startsWith("HELLO_ACK") -> {
                    if (version(reply) != NetworkManager.WFAS_PROTOCOL_VERSION) {
                        NetworkManager.reportProtocolMismatch(version(reply), PeerRole.SENDER)
                        reject("PROTOCOL_MISMATCH", R.string.status_protocol_incompatible)
                    }
                    if ((fromInvite || key.isNotEmpty()) && !proved) reject("AUTH_REJECTED", R.string.status_unauthorized)
                    encrypted = WfasAuth.getToken(reply, "enc") == "1"
                    if (encrypted && !proved) reject("AUTH_REJECTED", R.string.status_unauthorized)
                    val dirs = if (proved) WfasCrypto.deriveUnicast(key, nonce, serverNonce) else null
                    recvDir = if (encrypted) dirs?.second else null
                    remoteVolume = RemoteVolumeProtocol.accept(reply, volumeNonce,
                        if (proved) key else "", nonce, serverNonce)
                    if (remoteVolume == null) publishRemoteVolume(RemoteVolumeState(RemoteVolumeSupport.UNSUPPORTED))
                    else {
                        remoteVolume!!.query()
                        publishRemoteVolume(remoteVolume!!.state())
                        tickRemoteVolume(sock)
                    }
                    if (proved) onKey(key)
                    onMicrophoneReady(if (encrypted) dirs?.first else null)
                    status(text(R.string.source_connected_silent), false)
                    return
                }
            }
        }
        currentCoroutineContext().ensureActive()
        if (stopped) throw CancellationException("Source stopped during handshake")
        reject("ACK_TIMEOUT", R.string.status_server_disconnected, true)
    }

    private suspend fun groupBeacon(message: String): Boolean {
        if (key.isEmpty()) obtainKey(false)
        val info = WfasCrypto.parseMcastBeacon(key, message, -1L)
        if (info == null) {
            if (++badBeacons >= 6) {
                if (recvDir != null) reject("GROUP_REKEYED", R.string.status_group_rekeyed)
                obtainKey(true); badBeacons = 0
            }
            return false
        }
        badBeacons = 0
        if (expectedEpoch != null && expectedEpoch != info.epoch) {
            NetworkManager.pendingEpochMismatch.value = true
            reject("GROUP_EPOCH_MISMATCH", R.string.status_group_rekeyed)
        }
        if (info.epoch < groupEpoch) return false
        if (recvDir != null && info.epoch == groupEpoch) {
            if (!info.salt.contentEquals(lastGroupSalt)) reject("GROUP_SALT_CHANGED", R.string.status_group_rekeyed)
            return true // Do not reset replay state on each repeated authenticated beacon.
        }
        groupEpoch = info.epoch; lastGroupSalt = info.salt
        recvDir = WfasCrypto.deriveMulticast(key, info.salt)
        onKey(key)
        encrypted = true; replay = WfasCrypto.ReplayWindow()
        if (!fromInvite) SettingsDataStore(context).setMcastClientEpoch(peer.ip, info.epoch)
        return true
    }

    private fun send(sock: DatagramSocket, message: String) {
        val bytes = message.toByteArray(Charsets.US_ASCII)
        sock.send(DatagramPacket(bytes, bytes.size, checkNotNull(remoteEndpoint)))
    }
    private fun isPeer(packet: DatagramPacket): Boolean {
        val expected = remoteEndpoint ?: return false
        if (packet.address == expected.address && (peer.isMulticast || packet.port == expected.port)) return true
        rejectedEndpoints++
        if (rejectedEndpoints == 1L || rejectedEndpoints % 1000L == 0L) {
            RuntimeDiagnostics.event("SOURCE_ENDPOINT_REJECTED",
                "source=$id count=$rejectedEndpoints from=${packet.socketAddress} differentAddress=${packet.address != expected.address} differentPort=${packet.port != expected.port}")
        }
        return false
    }
    private fun tickRemoteVolume(sock: DatagramSocket) {
        val control = remoteVolume ?: return
        control.nextMessage(now())?.let {
            try { send(sock, it) }
            catch (failure: java.io.IOException) {
                // A volume request is optional. Its bounded retry/error path
                // must not tear down otherwise healthy incoming audio.
                RuntimeDiagnostics.event("SOURCE_VOLUME_SEND_FAILED",
                    "source=$id type=${failure.javaClass.simpleName}")
            }
        }
        publishRemoteVolume(control.state())
    }
    private fun publishRemoteVolume(state: RemoteVolumeState) {
        if (lastRemoteState == state) return
        lastRemoteState = state
        onRemoteVolume(state)
    }
    fun setRemoteVolume(percent: Int): Boolean {
        require(percent in 0..100)
        val control = remoteVolume ?: return false
        control.setPercent(percent)
        publishRemoteVolume(control.state())
        return true
    }
    private fun version(message: String) = WfasAuth.getToken(message, "v")?.toIntOrNull() ?: 0
    private fun fadeIn(pcm: ByteArray, channels: Int) {
        val frame = channels * 2
        val count = minOf(pcm.size / frame, 64)
        for (i in 0 until count) for (ch in 0 until channels) {
            val offset = i * frame + ch * 2
            val value = ((pcm[offset].toInt() and 255) or (pcm[offset + 1].toInt() shl 8)).toShort().toInt()
            val scaled = value * (i + 1) / maxOf(1, count)
            pcm[offset] = scaled.toByte(); pcm[offset + 1] = (scaled shr 8).toByte()
        }
    }
    fun stop() {
        stopped = true
        // Cancellation enters run's IO finally, sends CLIENT_BYE before closing
        // and releasing output. Do not send network data on the UI thread.
        player?.stop()
    }
}
