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

package com.cuscus.wifiaudiostreaming

import android.annotation.SuppressLint
import android.app.Service
import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.cuscus.wifiaudiostreaming.diagnostics.RuntimeDiagnostics
import com.cuscus.wifiaudiostreaming.data.AutoConnectEntry
import com.cuscus.wifiaudiostreaming.data.SecretStore
import com.cuscus.wifiaudiostreaming.data.SettingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException

class AutoConnectService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var isConnecting = false
    private var listenJob: Job? = null
    private var foregroundReady = false

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    override fun onCreate() {
        super.onCreate()
        RoleSelectionGate.initialize(applicationContext)
        NotificationCenter.ensureChannels(this)

        val notification = buildNotification(getString(R.string.auto_connect_listening), false)

        RuntimeDiagnostics.event("AUTO_CONNECT_CREATE", critical = true)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NotificationCenter.ID_AUTO_CONNECT, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NotificationCenter.ID_AUTO_CONNECT, notification)
            }
            foregroundReady = true
        } catch (error: RuntimeException) {
            if (Build.VERSION.SDK_INT < 31 || error !is ForegroundServiceStartNotAllowedException) throw error
            RuntimeDiagnostics.event("AUTO_CONNECT_FOREGROUND_REJECTED", error.message.orEmpty(), true)
            Log.e("AutoConnect", "Foreground discovery is unavailable; waiting for explicit foreground restart", error)
            stopSelf()
            return
        }

        serviceScope.launch {
            combine(
                NetworkManager.connectionStatus,
                NetworkManager.isStreamingCurrent
            ) { status, streaming -> status to streaming }
                .distinctUntilChanged()
                .conflate()
                .collect { (status, streaming) ->
                    val text = if (streaming && status.isNotBlank()) {
                        status
                    } else {
                        getString(R.string.auto_connect_listening)
                    }
                    NotificationCenter.post(
                        this@AutoConnectService,
                        NotificationCenter.ID_AUTO_CONNECT,
                        buildNotification(text, streaming)
                    )
                    delay(UPDATE_THROTTLE_MS)
                }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundReady) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (
            StreamingActionReceiver.wasRecentlyTaskRemoved(this) ||
            !RoleSelectionGate.isReceiverSelected()
        ) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (listenJob?.isActive == true) {
            return START_STICKY
        }

        Log.d("AutoConnect", "Servizio Avviato. Inizio loop di background.")

        listenJob = serviceScope.launch(Dispatchers.IO) {
            val settingsDataStore = SettingsDataStore(applicationContext)
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager

            while (isActive) {
                val prefs = settingsDataStore.settingsFlow.first()

                if (!prefs.autoConnectEnabled || !RoleSelectionGate.isReceiverSelected()) {
                    Log.d(
                        "AutoConnect",
                        "Auto-connect stopped: disabled or Receive is no longer selected."
                    )
                    stopSelf()
                    return@launch
                }

                if (NetworkManager.isStreamingCurrent.value || isConnecting) {
                    delay(2000)
                    continue
                }

                var targetServer: ServerInfo? = null
                var matchedEntry: AutoConnectEntry? = null
                val multicastLock = wifiManager.createMulticastLock("auto_connect_multicast_lock")
                multicastLock.setReferenceCounted(false)

                try {
                    multicastLock.acquire()
                    val groupAddress = InetAddress.getByName("239.255.0.1")

                    val socket = MulticastSocket(null).apply {
                        reuseAddress = true
                        bind(InetSocketAddress(9091))
                        soTimeout = 5000
                    }

                    try {
                        val interfaces = NetworkInterface.getNetworkInterfaces().toList()
                        val wifiIface = if (prefs.networkInterface != "Auto") {
                            interfaces.firstOrNull { it.displayName == prefs.networkInterface || it.name == prefs.networkInterface }
                        } else {
                            interfaces.firstOrNull { it.isUp && !it.isLoopback && !it.isVirtual && it.name.contains("wlan") }
                        }
                        if (wifiIface != null) {
                            socket.networkInterface = wifiIface
                            Log.d("AutoConnect", "Forzata interfaccia di rete: ${wifiIface.name}")
                        }
                    } catch (e: Exception) {}

                    MulticastNet.joinAllGroups(socket)
                    Log.d("AutoConnect", "Socket Multicast APERTO e in ASCOLTO sulla porta 9091")

                    val buffer = ByteArray(1024)
                    val packet = DatagramPacket(buffer, buffer.size)
                    val localIp = NetworkManager.getLocalIpAddress(applicationContext)

                    while (
                        isActive &&
                        !NetworkManager.isStreamingCurrent.value &&
                        !isConnecting
                    ) {
                        try {
                            socket.receive(packet)
                            val message = String(packet.data, 0, packet.length).trim()
                            val remoteIp = packet.address.hostAddress

                            if (remoteIp != null && remoteIp != localIp && message.startsWith("WIFI_AUDIO_STREAMER_DISCOVERY")) {
                                Log.d("AutoConnect", "-> Ricevuto UDP da $remoteIp: $message")

                                val parts = message.split(";")
                                if (parts.size >= 4) {
                                    val isMulticast = parts[2].equals("MULTICAST", ignoreCase = true)
                                    val beaconPort = parts[3].toIntOrNull() ?: continue

                                    val currentSsid = NetworkManager.getCurrentSsid(applicationContext).trim().removePrefix("\"").removeSuffix("\"")
                                    val priorityList = AutoConnectEntry.parseList(prefs.autoConnectList)
                                    // Disabled entries are skipped, exactly as on the desktop.
                                    val validEntries = priorityList.filter { it.ip.isNotBlank() && it.enabled }

                                    val matched = validEntries.firstOrNull { entry ->
                                        val cleanIp = entry.ip.trim()
                                        val cleanSsid = entry.ssid.trim().removePrefix("\"").removeSuffix("\"")
                                        val isSsidUnknown = currentSsid == "<unknown ssid>" || currentSsid.isEmpty()
                                        val matchesSsid = cleanSsid.isEmpty() || isSsidUnknown || cleanSsid == currentSsid
                                        matchesSsid && cleanIp == remoteIp
                                    }
                                    // Only a configured, enabled entry is a target. An empty
                                    // list connects to nothing, matching the desktop, which
                                    // returns early when its target list is empty: an
                                    // unconfigured auto-connect must not latch onto whatever
                                    // server happens to be broadcasting on the network.
                                    if (matched != null) {
                                        val port = matched?.port ?: beaconPort
                                        Log.d("AutoConnect", "MATCH POSITIVO. Preparo la connessione a $remoteIp:$port")
                                        val micTok = parts.firstOrNull { it.startsWith("mic=") }
                                            ?.removePrefix("mic=")
                                        targetServer = ServerInfo(
                                            remoteIp, isMulticast, port,
                                            serverSendsMic = micTok?.contains("tx") == true,
                                            serverWantsMic = micTok?.contains("rx") == true,
                                            fromBeacon = true,
                                            audioFormat = StreamAudioFormat.fromBeaconParts(parts)
                                        )
                                        matchedEntry = matched
                                        break
                                    }
                                }
                            }
                        } catch (e: SocketTimeoutException) {
                            continue
                        } catch (e: Exception) {
                            break
                        }
                    }

                    try {
                        MulticastNet.leaveAllGroups(socket)
                        socket.close()
                        Log.d("AutoConnect", "Socket CHIUSO correttamente.")
                    } catch (e: Exception) {}

                } catch (e: Exception) {
                    delay(3000)
                } finally {
                    if (multicastLock.isHeld) {
                        multicastLock.release()
                    }
                }

                if (targetServer != null) {
                    isConnecting = true
                    NetworkManager.isServerStreaming = false

                    // Auto-connect only discovers/selects a target. The actual
                    // session is owned by the same controller used by manual
                    // receiver connections, so silence, reconnect and service
                    // recreation all share one state machine.
                    val resolvedKey = matchedEntry?.keyRef
                        ?.let { SecretStore.get(applicationContext).getAutoConnectKey(it) }

                    try {
                        Log.d(
                            "AutoConnect",
                            "Handoff to ClientSessionController for ${targetServer.ip}:${targetServer.port}"
                        )
                        ClientSessionController.connect(
                            context = applicationContext,
                            serverInfo = targetServer,
                            presharedKey = resolvedKey
                        )
                        stopSelf()
                        return@launch
                    } catch (e: Exception) {
                        Log.e("AutoConnect", "Unable to hand off auto-connect session", e)
                        isConnecting = false
                        delay(2000)
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        RuntimeDiagnostics.event("AUTO_CONNECT_TASK_REMOVED", critical = true)
        StreamingActionReceiver.handleTaskRemoved(this)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        RuntimeDiagnostics.event("AUTO_CONNECT_DESTROY", "foregroundReady=$foregroundReady", true)
        Log.d("AutoConnect", "Servizio distrutto.")
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        NotificationCenter.cancel(this, NotificationCenter.ID_AUTO_CONNECT)
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // A timed-out dataSync service is no longer foreground. Stop promptly;
        // do not tear down the separate mediaPlayback service or clear user intent.
        foregroundReady = false
        RuntimeDiagnostics.event("AUTO_CONNECT_TIMEOUT", "startId=$startId type=$fgsType", true)
        serviceScope.cancel()
        if (!NetworkManager.isStreamingCurrent.value) {
            NetworkManager.connectionStatus.value = getString(R.string.auto_connect_unavailable)
        }
        stopSelf()
    }

    private fun buildNotification(statusText: String, streaming: Boolean) =
        NotificationCenter.autoConnectNotification(this, statusText, streaming)

    companion object {
        const val UPDATE_THROTTLE_MS = 500L

        fun startSafely(context: Context): Boolean {
            val intent = Intent(context, AutoConnectService::class.java)
            return try {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
                else context.startService(intent)
                true
            } catch (error: RuntimeException) {
                if (Build.VERSION.SDK_INT < 31 || error !is ForegroundServiceStartNotAllowedException) throw error
                RuntimeDiagnostics.event("AUTO_CONNECT_START_REJECTED", error.message.orEmpty(), true)
                if (!NetworkManager.isStreamingCurrent.value) {
                    NetworkManager.connectionStatus.value = context.getString(R.string.auto_connect_unavailable)
                }
                false
            }
        }
    }
}
