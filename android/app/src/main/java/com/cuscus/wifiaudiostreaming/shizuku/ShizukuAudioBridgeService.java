/*
 * Experimental Shizuku audio bridge for the audio-bridge-lab branch.
 *
 * Copyright (c) 2026 Marco Morosi and contributors
 * Licensed under the EUPL, Version 1.2 or later versions approved by the EC.
 */

package com.cuscus.wifiaudiostreaming.shizuku;

import com.cuscus.wifiaudiostreaming.BuildConfig;
import com.cuscus.wifiaudiostreaming.PcmSilence;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.Keep;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shizuku UserService. This code is loaded from our APK, but the process itself
 * runs with Shizuku's privilege (normally Android shell uid 2000).
 *
 * Android 13+ uses the same AudioPolicy loopback+render direction as scrcpy's
 * playback capture: audio keeps rendering on the source device while a copy is
 * exposed through an AudioRecord sink. Android 11/12 keep REMOTE_SUBMIX only as
 * a compatibility fallback, where local playback may be redirected.
 */
public final class ShizukuAudioBridgeService extends IShizukuAudioBridge.Stub {

    private static final String TAG = "WFAS_SHIZUKU";
    private static final int SHELL_UID = 2000;
    private static final int PROTOCOL_VERSION = 2;
    private static final int HEADER_SIZE = 10;
    private static final byte MAGIC_0 = 0x57;
    private static final byte MAGIC_1 = 0x46;

    private final Context context;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile String status = "idle";
    private volatile Thread bridgeThread;
    private volatile DatagramSocket socket;
    private volatile AudioRecord recorder;
    private volatile float streamVolume = 1.0f;
    private volatile Object registeredAudioPolicy;
    private volatile Class<?> registeredAudioPolicyClass;
    private volatile AudioManager registeredAudioManager;
    private volatile String activeCaptureMode = "none";
    private volatile IBinder ownerToken;
    private volatile IBinder.DeathRecipient ownerDeathRecipient;
    private AudioManager captureVolumeManager;
    private boolean originalMediaMuted;
    private int originalMediaVolume;
    private boolean captureMuteChanged;
    private final java.util.ArrayDeque<String> diagnosticEvents = new java.util.ArrayDeque<>();
    private long droppedDiagnosticEvents;
    private static final class CaptureRoutePendingException extends IllegalStateException {
        CaptureRoutePendingException(String detail) { super(detail); }
    }

    private void trace(String event, String detail) {
        synchronized (diagnosticEvents) {
            if (diagnosticEvents.size() >= 64) {
                diagnosticEvents.removeFirst();
                droppedDiagnosticEvents++;
            }
            String safeDetail = detail == null ? "" : detail.replace('\n', ' ').replace('\r', ' ');
            if (safeDetail.length() > 1500) safeDetail = safeDetail.substring(0, 1500);
            diagnosticEvents.addLast("wall=" + System.currentTimeMillis() + " elapsed=" +
                    SystemClock.elapsedRealtime() + " pid=" + Process.myPid() +
                    " event=" + event + " " + safeDetail);
        }
    }

    @Override public String drainDiagnostics() {
        synchronized (diagnosticEvents) {
            StringBuilder result = new StringBuilder();
            if (droppedDiagnosticEvents > 0) {
                result.append("REMOTE_DIAGNOSTIC_DROP count=").append(droppedDiagnosticEvents).append('\n');
                droppedDiagnosticEvents = 0;
            }
            while (!diagnosticEvents.isEmpty()) result.append(diagnosticEvents.removeFirst()).append('\n');
            return result.toString();
        }
    }

    public ShizukuAudioBridgeService() {
        this.context = null;
        Log.i(TAG, "constructed without Context uid=" + Process.myUid());
    }

    @Keep
    public ShizukuAudioBridgeService(Context context) {
        this.context = context;
        Log.i(TAG, "constructed with Context uid=" + Process.myUid() + " context=" + context);
    }

