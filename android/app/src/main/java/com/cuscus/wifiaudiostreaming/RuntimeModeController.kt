package com.cuscus.wifiaudiostreaming

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.cuscus.wifiaudiostreaming.diagnostics.RuntimeDiagnostics
import androidx.core.content.ContextCompat
import com.cuscus.wifiaudiostreaming.data.SettingsDataStore
import com.cuscus.wifiaudiostreaming.shizuku.ShizukuAudioBridgeManager
import com.cuscus.wifiaudiostreaming.scripting.ScriptExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single owner for AudioBridge's three runtime states:
 * OFF / SEND / RECEIVE.
 *
 * UI, notifications and restored foreground services all go through here so
 * switching modes cannot accidentally clear the receiver target or leave the
 * opposite role running in background.
 */
object RuntimeModeController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val switchingOff = AtomicBoolean(false)
    private var modeGeneration = 0L
    private var pendingModeJob: Job? = null

    private fun beginModeCommand(): Long {
        pendingModeJob?.cancel()
        pendingModeJob = null
        return ++modeGeneration
    }

    fun isSwitchingOff(): Boolean = switchingOff.get()

    fun selectOff(context: Context, keepControlNotification: Boolean = true) {
        RuntimeDiagnostics.event("MODE_OFF", "keepControl=$keepControlNotification", true)
        val app = context.applicationContext
        beginModeCommand()
        if (!switchingOff.compareAndSet(false, true)) return
        try {
            RoleSelectionGate.initialize(app)
            RoleSelectionGate.selectOff(app)

            // OFF means stop activity, but keep the remembered receiver target so
            // RECEIVE can reconnect to the same sender with one tap later.
            ClientSessionController.pauseKeepTarget(app)
            ShizukuAudioBridgeManager.stop(app)
            NetworkManager.stopStreaming(app)
            NetworkManager.stopListeningForDevices()

            app.stopService(Intent(app, AudioCaptureService::class.java))
            app.stopService(Intent(app, ClientService::class.java))
            app.stopService(Intent(app, AutoConnectService::class.java))
            app.stopService(Intent(app, SnapcastClientService::class.java))
            app.stopService(Intent(app, RtpClientService::class.java))

            VolumeOverlayController.dismiss()
            NotificationCenter.cancel(app, NotificationCenter.ID_SERVER)
            NotificationCenter.cancel(app, NotificationCenter.ID_CLIENT)
            NotificationCenter.cancel(app, NotificationCenter.ID_AUTO_CONNECT)
            if (keepControlNotification) {
                NotificationCenter.postModeControl(app)
            } else {
                NotificationCenter.cancel(app, NotificationCenter.ID_CONTROL)
            }
        } finally {
            switchingOff.set(false)
        }
    }

    fun selectReceiver(context: Context) {
        RuntimeDiagnostics.event("MODE_RECEIVE", critical = true)
        val app = context.applicationContext
        val generation = beginModeCommand()
        RoleSelectionGate.initialize(app)
        StreamingActionReceiver.clearTaskRemovedStop(app)

        if (!RoleSelectionGate.isReceiverSelected()) {
            // Stop only sender ownership. The receiver target is deliberately
            // preserved and will be restored by enterReceiverMode().
            ShizukuAudioBridgeManager.stop(app)
            NetworkManager.stopStreaming(app)
            app.stopService(Intent(app, AudioCaptureService::class.java))
            NotificationCenter.cancel(app, NotificationCenter.ID_SERVER)
        }

        RoleSelectionGate.selectReceiver(app)
        ClientSessionController.enterReceiverMode(app)
        NotificationCenter.postModeControl(app)

        pendingModeJob = scope.launch {
            val settings = SettingsDataStore(app).settingsFlow.first()
            if (generation != modeGeneration || !RoleSelectionGate.isReceiverSelected()) return@launch
            if (settings.autoConnectEnabled) {
                AutoConnectService.startSafely(app)
            }
        }
    }

    /**
     * Notification command semantics: RECEIVE always means "connect now".
     * If RECEIVE is already selected, rebuild the current transport instead of
     * treating the button as a passive state selector.
     */
    fun restartReceiver(context: Context) {
        RuntimeDiagnostics.event("MODE_RECEIVE_RESTART", critical = true)
        val app = context.applicationContext
        val generation = beginModeCommand()
        RoleSelectionGate.initialize(app)
        StreamingActionReceiver.clearTaskRemovedStop(app)

        if (!RoleSelectionGate.isReceiverSelected()) {
            ShizukuAudioBridgeManager.stop(app)
            NetworkManager.stopStreaming(app)
            app.stopService(Intent(app, AudioCaptureService::class.java))
            NotificationCenter.cancel(app, NotificationCenter.ID_SERVER)
        }

        RoleSelectionGate.selectReceiver(app)
        ClientSessionController.restartCurrent(app)
        NotificationCenter.postModeControl(app)

        pendingModeJob = scope.launch {
            val settings = SettingsDataStore(app).settingsFlow.first()
            if (generation != modeGeneration || !RoleSelectionGate.isReceiverSelected()) return@launch
            if (settings.autoConnectEnabled) {
                AutoConnectService.startSafely(app)
            }
        }
    }

    /**
     * Switch the UI/runtime role to SEND without automatically starting capture.
     * The normal app Start button still controls capture.
     */
    fun selectSenderIdle(context: Context) {
        RuntimeDiagnostics.event("MODE_SEND_IDLE", critical = true)
        val app = context.applicationContext
        beginModeCommand()
        RoleSelectionGate.initialize(app)
        StreamingActionReceiver.clearTaskRemovedStop(app)

        if (!RoleSelectionGate.isSenderSelected()) {
            ClientSessionController.pauseKeepTarget(app)
            NetworkManager.stopStreaming(app)
            app.stopService(Intent(app, ClientService::class.java))
            app.stopService(Intent(app, AutoConnectService::class.java))
            app.stopService(Intent(app, SnapcastClientService::class.java))
            app.stopService(Intent(app, RtpClientService::class.java))
            NotificationCenter.cancel(app, NotificationCenter.ID_CLIENT)
        }

        RoleSelectionGate.selectSender(app)
        NetworkManager.stopListeningForDevices()
        NotificationCenter.postModeControl(app)
    }

    /**
     * Notification shortcut: switch to SEND and immediately start the current
     * configured sender when it can run without opening MainActivity.
     *
     * Shizuku is the normal headless path. Legacy MediaProjection still requires
     * Android's permission UI and therefore cannot be started invisibly.
     */
    fun startSender(context: Context) {
        RuntimeDiagnostics.event("MODE_SEND_START", critical = true)
        val app = context.applicationContext
        selectSenderIdle(app)

        if (NetworkManager.isServerStreaming || ShizukuAudioBridgeManager.isActive()) {
            return
        }

        val generation = modeGeneration
        pendingModeJob = scope.launch { startConfiguredSender(app, generation) }
    }

    /**
     * Notification command semantics: SEND always means "start sending now".
     * If SEND is already active, tear down the current sender path first and
     * start it again with the current settings.
     */
    fun restartSender(context: Context) {
        val app = context.applicationContext
        selectSenderIdle(app)

        val generation = modeGeneration
        pendingModeJob = scope.launch {
            ShizukuAudioBridgeManager.stop(app)
            NetworkManager.stopStreaming(app)
            app.stopService(Intent(app, AudioCaptureService::class.java))
            NotificationCenter.cancel(app, NotificationCenter.ID_SERVER)

            // UserService removal is asynchronous on some ROMs. Give the old
            // privileged bridge a short window to disappear before rebinding.
            delay(250L)
            if (generation != modeGeneration || !RoleSelectionGate.isSenderSelected()) return@launch

            startConfiguredSender(app, generation)
        }
    }

    private suspend fun startConfiguredSender(app: Context, generation: Long) {
        val settings = SettingsDataStore(app).settingsFlow.first()
        if (generation != modeGeneration || !RoleSelectionGate.isSenderSelected()) return

        if (settings.streamInternal &&
            InternalAudioBackend.normalize(settings.internalAudioBackend) == InternalAudioBackend.SHIZUKU
        ) {
            val unsupportedProtocols =
                settings.lastMulticastMode ||
                    settings.rtpEnabled ||
                    settings.httpEnabled ||
                    settings.dlnaEnabled ||
                    settings.snapcastEnabled

            if (unsupportedProtocols) {
                NetworkManager.connectionStatus.value =
                    app.getString(R.string.shizuku_unicast_only)
                NotificationCenter.postModeControl(app)
                return
            }

            val securityOff =
                settings.securityMode.equals("OFF", ignoreCase = true) &&
                    !settings.encryptionEnabled &&
                    !settings.qrPairingEnabled

            if (!securityOff) {
                NetworkManager.connectionStatus.value =
                    app.getString(R.string.shizuku_security_off_required)
                NotificationCenter.postModeControl(app)
                return
            }

            ShizukuAudioBridgeManager.start(
                app,
                ShizukuAudioBridgeManager.Config(
                    port = settings.streamingPort,
                    sampleRate = settings.sampleRate,
                    channels = if (settings.channelConfig.equals("STEREO", ignoreCase = true)) 2 else 1,
                    packetBytes = settings.maxPayloadBytes,
                    keepPlayingOnDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
                    networkInterfaceName = settings.networkInterface,
                    persistAfterClient = true
                )
            )
            return
        }

        if (!settings.streamInternal && settings.streamMic) {
            if (
                ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                val command = com.cuscus.wifiaudiostreaming.scripting.ScriptCommand(
                    com.cuscus.wifiaudiostreaming.scripting.ScriptActionType.START_SERVER
                )
                ScriptExecutor.startServerMicOnly(
                    app,
                    ScriptExecutor.resolveServerParams(settings, command)
                )
            } else {
                NetworkManager.connectionStatus.value =
                    app.getString(R.string.mic_permission_denied)
            }
            NotificationCenter.postModeControl(app)
            return
        }

        if (settings.streamInternal) {
            // MediaProjection cannot be started headlessly by design.
            NetworkManager.connectionStatus.value =
                app.getString(R.string.capture_backend_legacy_desc)
        } else {
            NetworkManager.connectionStatus.value =
                app.getString(R.string.select_audio_source_first)
        }
        NotificationCenter.postModeControl(app)
    }
}
