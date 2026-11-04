package com.cuscus.wifiaudiostreaming

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

/**
 * Small volume panel shown directly above the currently foreground app.
 *
 * This deliberately does not use an Activity: launching an Activity would move
 * video apps such as Douyin to the background and many of them pause playback.
 * TYPE_APPLICATION_OVERLAY stays above the current app while FLAG_NOT_FOCUSABLE
 * keeps keyboard/media focus with that app. The slider remains touchable.
 */
object VolumeOverlayController {

    const val MODE_SERVER = "server"
    const val MODE_CLIENT = "client"

    private val handler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var volumeJob: Job? = null

    private val dismissRunnable = Runnable { dismiss() }

    fun show(context: Context, mode: String) {
        val app = context.applicationContext

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(app)
        ) {
            Toast.makeText(
                app,
                app.getString(R.string.volume_overlay_permission_needed),
                Toast.LENGTH_LONG
            ).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${app.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { app.startActivity(intent) }
            return
        }

        handler.post {
            showInternal(app, mode)
        }
    }

    fun dismiss() {
        handler.removeCallbacks(dismissRunnable)
        volumeJob?.cancel(); volumeJob = null
        val manager = windowManager
        val view = overlayView
        overlayView = null
        windowManager = null
        if (manager != null && view != null) {
            runCatching { manager.removeViewImmediate(view) }
        }
    }

    private fun showInternal(context: Context, mode: String) {
        dismiss()

        val isServer = mode == MODE_SERVER
        val maxPercent = 100
        val maxGain = if (isServer) 2f else 1f
        val current = if (isServer) {
            NetworkManager.serverVolume.value
        } else {
            NetworkManager.clientVolume.value
        }

        val density = context.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).roundToInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.argb(238, 30, 30, 30))
                cornerRadius = dp(18).toFloat()
            }
            elevation = dp(10).toFloat()
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val title = TextView(context).apply {
            text = context.getString(
                if (isServer) R.string.volume_popup_sender_title
                else R.string.volume_popup_receiver_title
            )
            setTextColor(Color.WHITE)
            textSize = 17f
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        val close = TextView(context).apply {
            text = "×"
            setTextColor(Color.WHITE)
            textSize = 25f
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, 0, 0)
            setOnClickListener { dismiss() }
        }

        val valueLabel = TextView(context).apply {
            setTextColor(Color.LTGRAY)
            textSize = 14f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(4), 0, 0)
        }

        val seekBar = SeekBar(context).apply {
            max = maxPercent
            progress = VolumeScale.percent(current, maxGain)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        fun updateLabel(progress: Int) {
            valueLabel.text = context.getString(R.string.notif_volume, progress)
        }

        fun keepOpen() {
            handler.removeCallbacks(dismissRunnable)
            handler.postDelayed(dismissRunnable, 5_000L)
        }

        updateLabel(seekBar.progress)
        var tracking = false
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateLabel(progress)
                if (!fromUser) return
                keepOpen()
                val value = VolumeScale.gain(progress, maxGain)
                if (isServer) {
                    NetworkManager.setServerVolume(value)
                } else {
                    NetworkManager.setClientVolume(value.coerceIn(0f, 1f))
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                tracking = true
                handler.removeCallbacks(dismissRunnable)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                tracking = false
                val gain = if (isServer) NetworkManager.serverVolume.value else NetworkManager.clientVolume.value
                seekBar?.progress = VolumeScale.percent(gain, maxGain)
                keepOpen()
            }
        })

        header.addView(title)
        header.addView(close)
        root.addView(header)
        root.addView(valueLabel)
        root.addView(seekBar)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            (context.resources.displayMetrics.widthPixels * 0.90f).roundToInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(72)
        }

        val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        runCatching {
            manager.addView(root, params)
            windowManager = manager
            overlayView = root
            volumeJob = scope.launch {
                val volumes = if (isServer) NetworkManager.serverVolume else NetworkManager.clientVolume
                volumes.collect { gain ->
                    if (!tracking) seekBar.progress = VolumeScale.percent(gain, maxGain)
                }
            }
            keepOpen()
        }.onFailure {
            Toast.makeText(
                context,
                context.getString(R.string.volume_overlay_permission_needed),
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
