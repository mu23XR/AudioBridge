/*
 * Foreground host for the lab Shizuku audio bridge.
 *
 * Keeps the normal app process, CPU and Wi-Fi awake while the privileged
 * UserService performs capture and packetization in the shell process.
 */

package com.cuscus.wifiaudiostreaming.shizuku

import com.cuscus.wifiaudiostreaming.StreamingActionReceiver
import com.cuscus.wifiaudiostreaming.diagnostics.RuntimeDiagnostics

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.cuscus.wifiaudiostreaming.NetworkManager
import com.cuscus.wifiaudiostreaming.NotificationCenter
import com.cuscus.wifiaudiostreaming.RoleSelectionGate
import com.cuscus.wifiaudiostreaming.SenderVolumeSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ShizukuBridgeHostService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var statusJob: Job? = null
    private var bridgeWatchJob: Job? = null
    private var volumeSession: SenderVolumeSession? = null
    private var volumeSessionFailed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        RuntimeDiagnostics.event("SENDER_HOST_START", "startId=$startId redelivered=${intent == null}", true)
        RoleSelectionGate.initialize(applicationContext)
        NotificationCenter.ensureChannels(this)
        try {
            startForeground(
                NotificationCenter.ID_SERVER,
                NotificationCenter.shizukuBridgeNotification(
                    this,
                    NetworkManager.connectionStatus.value.ifBlank {
                        getString(com.cuscus.wifiaudiostreaming.R.string.shizuku_bridge_active)
                    },
                    NetworkManager.serverVolume.value
                )
            )
        } catch (failure: RuntimeException) {
            if (Build.VERSION.SDK_INT < 31 || failure !is android.app.ForegroundServiceStartNotAllowedException) throw failure
            RuntimeDiagnostics.event("SENDER_FOREGROUND_REJECTED", failure.javaClass.simpleName, true)
            ShizukuAudioBridgeManager.stop(this)
            NetworkManager.connectionStatus.value = getString(com.cuscus.wifiaudiostreaming.R.string.source_foreground_denied)
            stopSelf()
            return START_NOT_STICKY
        }
        // Honor a pending foreground-service promotion before rejecting a stale
        // role command. No capture/session resources are acquired on this path.
        if (StreamingActionReceiver.wasRecentlyTaskRemoved(this) || !RoleSelectionGate.isSenderSelected()) {
            stopSelf()
            return START_NOT_STICKY
        }

        acquireLocks()

        if (statusJob?.isActive != true) {
            statusJob = scope.launch {
                combine(
                    NetworkManager.connectionStatus,
                    NetworkManager.serverVolume
                ) { status, volume -> status to volume }
                    .distinctUntilChanged()
                    .conflate()
                    .collect { (status, volume) ->
                        updateVolumeSession(volume)
                        NotificationCenter.post(
                            this@ShizukuBridgeHostService,
                            NotificationCenter.ID_SERVER,
                            NotificationCenter.shizukuBridgeNotification(
                                this@ShizukuBridgeHostService,
                                volumeSessionStatus(status.ifBlank {
                                    getString(
                                        com.cuscus.wifiaudiostreaming.R.string.shizuku_bridge_active
                                    )
                                }),
                                volume
                            )
                        )
                        delay(120)
                    }
            }
        }

        // If Android recreated the normal process but Shizuku's daemon
        // UserService survived, reattach to it instead of starting a new capture.
        ShizukuAudioBridgeManager.rebindExisting(this)

        if (bridgeWatchJob?.isActive != true) {
            bridgeWatchJob = scope.launch {
                while (isActive) {
                    delay(1_000)
                    if (!ShizukuAudioBridgeManager.refreshRemoteState(this@ShizukuBridgeHostService)) {
                        stopSelf()
                        break
                    }
                }
            }
        }

        return START_STICKY
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        if (wakeLock?.isHeld != true) {
            wakeLock = runCatching {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "wfas:shizuku-bridge"
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }.getOrNull()
        }

        if (wifiLock?.isHeld != true) {
            wifiLock = runCatching {
                val wifiManager =
                    applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiManager.createWifiLock(mode, "wfas:shizuku-bridge-wifi").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }.getOrNull()
        }
    }

    private fun updateVolumeSession(volume: Float) {
        if (ShizukuAudioBridgeManager.state.value is ShizukuAudioBridgeManager.State.Running &&
            !ShizukuAudioBridgeManager.keepsLocalPlayback()) {
            if (volumeSession == null && !volumeSessionFailed) {
                try {
                    volumeSession = SenderVolumeSession(this)
                } catch (error: RuntimeException) {
                    volumeSessionFailed = true
                    RuntimeDiagnostics.event("SENDER_VOLUME_SESSION_FAILED", error.javaClass.simpleName, true)
                }
            }
            volumeSession?.update(volume)
        } else {
            volumeSession?.release()
            volumeSession = null
            volumeSessionFailed = false
        }
    }

    private fun volumeSessionStatus(status: String): String =
        if (volumeSessionFailed) status + "\n" + getString(com.cuscus.wifiaudiostreaming.R.string.sender_volume_keys_unavailable)
        else status

    override fun onTaskRemoved(rootIntent: Intent?) {
        RuntimeDiagnostics.event("SENDER_HOST_TASK_REMOVED", critical = true)
        StreamingActionReceiver.handleTaskRemoved(this)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        RuntimeDiagnostics.event("SENDER_HOST_DESTROY", "status=${NetworkManager.connectionStatus.value}", true)
        StreamingActionReceiver.handleStoppedWithTask(this)
        volumeSession?.release()
        volumeSession = null
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
        statusJob = null
        bridgeWatchJob = null
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        NotificationCenter.cancel(this, NotificationCenter.ID_SERVER)
        super.onDestroy()
    }
}