    @Override
    public synchronized String startBridge(
            int port,
            int sampleRate,
            int channels,
            int packetBytes,
            boolean keepPlayingOnDevice,
            boolean persistAfterClient,
            IBinder ownerToken
    ) {
        enforceCaller();
        if (ownerToken == null || !ownerToken.isBinderAlive()) {
            return "error: owner process unavailable";
        }
        trace("CAPTURE_IDENTITY", "processUid=" + Process.myUid() + " callerUid=" + Binder.getCallingUid());
        // AudioPolicy performs a local checkCallingOrSelfPermission. A shell
        // process inside an app-originated Binder call otherwise checks the
        // unprivileged caller, even with a shell-attributed Context.
        long identity = Binder.clearCallingIdentity();
        try {
            return startBridgeOwned(port, sampleRate, channels, packetBytes,
                    keepPlayingOnDevice, persistAfterClient, ownerToken);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void enforceCaller() {
        int caller = Binder.getCallingUid();
        if (caller == Process.myUid() || caller == 0) return;
        if (context == null) throw new SecurityException("Shizuku application Context unavailable");
        try {
            String[] packages = context.getPackageManager().getPackagesForUid(caller);
            if (packages != null) for (String name : packages) {
                if (BuildConfig.APPLICATION_ID.equals(name)) return;
            }
            throw new SecurityException("Unauthorized bridge caller");
        } catch (RuntimeException e) {
            throw new SecurityException("Bridge owner package unavailable", e);
        }
    }

    private String startBridgeOwned(int port, int sampleRate, int channels, int packetBytes,
            boolean keepPlayingOnDevice, boolean persistAfterClient, IBinder ownerToken) {
        stopBridgeInternal();

        trace("CAPTURE_START", "port=" + port + " rate=" + sampleRate + " channels=" + channels +
                "payload=" + packetBytes + " local=" + keepPlayingOnDevice + " persist=" + persistAfterClient);

        int uid = Process.myUid();
        if (uid != SHELL_UID && uid != 0) {
            status = "error: UserService uid=" + uid + " (expected shell 2000)";
            return status;
        }
        if (port < 1024 || port > 65535) {
            status = "error: invalid port " + port;
            return status;
        }
        if (channels != 1 && channels != 2) {
            status = "error: channels must be 1 or 2";
            return status;
        }
        if (sampleRate < 8000 || sampleRate > 192000) {
            status = "error: invalid sample rate " + sampleRate;
            return status;
        }

        try {
            attachOwner(ownerToken);
        } catch (Throwable t) {
            status = "error: owner process unavailable: " + String.valueOf(t.getMessage());
            Log.e(TAG, "could not attach owner process token", t);
            return status;
        }

        int frameSize = channels * 2;
        int safePacketBytes = Math.max(128, Math.min(packetBytes, 1390));
        safePacketBytes -= safePacketBytes % frameSize;
        if (safePacketBytes < frameSize) safePacketBytes = frameSize;

        String captureMode;
        try {
            Context volumeContext = createSystemShellAudioContext(context);
            AudioManager initialVolumeManager = AudioManager.class.getConstructor(Context.class)
                    .newInstance(volumeContext);
            boolean initialMuted = initialVolumeManager.isStreamMute(AudioManager.STREAM_MUSIC);
            int initialVolume = initialVolumeManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            if (Build.VERSION.SDK_INT >= 33) {
                if (context == null) {
                    throw new IllegalStateException("Shizuku v13+ Context is required for AudioPolicy");
                }
                try {
                    recorder = createPlaybackCapture(sampleRate, channels, keepPlayingOnDevice);
                    captureMode = activeCaptureMode;
                } catch (Throwable policyFailure) {
                    Log.w(TAG, "AudioPolicy playback capture failed, trying REMOTE_SUBMIX fallback", policyFailure);
                    releaseCapture();
                    try {
                        recorder = createRemoteSubmixCapture(sampleRate, channels, safePacketBytes);
                        captureMode = "REMOTE_SUBMIX fallback";
                        activeCaptureMode = captureMode;
                    } catch (Throwable submixFailure) {
                        throw new IllegalStateException(
                                "AudioPolicy failed [" +
                                        String.valueOf(policyFailure.getMessage()) +
                                        "]; REMOTE_SUBMIX failed [" +
                                        submixFailure.getClass().getSimpleName() + ": " +
                                        String.valueOf(submixFailure.getMessage()) +
                                        "]",
                                submixFailure
                        );
                    }
                }
            } else if (Build.VERSION.SDK_INT >= 30) {
                recorder = createRemoteSubmixCapture(sampleRate, channels, safePacketBytes);
                captureMode = "REMOTE_SUBMIX compatibility";
                activeCaptureMode = captureMode;
            } else {
                throw new UnsupportedOperationException("system audio requires Android 11+");
            }

            if (recorder == null || recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("AudioRecord is not initialized");
            }
            if (captureMode.startsWith("REMOTE_SUBMIX")) {
                captureVolumeManager = initialVolumeManager;
                originalMediaMuted = initialMuted;
                originalMediaVolume = initialVolume;
            }
            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IllegalStateException("AudioRecord did not enter RECORDSTATE_RECORDING");
            }
            synchronizeCaptureVolume();

        } catch (Throwable t) {
            releaseCapture();
            detachOwner();
            status = "error: " + (t instanceof CaptureRoutePendingException ? "kind=ROUTE_NOT_READY; " : "") +
                    "build=" + BuildConfig.VERSION_CODE + " " + t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage());
            Log.e(TAG, "capture start failed", t);
            trace("CAPTURE_START_FAILED", t.getClass().getSimpleName() + ": " + t.getMessage());
            return status;
        }

        running.set(true);
        final int finalPacketBytes = safePacketBytes;
        bridgeThread = new Thread(
                () -> runServer(port, sampleRate, channels, finalPacketBytes, persistAfterClient),
                "wfas-shizuku-bridge"
        );
        bridgeThread.setDaemon(true);
        bridgeThread.start();

        status = "running build=" + BuildConfig.VERSION_CODE + " uid=" + uid +
                " mode=" + captureMode +
                " port=" + port +
                " " + sampleRate + "Hz/" + channels + "ch" +
                " packet=" + finalPacketBytes + "B" +
                " persist=" + persistAfterClient;
        Log.i(TAG, status);
        return status;
    }

    @Override
    public synchronized void stopBridge() {
        enforceCaller();
        long identity = Binder.clearCallingIdentity();
        try {
            trace("CAPTURE_STOP_REQUEST", status);
            stopBridgeInternal();
        } finally { Binder.restoreCallingIdentity(identity); }
    }

    @Override
    public String getStatus() {
        return status;
    }

    @Override
    public int getBuildVersion() {
        return BuildConfig.VERSION_CODE;
    }

    @Override
    public void setVolume(float volume) {
        enforceCaller();
        if (Float.isNaN(volume) || Float.isInfinite(volume)) {
            return;
        }
        streamVolume = Math.max(0.0f, Math.min(volume, 2.0f));
    }


    @Override
    public void destroy() {
        enforceCaller();
        Binder.clearCallingIdentity();
        stopBridgeInternal();
        Log.i(TAG, "destroy");
        System.exit(0);
    }

