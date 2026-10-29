package com.remoteshutter

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything camera. No Garmin knowledge in here, which is what makes the
 * trigger source replaceable (watch today, anything tomorrow).
 *
 * We run our own camera instead of poking the OEM camera app: third-party
 * apps cannot press another app's shutter button without accessibility hacks,
 * and ACTION_IMAGE_CAPTURE always ends with a "save/discard" screen. Owning
 * the camera costs us ~150 lines and buys us a guaranteed, silent, instant
 * shot.
 */
class CameraEngine(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
) {

    enum class Mode { PHOTO, VIDEO }

    /** Emits "ok:..." or "err:..." lines. */
    @Volatile
    var onEvent: ((String) -> Unit)? = null

    @Volatile
    var mode: Mode = Mode.PHOTO
        private set

    private val executor = ContextCompat.getMainExecutor(context)
    private var provider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    fun start() {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                provider = future.get()
                bind()
            } catch (e: Exception) {
                emit(Protocol.err("camera init failed"))
            }
        }, executor)
    }

    fun setMode(newMode: Mode) {
        if (newMode == mode) return
        if (recording != null) {
            emit(Protocol.err("recording in progress"))
            return
        }
        mode = newMode
        bind()
    }

    /**
     * We rebind instead of binding preview+photo+video at once: some devices
     * refuse the three-way combination, and rebinding takes milliseconds.
     */
    private fun bind() {
        val cameraProvider = provider ?: return
        cameraProvider.unbindAll()

        val preview = Preview.Builder()
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        try {
            when (mode) {
                Mode.PHOTO -> {
                    val capture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build()
                    imageCapture = capture
                    videoCapture = null
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture
                    )
                }

                Mode.VIDEO -> {
                    val recorder = Recorder.Builder()
                        .setQualitySelector(
                            QualitySelector.from(
                                Quality.HD,
                                FallbackStrategy.higherQualityOrLowerThan(Quality.SD)
                            )
                        )
                        .build()
                    val capture = VideoCapture.withOutput(recorder)
                    videoCapture = capture
                    imageCapture = null
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture
                    )
                }
            }
            emit(Protocol.ok("camera ${mode.name.lowercase()}"))
        } catch (e: Exception) {
            emit(Protocol.err("camera ${e.message}"))
        }
    }

    fun takePhoto() {
        val capture = imageCapture ?: run {
            emit(Protocol.err("camera busy"))
            return
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "RS_${stamp()}")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/RemoteShutter")
        }
        val options = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        ).build()

        capture.takePicture(options, executor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                emit(Protocol.ok("photo"))
            }

            override fun onError(error: ImageCaptureException) {
                emit(Protocol.err(error.message ?: "capture failed"))
            }
        })
    }

    fun toggleVideo() {
        val inFlight = recording
        if (inFlight != null) {
            try {
                inFlight.stop()
            } catch (e: Exception) {
                emit(Protocol.err(e.message ?: "video failed"))
            }
            return
        }

        val capture = videoCapture ?: run {
            emit(Protocol.err("camera busy"))
            return
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "RS_${stamp()}")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/RemoteShutter")
        }
        val options = MediaStoreOutputOptions.Builder(
            context.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(values).build()

        recording = capture.output
            .prepareRecording(context, options)
            .start(executor) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> emit(Protocol.ok("recording"))
                    is VideoRecordEvent.Finalize -> {
                        recording = null
                        if (event.hasError()) {
                            emit(Protocol.err("video ${event.error}"))
                        } else {
                            emit(Protocol.ok("video"))
                        }
                    }
                }
            }
    }

    fun release() {
        try {
            recording?.stop()
        } catch (_: Exception) {
        }
        recording = null
        provider?.unbindAll()
    }

    private fun emit(text: String) {
        onEvent?.invoke(text)
    }

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
}
