package com.remoteshutter

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat

/**
 * The camera screen.
 *
 * Two rules keep this reliable:
 *   1. This activity stays visible and awake. CameraX is lifecycle-bound, so
 *      as soon as we are backgrounded the camera is released.
 *   2. Every trigger gets exactly one reply ("ok:..." / "err:..."), because
 *      the watch shows FAILED if no reply arrives within six seconds.
 */
class MainActivity : ComponentActivity() {

    companion object {
        /** adb shell am broadcast -a com.remoteshutter.TRIGGER --es cmd photo */
        const val ACTION_TRIGGER = "com.remoteshutter.TRIGGER"
        private const val REQ_CAMERA = 1
    }

    private lateinit var previewView: PreviewView
    private lateinit var statusView: TextView
    private lateinit var modeButton: Button
    private lateinit var hintView: TextView

    private var engine: CameraEngine? = null
    private lateinit var app: App

    private val triggerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (val cmd = intent?.getStringExtra("cmd")) {
                "mode:photo" -> engine?.setMode(CameraEngine.Mode.PHOTO)
                "mode:video" -> engine?.setMode(CameraEngine.Mode.VIDEO)
                // Anything else goes through the same parser the watch path
                // uses, so "adb shell am broadcast" exercises the real thing.
                else -> onCommand(Protocol.parseCommand(cmd))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as App

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()

        if (hasCameraPermission()) {
            startEngine()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }

        app.ciq.onCommand = { cmd -> runOnUiThread { onCommand(cmd) } }
        app.ciq.onStatus = { text -> runOnUiThread { setStatus(text) } }
        app.ciq.start()
    }

    override fun onStart() {
        super.onStart()
        // The adb trigger is a convenience and a way to test the camera path
        // without the watch, so it is only exported in debug builds. In
        // release builds any installed app could otherwise fire the shutter.
        val flags = if (BuildConfig.DEBUG) {
            ContextCompat.RECEIVER_EXPORTED
        } else {
            ContextCompat.RECEIVER_NOT_EXPORTED
        }
        ContextCompat.registerReceiver(
            this,
            triggerReceiver,
            IntentFilter(ACTION_TRIGGER),
            flags,
        )
    }

    override fun onStop() {
        unregisterReceiver(triggerReceiver)
        super.onStop()
    }

    override fun onDestroy() {
        engine?.release()
        engine = null
        super.onDestroy()
    }

    @Deprecated("Kept for the platform signature; ComponentActivity routes here.")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                startEngine()
            } else {
                setStatus("Camera permission denied")
            }
        }
    }

    // ---------------------------------------------------------------- camera

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun startEngine() {
        if (engine != null) return
        val created = CameraEngine(this, this, previewView)
        created.onEvent = { event -> runOnUiThread { onCameraEvent(event) } }
        created.start()
        engine = created
    }

    /** A command arrived from the watch (or from adb for testing). */
    private fun onCommand(cmd: Protocol.Command) {
        val cam = engine
        if (cam == null) {
            reply(Protocol.err("no camera"))
            return
        }
        when (cmd) {
            Protocol.Command.Photo -> {
                cam.setMode(CameraEngine.Mode.PHOTO)
                cam.takePhoto()
            }

            Protocol.Command.Video -> {
                cam.setMode(CameraEngine.Mode.VIDEO)
                cam.toggleVideo()
            }

            is Protocol.Command.Unknown -> reply(Protocol.err("unknown command"))
        }
    }

    /** Camera results go back to the watch *and* to the screen. */
    private fun onCameraEvent(event: String) {
        setStatus(event)
        reply(event)
    }

    private fun reply(event: String) {
        app.ciq.reply(event)
    }

    // -------------------------------------------------------------------- ui

    private fun buildUi() {
        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }

        val root = FrameLayout(this)
        root.addView(previewView, matchParent())

        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            )
        }

        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
            textSize = 15f
            setPadding(dp(16), dp(12), dp(16), dp(4))
            text = "Starting..."
        }

        hintView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
            textSize = 12f
            setPadding(dp(16), 0, dp(16), dp(8))
            text = "Keep this screen on. Press START on your watch."
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(4), dp(12), dp(20))
        }

        modeButton = Button(this).apply {
            text = "PHOTO"
            textSize = 14f
            setOnClickListener { toggleMode() }
            layoutParams = LinearLayout.LayoutParams(0, dp(64), 1f).also {
                it.marginEnd = dp(12)
            }
        }

        val shootButton = Button(this).apply {
            text = "SHOOT"
            textSize = 18f
            setOnClickListener {
                val video = engine?.mode == CameraEngine.Mode.VIDEO
                onCommand(if (video) Protocol.Command.Video else Protocol.Command.Photo)
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(64), 2f)
        }

        buttons.addView(modeButton)
        buttons.addView(shootButton)
        overlay.addView(statusView)
        overlay.addView(hintView)
        overlay.addView(buttons)

        root.addView(overlay)
        root.keepScreenOn = true
        setContentView(root)
    }

    private fun toggleMode() {
        val cam = engine ?: return
        val next = if (cam.mode == CameraEngine.Mode.PHOTO) CameraEngine.Mode.VIDEO else CameraEngine.Mode.PHOTO
        cam.setMode(next)
        modeButton.text = next.name
    }

    private fun setStatus(text: String) {
        statusView.text = text
        if (engine != null) {
            modeButton.text = engine!!.mode.name
        }
    }

    private fun matchParent() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT,
    )

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
}