    private synchronized void stopBridgeInternal() {
        running.set(false);

        DatagramSocket s = socket;
        socket = null;
        if (s != null) {
            try {
                s.close();
            } catch (Throwable ignored) {
            }
        }

        Thread t = bridgeThread;
        bridgeThread = null;
        if (t != null && t != Thread.currentThread()) {
            t.interrupt();
            try {
                t.join(600);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        releaseCapture();
        detachOwner();
        status = "idle";
    }

    private synchronized void attachOwner(IBinder token) throws RemoteException {
        detachOwner();
        if (token == null) {
            throw new IllegalArgumentException("ownerToken is null");
        }

        IBinder.DeathRecipient recipient = () -> {
            Log.w(TAG, "owner app process died; stopping Shizuku bridge");
            Thread cleanup = new Thread(() -> {
                stopBridgeInternal();
                Log.w(TAG, "owner-death cleanup complete; exiting UserService");
                System.exit(0);
            }, "wfas-owner-death-cleanup");
            cleanup.setDaemon(false);
            cleanup.start();
        };

        token.linkToDeath(recipient, 0);
        ownerToken = token;
        ownerDeathRecipient = recipient;
    }

    private synchronized void detachOwner() {
        IBinder token = ownerToken;
        IBinder.DeathRecipient recipient = ownerDeathRecipient;
        ownerToken = null;
        ownerDeathRecipient = null;
        if (token != null && recipient != null) {
            try {
                token.unlinkToDeath(recipient, 0);
            } catch (Throwable ignored) {
            }
        }
    }

    private void runServer(
            int port,
            int sampleRate,
            int channels,
            int packetBytes,
            boolean persistAfterClient
    ) {
        DatagramSocket localSocket = null;
        try {
            localSocket = new DatagramSocket(null);
            localSocket.setReuseAddress(true);
            localSocket.setReceiveBufferSize(1 << 20);
            localSocket.setSendBufferSize(1 << 20);
            localSocket.setSoTimeout(1000);
            localSocket.bind(new InetSocketAddress(port));
            socket = localSocket;
            Log.i(TAG, "listening on UDP :" + port);

            while (running.get()) {
                InetSocketAddress client = waitForClient(localSocket);
                if (client == null || !running.get()) break;
                Log.i(TAG, "client connected " + client);
                trace("SENDER_CLIENT_CONNECTED", client.toString());
                boolean cleanClientBye =
                        runSession(localSocket, client, sampleRate, channels, packetBytes);
                if (running.get() && cleanClientBye && !persistAfterClient) {
                    Log.i(TAG, "client disconnected cleanly; persistence disabled, stopping bridge");
                    status = "idle";
                    trace("SENDER_STOP", "reason=CLIENT_BYE_PERSIST_DISABLED");
                    running.set(false);
                    break;
                }
                if (running.get()) {
                    Log.i(TAG, cleanClientBye
                            ? "client disconnected cleanly; waiting for next client"
                            : "client heartbeat lost; keeping server alive for reconnect");
                }
            }
        } catch (SocketException e) {
            if (running.get()) {
                status = "error: UDP " + e.getMessage();
                trace("SENDER_FAILED", "reason=UDP " + e.getMessage());
                Log.e(TAG, "UDP bridge failed", e);
            }
        } catch (Throwable t) {
            if (running.get()) {
                status = "error: " + t.getClass().getSimpleName() + ": " + t.getMessage();
                trace("SENDER_FAILED", t.getClass().getSimpleName() + ": " + t.getMessage());
                Log.e(TAG, "bridge failed", t);
            }
        } finally {
            running.set(false);
            if (localSocket != null) {
                try {
                    localSocket.close();
                } catch (Throwable ignored) {
                }
            }
            if (socket == localSocket) socket = null;
            releaseCapture();
        }
    }

    private InetSocketAddress waitForClient(DatagramSocket s) throws Exception {
        byte[] buf = new byte[2048];
        while (running.get()) {
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            try {
                s.receive(packet);
            } catch (SocketTimeoutException ignored) {
                continue;
            }

            InetSocketAddress remote = new InetSocketAddress(packet.getAddress(), packet.getPort());
            String text = new String(packet.getData(), packet.getOffset(), packet.getLength()).trim();

            if ("MODE_PROBE".equals(text)) {
                sendText(s, remote, "UNICAST");
            } else if (text.startsWith("HELLO_FROM_CLIENT")) {
                int version = tokenInt(text, "v", 0);
                if (version != PROTOCOL_VERSION) {
                    sendText(s, remote, "WFAS_INCOMPATIBLE;v=" + PROTOCOL_VERSION);
                    continue;
                }
                sendText(s, remote, "HELLO_ACK;v=" + PROTOCOL_VERSION);
                return remote;
            }
        }
        return null;
    }

    private boolean runSession(
            DatagramSocket s,
            InetSocketAddress initialClient,
            int sampleRate,
            int channels,
            int packetBytes
    ) throws Exception {
        final int frameSize = channels * 2;
        final AtomicBoolean sessionAlive = new AtomicBoolean(true);
        final AtomicBoolean explicitClientBye = new AtomicBoolean(false);
        final AtomicBoolean pongCapable = new AtomicBoolean(false);
        final AtomicLong lastClientActivityAt = new AtomicLong(System.currentTimeMillis());
        final AtomicReference<InetSocketAddress> client = new AtomicReference<>(initialClient);
        final InetAddress clientIp = initialClient.getAddress();

        Thread controlThread = new Thread(() -> {
            byte[] buf = new byte[2048];
            while (running.get() && sessionAlive.get()) {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                try {
                    s.receive(packet);
                } catch (SocketTimeoutException ignored) {
                    continue;
                } catch (Throwable t) {
                    if (running.get() && sessionAlive.get()) {
                        Log.w(TAG, "control receive failed: " + t.getMessage());
                    }
                    break;
                }

                InetSocketAddress remote = new InetSocketAddress(packet.getAddress(), packet.getPort());
                String text = new String(packet.getData(), packet.getOffset(), packet.getLength()).trim();

                try {
                    if ("MODE_PROBE".equals(text)) {
                        sendText(s, remote, "UNICAST");
                    } else if (text.startsWith("HELLO_FROM_CLIENT")) {
                        int version = tokenInt(text, "v", 0);
                        if (version != PROTOCOL_VERSION) {
                            sendText(s, remote, "WFAS_INCOMPATIBLE;v=" + PROTOCOL_VERSION);
                        } else if (remote.getAddress().equals(clientIp)) {
                            client.set(remote);
                            lastClientActivityAt.set(System.currentTimeMillis());
                            sendText(s, remote, "HELLO_ACK;v=" + PROTOCOL_VERSION);
                            Log.i(TAG, "client endpoint refreshed " + remote);
                        } else {
                            sendText(s, remote, "WFAS_BUSY");
                        }
                    } else if ("PONG".equals(text) && remote.getAddress().equals(clientIp)) {
                        client.set(remote);
                        pongCapable.set(true);
                        lastClientActivityAt.set(System.currentTimeMillis());
                    } else if ("CLIENT_BYE".equals(text) && remote.getAddress().equals(clientIp)) {
                        trace("SENDER_CLIENT_BYE", remote.toString());
                        lastClientActivityAt.set(System.currentTimeMillis());
                        explicitClientBye.set(true);
                        sessionAlive.set(false);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "control reply failed: " + t.getMessage());
                }
            }
        }, "wfas-shizuku-control");
        controlThread.setDaemon(true);
        controlThread.start();

        Thread pingThread = new Thread(() -> {
            while (running.get() && sessionAlive.get()) {
                try {
                    Thread.sleep(1000);
                    sendText(s, client.get(), "PING");
                    if (pongCapable.get() &&
                            System.currentTimeMillis() - lastClientActivityAt.get() > 30_000L) {
                        Log.w(TAG, "client heartbeat timed out; releasing session");
                        trace("SENDER_CLIENT_TIMEOUT", "lastPongAgeMs=" + (System.currentTimeMillis() - lastClientActivityAt.get()));
                        sessionAlive.set(false);
                        break;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Throwable t) {
                    if (running.get()) Log.w(TAG, "PING failed: " + t.getMessage());
                }
            }
        }, "wfas-shizuku-ping");
        pingThread.setDaemon(true);
        pingThread.start();

        byte[] readBuffer = new byte[Math.max(packetBytes, 4096)];
        int seq = 0;
        long samplePosition = 0;
        long packets = 0;
        long lastSilenceKeepaliveAt = 0L;
        long lastDiagnosticAt = SystemClock.elapsedRealtime();
        long lastReadAt = lastDiagnosticAt;
        long maxReadGap = 0;
        long capturedFrames = 0;
        long zeroReads = 0;
        long clippedSamples = 0;

        try {
            while (running.get() && sessionAlive.get()) {
                AudioRecord r = recorder;
                if (r == null) break;

                int read = r.read(
                        readBuffer,
                        0,
                        readBuffer.length,
                        AudioRecord.READ_NON_BLOCKING
                );
                if (read < 0) {
                    trace("CAPTURE_READ_ERROR", "code=" + read + " mode=" + activeCaptureMode);
                    throw new IllegalStateException("AudioRecord.read failed: " + read);
                }
                int alignedRead = read - (read % frameSize);
                long readAt = SystemClock.elapsedRealtime();
                maxReadGap = Math.max(maxReadGap, readAt - lastReadAt);
                lastReadAt = readAt;
                capturedFrames += alignedRead / frameSize;
                clippedSamples += applyPcmGainInPlace(readBuffer, alignedRead, streamVolume);
                if (readAt - lastDiagnosticAt >= 10000) {
                    trace("CAPTURE_STATS", "frames=" + capturedFrames + " packets=" + packets +
                            "zeroReads=" + zeroReads + " maxReadGapMs=" + maxReadGap +
                            "clippedSamples=" + clippedSamples + " gain=" + streamVolume +
                            "mode=" + activeCaptureMode);
                    maxReadGap = 0;
                    lastDiagnosticAt = readAt;
                }
                if (alignedRead == 0 || PcmSilence.isZero(readBuffer, 0, alignedRead)) {
                    zeroReads++;
                    // AudioRecord may return full buffers of zero PCM indefinitely.
                    // Sending those to a media AudioTrack triggers OEM zero-audio
                    // suspension. Keep the session alive without playing fake PCM.
                    samplePosition += alignedRead / frameSize;
                    long now = System.currentTimeMillis();
                    if (now - lastSilenceKeepaliveAt >= 1_000L) {
                        byte[] keepalive = new byte[HEADER_SIZE];
                        keepalive[0] = MAGIC_0;
                        keepalive[1] = MAGIC_1;
                        keepalive[2] = (byte) PROTOCOL_VERSION;
                        keepalive[3] = 0x01; // silence/liveness, no PCM payload
                        keepalive[4] = (byte) ((seq >>> 8) & 0xFF);
                        keepalive[5] = (byte) (seq & 0xFF);
                        ByteBuffer.wrap(keepalive, 6, 4)
                                .order(ByteOrder.BIG_ENDIAN)
                                .putInt((int) (samplePosition & 0xFFFFFFFFL));

                        InetSocketAddress target = client.get();
                        s.send(new DatagramPacket(
                                keepalive,
                                keepalive.length,
                                target.getAddress(),
                                target.getPort()
                        ));
                        seq = (seq + 1) & 0xFFFF;
                        packets++;
                        lastSilenceKeepaliveAt = now;
                    }
                    try {
                        Thread.sleep(2);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                }

                int offset = 0;
                while (offset < alignedRead && running.get() && sessionAlive.get()) {
                    int chunk = Math.min(packetBytes, alignedRead - offset);
                    chunk -= chunk % frameSize;
                    if (chunk <= 0) break;

                    byte[] out = new byte[HEADER_SIZE + chunk];
                    out[0] = MAGIC_0;
                    out[1] = MAGIC_1;
                    out[2] = (byte) PROTOCOL_VERSION;
                    out[3] = 0;
                    out[4] = (byte) ((seq >>> 8) & 0xFF);
                    out[5] = (byte) (seq & 0xFF);
                    ByteBuffer.wrap(out, 6, 4)
                            .order(ByteOrder.BIG_ENDIAN)
                            .putInt((int) (samplePosition & 0xFFFFFFFFL));
                    System.arraycopy(readBuffer, offset, out, HEADER_SIZE, chunk);

                    InetSocketAddress target = client.get();
                    s.send(new DatagramPacket(out, out.length, target.getAddress(), target.getPort()));

                    seq = (seq + 1) & 0xFFFF;
                    samplePosition += chunk / frameSize;
                    offset += chunk;
                    packets++;

                    if (packets == 1 || packets % 2000 == 0) {
                        Log.i(TAG, "sent packets=" + packets + " seq=" + seq + " target=" + target);
                    }
                }
            }
        } finally {
            sessionAlive.set(false);
            controlThread.interrupt();
            pingThread.interrupt();
            try {
                controlThread.join(400);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            try {
                pingThread.join(400);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        return explicitClientBye.get();
    }

    @SuppressLint({"PrivateApi", "WrongConstant", "MissingPermission"})
    private AudioRecord createPlaybackCapture(
            int sampleRate,
            int channels,
            boolean keepPlayingOnDevice
    ) throws Exception {
        StringBuilder failures = new StringBuilder();

        // 1) Closest to the AudioPolicy example that scrcpy adopted:
        // system Context + shell attribution + MEDIA-only rule.
        try {
            AudioRecord record = createAudioPolicyCapture(
                    sampleRate,
                    channels,
                    keepPlayingOnDevice,
                    new int[] { AudioAttributes.USAGE_MEDIA },
                    true,
                    true
            );
            activeCaptureMode = keepPlayingOnDevice
                    ? "AudioPolicy system-shell LOOP_BACK_RENDER"
                    : "AudioPolicy system-shell LOOP_BACK";
            return record;
        } catch (Throwable t) {
            appendFailure(failures, "system-shell", t);
            Log.w(TAG, "system-shell AudioPolicy capture failed", t);
        }

        // 2) Keep the broader MEDIA/GAME/UNKNOWN matching used by WFAS, but still
        // use system Context. Some OEMs are happier with the broad mix.
        try {
            AudioRecord record = createAudioPolicyCapture(
                    sampleRate,
                    channels,
                    keepPlayingOnDevice,
                    new int[] {
                            AudioAttributes.USAGE_MEDIA,
                            AudioAttributes.USAGE_GAME,
                            AudioAttributes.USAGE_UNKNOWN
                    },
                    true,
                    true
            );
            activeCaptureMode = keepPlayingOnDevice
                    ? "AudioPolicy system-shell broad LOOP_BACK_RENDER"
                    : "AudioPolicy system-shell broad LOOP_BACK";
            return record;
        } catch (Throwable t) {
            appendFailure(failures, "system-shell-broad", t);
            Log.w(TAG, "system-shell broad AudioPolicy capture failed", t);
        }

        // 3) Last AudioPolicy attempt: shell-attributed app Context. Retained for
        // OEMs where ActivityThread system Context is restricted.
        try {
            AudioRecord record = createAudioPolicyCapture(
                    sampleRate,
                    channels,
                    keepPlayingOnDevice,
                    new int[] { AudioAttributes.USAGE_MEDIA },
                    false,
                    false
            );
            activeCaptureMode = keepPlayingOnDevice
                    ? "AudioPolicy app-shell LOOP_BACK_RENDER"
                    : "AudioPolicy app-shell LOOP_BACK";
            return record;
        } catch (Throwable t) {
            appendFailure(failures, "app-shell", t);
            Log.w(TAG, "app-shell AudioPolicy capture failed", t);
        }

        throw new IllegalStateException(
                "all AudioPolicy strategies failed: " + failures
        );
    }

    private void appendFailure(StringBuilder out, String stage, Throwable t) {
        trace("CAPTURE_STRATEGY_FAILED", stage + "=" + t.getClass().getSimpleName() + ":" + t.getMessage());
        if (out.length() > 0) out.append(" | ");
        out.append(stage)
                .append("=")
                .append(t.getClass().getSimpleName())
                .append(":")
                .append(String.valueOf(t.getMessage()));
    }

    @SuppressLint({"PrivateApi", "WrongConstant", "MissingPermission"})
    private AudioRecord createAudioPolicyCapture(
            int sampleRate,
            int channels,
            boolean keepPlayingOnDevice,
            int[] usages,
            boolean enableVoiceCaptureBeforeBuild,
            boolean preferSystemContext
    ) throws Exception {
        Class<?> mixingRuleClass = Class.forName("android.media.audiopolicy.AudioMixingRule");
        Class<?> mixingRuleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule$Builder");

        Object mixingRuleBuilder = mixingRuleBuilderClass.getConstructor().newInstance();

        int mixRolePlayers = mixingRuleClass.getField("MIX_ROLE_PLAYERS").getInt(null);
        mixingRuleBuilderClass
                .getMethod("setTargetMixRole", int.class)
                .invoke(mixingRuleBuilder, mixRolePlayers);

        if (enableVoiceCaptureBeforeBuild) {
            try {
                mixingRuleBuilderClass
                        .getMethod("allowPrivilegedPlaybackCapture", boolean.class)
                        .invoke(mixingRuleBuilder, false);
            } catch (Throwable ignored) {
                // Hidden API availability differs across releases/OEMs.
            }
            try {
                mixingRuleBuilderClass
                        .getMethod("voiceCommunicationCaptureAllowed", boolean.class)
                        .invoke(mixingRuleBuilder, true);
            } catch (Throwable ignored) {
                // Optional. MEDIA/GAME capture must not depend on this OEM-specific path.
            }
        }

        int ruleMatchUsage = mixingRuleClass.getField("RULE_MATCH_ATTRIBUTE_USAGE").getInt(null);
        Method addMixRule = mixingRuleBuilderClass.getMethod("addMixRule", int.class, Object.class);
        for (int usage : usages) {
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(usage)
                    .build();
            addMixRule.invoke(mixingRuleBuilder, ruleMatchUsage, attributes);
        }

        Object mixingRule = mixingRuleBuilderClass.getMethod("build").invoke(mixingRuleBuilder);

        // scrcpy invokes this after build(). Keep the MEDIA-only retry as close as
        // possible to upstream behavior without letting this optional method fail.
        if (!enableVoiceCaptureBeforeBuild) {
            try {
                mixingRuleBuilderClass
                        .getMethod("voiceCommunicationCaptureAllowed", boolean.class)
                        .invoke(mixingRuleBuilder, true);
            } catch (Throwable ignored) {
            }
        }

        Class<?> audioMixClass = Class.forName("android.media.audiopolicy.AudioMix");
        Class<?> audioMixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix$Builder");
        Object audioMixBuilder = audioMixBuilderClass
                .getConstructor(mixingRuleClass)
                .newInstance(mixingRule);

        int channelMask = channels == 2
                ? AudioFormat.CHANNEL_OUT_STEREO
                : AudioFormat.CHANNEL_OUT_MONO;
        AudioFormat audioFormat = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(channelMask)
                .build();

        audioMixBuilderClass
                .getMethod("setFormat", AudioFormat.class)
                .invoke(audioMixBuilder, audioFormat);

        String routeFlagName = keepPlayingOnDevice
                ? "ROUTE_FLAG_LOOP_BACK_RENDER"
                : "ROUTE_FLAG_LOOP_BACK";
        int routeFlags = audioMixClass.getField(routeFlagName).getInt(null);
        audioMixBuilderClass
                .getMethod("setRouteFlags", int.class)
                .invoke(audioMixBuilder, routeFlags);

        Object audioMix = audioMixBuilderClass.getMethod("build").invoke(audioMixBuilder);

        Class<?> audioPolicyClass = Class.forName("android.media.audiopolicy.AudioPolicy");
        Class<?> audioPolicyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy$Builder");

        Context policyContext = preferSystemContext
                ? createSystemShellAudioContext(context)
                : createShellAudioContext(context);
        Object audioPolicyBuilder = audioPolicyBuilderClass
                .getConstructor(Context.class)
                .newInstance(policyContext);
        audioPolicyBuilderClass
                .getMethod("addMix", audioMixClass)
                .invoke(audioPolicyBuilder, audioMix);
        Object audioPolicy = audioPolicyBuilderClass.getMethod("build").invoke(audioPolicyBuilder);

        int result;
        AudioManager policyAudioManager = policyContext.getSystemService(AudioManager.class);
        if (preferSystemContext) {
            try {
                Method register = AudioManager.class.getMethod(
                        "registerAudioPolicy",
                        audioPolicyClass
                );
                result = (Integer) register.invoke(policyAudioManager, audioPolicy);
            } catch (Throwable instanceFailure) {
                Log.w(TAG, "instance registerAudioPolicy unavailable; using static", instanceFailure);
                Method register = AudioManager.class
                        .getDeclaredMethod("registerAudioPolicyStatic", audioPolicyClass);
                register.setAccessible(true);
                result = (Integer) register.invoke(null, audioPolicy);
            }
        } else {
            Method register = AudioManager.class
                    .getDeclaredMethod("registerAudioPolicyStatic", audioPolicyClass);
            register.setAccessible(true);
            result = (Integer) register.invoke(null, audioPolicy);
        }
        if (result != 0) {
            throw new IllegalStateException("registerAudioPolicy returned " + result);
        }

        AudioRecord resultRecord = null;
        try {
            resultRecord = createAttributedPolicySink(audioMix, audioMixClass,
                    policyContext, sampleRate, channels);
            if (resultRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException(
                        "Attributed policy sink returned uninitialized AudioRecord state=" +
                                resultRecord.getState()
                );
            }

            registeredAudioPolicy = audioPolicy;
            registeredAudioPolicyClass = audioPolicyClass;
            registeredAudioManager = policyAudioManager;
            return resultRecord;
        } catch (Throwable t) {
            if (resultRecord != null) {
                try {
                    resultRecord.release();
                } catch (Throwable ignored) {
                }
            }
            unregisterPolicy(policyAudioManager, audioPolicy, audioPolicyClass);
            throw t;
        }
    }

    @TargetApi(31)
    @SuppressLint({"PrivateApi", "MissingPermission"})
    private AudioRecord createAttributedPolicySink(Object mix, Class<?> mixClass,
            Context policyContext, int sampleRate, int channels) throws Exception {
        String registration = (String) mixClass.getMethod("getRegistration").invoke(mix);
        if (registration == null || registration.isEmpty()) {
            throw new IllegalStateException("AudioPolicy mix has no registered capture address");
        }
        // AOSP createAudioRecordSink constructs the record without its policy
        // Context. In a Shizuku-loaded app this loses shell attribution even
        // after clearing Binder identity. Pass it to the actual record builder.
        AudioAttributes.Builder attributes = new AudioAttributes.Builder();
        AudioAttributes.Builder.class.getMethod("setInternalCapturePreset", int.class)
                .invoke(attributes, MediaRecorder.AudioSource.REMOTE_SUBMIX);
        Method addTag = AudioAttributes.Builder.class.getMethod("addTag", String.class);
        addTag.invoke(attributes, "addr=" + registration);
        addTag.invoke(attributes, (String) AudioRecord.class.getField("SUBMIX_FIXED_VOLUME").get(null));
        int inputMask = channels == 2 ? AudioFormat.CHANNEL_IN_STEREO : AudioFormat.CHANNEL_IN_MONO;
        int minBuffer = AudioRecord.getMinBufferSize(sampleRate, inputMask, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuffer <= 0) throw new IllegalStateException("Unsupported policy capture format: " + minBuffer);
        AudioRecord.Builder builder = new AudioRecord.Builder()
                .setContext(policyContext)
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(inputMask).build())
                .setBufferSizeInBytes(minBuffer);
        AudioRecord.Builder.class.getMethod("setAudioAttributes", AudioAttributes.class)
                .invoke(builder, attributes.build());
        trace("CAPTURE_ATTRIBUTION", "processUid=" + Process.myUid() +
                " recordUid=" + policyContext.getAttributionSource().getUid() +
                " recordPackage=" + policyContext.getAttributionSource().getPackageName());
        return builder.build();
    }

    @SuppressLint({"WrongConstant", "MissingPermission"})
    private AudioRecord createRemoteSubmixCapture(
            int sampleRate,
            int channels,
            int packetBytes
    ) {
        int channelMask = channels == 2
                ? AudioFormat.CHANNEL_IN_STEREO
                : AudioFormat.CHANNEL_IN_MONO;
        int minBuffer = AudioRecord.getMinBufferSize(
                sampleRate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT
        );
        if (minBuffer <= 0) {
            throw new IllegalStateException("unsupported AudioRecord format: " + minBuffer);
        }

        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(channelMask)
                .build();

        AudioRecord.Builder builder = new AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.REMOTE_SUBMIX)
                .setAudioFormat(format)
                .setBufferSizeInBytes(Math.max(minBuffer, packetBytes * 8));

        if (Build.VERSION.SDK_INT >= 31 && context != null) {
            builder.setContext(createShellAudioContext(context));
        }

        AudioRecord record = builder.build();
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            try {
                record.release();
            } catch (Throwable ignored) {
            }
            throw new IllegalStateException("REMOTE_SUBMIX AudioRecord is not initialized");
        }
        return record;
    }

    @SuppressLint({"PrivateApi", "DiscouragedPrivateApi"})
    private static Context createSystemShellAudioContext(Context fallback) {
        try {
            // Xiaomi/MIUI/HyperOS-specific framework hook seen in the same
            // AudioPolicy path used by scrcpy-related implementations.
            try {
                Class<?> themeManagerStub = Class.forName("android.content.res.ThemeManagerStub");
                Field resourceField = themeManagerStub.getDeclaredField("sResource");
                resourceField.setAccessible(true);
                resourceField.set(null, null);
            } catch (Throwable ignored) {
            }

            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Object activityThread = null;

            try {
                Method current = activityThreadClass.getDeclaredMethod("currentActivityThread");
                current.setAccessible(true);
                activityThread = current.invoke(null);
            } catch (Throwable ignored) {
            }

            if (activityThread == null) {
                Method systemMain = activityThreadClass.getDeclaredMethod("systemMain");
                systemMain.setAccessible(true);
                activityThread = systemMain.invoke(null);
            }

            Method getSystemContext = activityThreadClass.getDeclaredMethod("getSystemContext");
            getSystemContext.setAccessible(true);
            Context systemContext = (Context) getSystemContext.invoke(activityThread);

            if (systemContext != null) {
                Log.i(
                        TAG,
                        "using system Context for AudioPolicy basePackage=" +
                                systemContext.getPackageName() +
                                " uid=" + Process.myUid()
                );
                return createShellAudioContext(systemContext);
            }
        } catch (Throwable t) {
            Log.w(TAG, "could not create system shell Context; using UserService Context", t);
        }
        return createShellAudioContext(fallback);
    }

    private static Context createShellAudioContext(Context base) {
        if (base == null || Build.VERSION.SDK_INT < 31) {
            return base;
        }
        return new ShellAudioContext(base);
    }

    @TargetApi(31)
    private static final class ShellAudioContext extends ContextWrapper {
        ShellAudioContext(Context base) {
            super(base);
        }

        @Override
        public String getPackageName() {
            return "com.android.shell";
        }

        @Override
        public String getOpPackageName() {
            return "com.android.shell";
        }

        @Override
        public AttributionSource getAttributionSource() {
            return new AttributionSource.Builder(SHELL_UID)
                    .setPackageName("com.android.shell")
                    .build();
        }

        @Override
        public Context getApplicationContext() {
            return this;
        }

        @Override
        public Context createPackageContext(String packageName, int flags) {
            return this;
        }
    }

    private static long applyPcmGainInPlace(byte[] pcm, int bytes, float gain) {
        if (pcm == null || bytes < 2 || Math.abs(gain - 1.0f) < 0.0001f) {
            return 0;
        }
        int limit = bytes - (bytes % 2);
        long clipped = 0;
        for (int i = 0; i < limit; i += 2) {
            int sample = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
            int scaled = Math.round(sample * gain);
            if (scaled > Short.MAX_VALUE || scaled < Short.MIN_VALUE) clipped++;
            if (scaled > Short.MAX_VALUE) scaled = Short.MAX_VALUE;
            if (scaled < Short.MIN_VALUE) scaled = Short.MIN_VALUE;
            pcm[i] = (byte) (scaled & 0xFF);
            pcm[i + 1] = (byte) ((scaled >>> 8) & 0xFF);
        }
        return clipped;
    }

    private static int mediaOutputDevices() throws Exception {
        Class<?> audioSystem = Class.forName("android.media.AudioSystem");
        return (Integer) audioSystem.getMethod("getDevicesForStream", int.class)
                .invoke(null, AudioManager.STREAM_MUSIC);
    }

    private void synchronizeCaptureVolume() throws Exception {
        AudioManager manager = captureVolumeManager;
        if (manager == null) return; // LOOP_BACK_RENDER retains the local route.
        long deadline = SystemClock.elapsedRealtime() + 2500L;
        int devices;
        while ((devices = mediaOutputDevices()) != 0x8000) {
            if (SystemClock.elapsedRealtime() >= deadline) {
                throw new CaptureRoutePendingException("REMOTE_SUBMIX media route did not become active; devices=0x" +
                        Integer.toHexString(devices));
            }
            Thread.sleep(20L);
        }
        // Never change the local speaker's index to repair a capture-route mute.
        captureMuteChanged = originalMediaMuted;
        // Match the volume-key action verified on the affected OEM. Explicit
        // UNMUTE/setStreamVolume can copy a maximum index into the speaker map.
        if (manager.isStreamMute(AudioManager.STREAM_MUSIC) ||
                manager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
            manager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0);
        }
        if (mediaOutputDevices() != 0x8000) {
            throw new IllegalStateException("Media route changed during capture volume setup");
        }
        Log.i(TAG, "capture route volume synchronized; originalMediaVolume=" +
                originalMediaVolume + " originalMuted=" + originalMediaMuted);
    }

    private void restoreLocalMute() {
        AudioManager manager = captureVolumeManager;
        captureVolumeManager = null;
        if (manager == null || !captureMuteChanged || !originalMediaMuted) return;
        captureMuteChanged = false;
        try {
            long deadline = SystemClock.elapsedRealtime() + 1000L;
            while ((mediaOutputDevices() & 0x8000) != 0) {
                if (SystemClock.elapsedRealtime() >= deadline) {
                    throw new IllegalStateException("Capture route still active after release");
                }
                Thread.sleep(20L);
            }
            // Do not overwrite a volume the user changed during the session.
            if (manager.getStreamVolume(AudioManager.STREAM_MUSIC) == originalMediaVolume) {
                manager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0);
            }
        } catch (Exception failure) {
            Log.e(TAG, "Could not restore local media mute after capture release", failure);
        }
    }

    private synchronized void releaseCapture() {
        AudioRecord r = recorder;
        recorder = null;
        if (r != null) {
            try {
                r.stop();
            } catch (Throwable ignored) {
            }
            try {
                r.release();
            } catch (Throwable ignored) {
            }
        }

        Object policy = registeredAudioPolicy;
        Class<?> policyClass = registeredAudioPolicyClass;
        AudioManager audioManager = registeredAudioManager;
        registeredAudioPolicy = null;
        registeredAudioPolicyClass = null;
        registeredAudioManager = null;
        activeCaptureMode = "none";

        if (policy != null && policyClass != null) {
            unregisterPolicy(audioManager, policy, policyClass);
        }
        restoreLocalMute();
    }

    /**
     * Task removal can destroy the Shizuku UserService immediately after stopBridge().
     * AudioManager.unregisterAudioPolicyAsyncStatic() is explicitly asynchronous, so
     * killing the UserService right afterwards can leave an OEM audio route/volume
     * context behind. Prefer the synchronous unregisterAudioPolicy() call and only
     * fall back to the asynchronous hidden API on frameworks where the synchronous
     * method cannot be reflected.
     */
    private static void unregisterPolicy(
            AudioManager audioManager,
            Object policy,
            Class<?> policyClass
    ) {
        Throwable syncFailure = null;

        if (audioManager != null) {
            try {
                Method unregister = AudioManager.class.getDeclaredMethod(
                        "unregisterAudioPolicy",
                        policyClass
                );
                unregister.setAccessible(true);
                unregister.invoke(audioManager, policy);
                Log.i(TAG, "AudioPolicy synchronously unregistered");
                return;
            } catch (Throwable t) {
                syncFailure = t;
                Log.w(TAG, "synchronous AudioPolicy unregister failed; falling back", t);
            }
        }

        try {
            Method unregister = AudioManager.class.getDeclaredMethod(
                    "unregisterAudioPolicyAsyncStatic",
                    policyClass
            );
            unregister.setAccessible(true);
            unregister.invoke(null, policy);
            Log.i(TAG, "AudioPolicy async fallback requested");
        } catch (Throwable asyncFailure) {
            if (syncFailure != null) {
                asyncFailure.addSuppressed(syncFailure);
            }
            Log.e(TAG, "could not unregister AudioPolicy", asyncFailure);
        }
    }

    private static void sendText(
            DatagramSocket socket,
            InetSocketAddress remote,
            String text
    ) throws Exception {
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        socket.send(new DatagramPacket(
                bytes,
                bytes.length,
                remote.getAddress(),
                remote.getPort()
        ));
    }

    private static int tokenInt(String message, String name, int fallback) {
        String prefix = name + "=";
        String[] parts = message.split(";");
        for (String part : parts) {
            if (part.startsWith(prefix)) {
                try {
                    return Integer.parseInt(part.substring(prefix.length()));
                } catch (NumberFormatException ignored) {
                    return fallback;
                }
            }
        }
        return fallback;
    }
}
