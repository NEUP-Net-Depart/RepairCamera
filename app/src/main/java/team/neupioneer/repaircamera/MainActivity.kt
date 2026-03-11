package team.neupioneer.repaircamera

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.camera2.CameraCharacteristics
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.Spannable
import android.text.SpannableString
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.SizeF
import android.view.LayoutInflater
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatImageButton
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.PendingRecording
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.TransformationRequest
import androidx.media3.transformer.Transformer
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

@UnstableApi
class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var flashButton: AppCompatImageButton
    private lateinit var zoomText: TextView
    private lateinit var recordingTimeText: TextView
    private lateinit var overlayText: TextView
    private lateinit var signInButton: AppCompatButton
    private lateinit var registerButton: AppCompatButton
    private lateinit var recordButton: AppCompatImageButton
    private lateinit var pauseButton: AppCompatImageButton

    private lateinit var cameraExecutor: ExecutorService

    private val timerHandler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            recordingTimeText.text = formatRecordingDuration(getActiveRecordingDurationMs())
            timerHandler.postDelayed(this, 250L)
        }
    }

    private var camera: Camera? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var exportTransformer: Transformer? = null
    private var isRecordingPaused = false
    private var isTorchEnabled = false
    private var currentRegistrationText = ""
    private var currentOutputPath: String? = null
    private var permissionFlowStarted = false
    private var previewReady = false
    private var isStoppingRecording = false
    private var isExportingVideo = false
    private var recordingStartElapsedMs = 0L
    private var pausedDurationMs = 0L
    private var pauseStartElapsedMs = 0L
    private var currentRawVideoFile: File? = null
    private var currentFinalVideoFile: File? = null
    private var currentRecordingOverlayText: String? = null

    private val runtimePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val allGranted = REQUIRED_RUNTIME_PERMISSIONS.all { permission ->
            result[permission] == true || ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
        if (!allGranted) {
            toastAndFinish(getString(R.string.permission_camera_audio_required))
            return@registerForActivityResult
        }
        ensureManageAllFilesPermission()
    }

    private val manageFilesPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (hasManageAllFilesPermission()) {
            startCameraIfReady()
        } else {
            toastAndFinish(getString(R.string.permission_all_files_required))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        cameraExecutor = Executors.newSingleThreadExecutor()
        bindViews()
        applyWindowInsets()
        setupControls()
        checkAndRequestPermissions()
    }

    override fun onResume() {
        super.onResume()
        if (permissionFlowStarted && hasAllPermissions()) {
            startCameraIfReady()
        }
    }

    override fun onDestroy() {
        stopTimerUpdates(reset = true)
        activeRecording?.close()
        activeRecording = null
        exportTransformer?.cancel()
        exportTransformer = null
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    private fun bindViews() {
        previewView = findViewById(R.id.previewView)
        flashButton = findViewById(R.id.flashButton)
        zoomText = findViewById(R.id.zoomText)
        recordingTimeText = findViewById(R.id.recordingTimeText)
        overlayText = findViewById(R.id.overlayText)
        signInButton = findViewById(R.id.signInButton)
        registerButton = findViewById(R.id.registerButton)
        recordButton = findViewById(R.id.recordButton)
        pauseButton = findViewById(R.id.pauseButton)
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    private fun setupControls() {
        flashButton.setOnClickListener {
            isTorchEnabled = !isTorchEnabled
            camera?.cameraControl?.enableTorch(isTorchEnabled)
            updateFlashButton()
        }

        registerButton.setOnClickListener {
            showQuestionnaireDialog()
        }

        recordButton.setOnClickListener {
            if (activeRecording == null) {
                startRecording()
            } else {
                stopRecording()
            }
        }

        pauseButton.setOnClickListener {
            togglePauseResume()
        }

        val scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean = true

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val zoomState = camera?.cameraInfo?.zoomState?.value ?: return false
                val targetZoom = (zoomState.zoomRatio * detector.scaleFactor)
                    .coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
                camera?.cameraControl?.setZoomRatio(targetZoom)
                return true
            }
        })

        previewView.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            true
        }

        recordingTimeText.text = getString(R.string.recording_time_default)
        recordingTimeText.visibility = View.GONE
        overlayText.text = currentRegistrationText
        overlayText.visibility = View.GONE
        updateFlashButton()
        updateRecordingUi(isRecording = false)
    }

    private fun checkAndRequestPermissions() {
        permissionFlowStarted = true
        val missingRuntimePermissions = REQUIRED_RUNTIME_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingRuntimePermissions.isNotEmpty()) {
            runtimePermissionLauncher.launch(missingRuntimePermissions.toTypedArray())
            return
        }
        ensureManageAllFilesPermission()
    }

    private fun ensureManageAllFilesPermission() {
        if (hasManageAllFilesPermission()) {
            startCameraIfReady()
            return
        }

        Snackbar.make(previewView, R.string.ask_manage_all_files, Snackbar.LENGTH_LONG).show()
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:$packageName")
        }
        try {
            manageFilesPermissionLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            manageFilesPermissionLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    private fun hasManageAllFilesPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    private fun hasAllPermissions(): Boolean {
        return REQUIRED_RUNTIME_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        } && hasManageAllFilesPermission()
    }

    private fun startCameraIfReady() {
        if (!hasAllPermissions() || previewReady) {
            return
        }
        startCamera()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val selectedCameraInfo = resolveMainBackCameraInfo(cameraProvider)
                val selector = buildSelectorForCameraInfo(selectedCameraInfo)

                val preview = Preview.Builder()
                    .setTargetRotation(Surface.ROTATION_0)
                    .build().apply {
                        surfaceProvider = previewView.surfaceProvider
                    }

                val recorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.from(
                            Quality.FHD,
                            FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD)
                        )
                    )
                    .build()

                videoCapture = VideoCapture.Builder(recorder)
                    .setTargetRotation(Surface.ROTATION_270)
                    .build()

                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this,
                    selector,
                    preview,
                    videoCapture
                )
                observeCameraState()
                previewReady = true
            } catch (error: Exception) {
                previewReady = false
                Toast.makeText(this, getString(R.string.preview_unavailable), Toast.LENGTH_LONG).show()
                error.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun resolveMainBackCameraInfo(cameraProvider: ProcessCameraProvider): CameraInfo {
        val backCameraInfos = cameraProvider.availableCameraInfos.filter { info ->
            Camera2CameraInfo.from(info).getCameraCharacteristic(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
        return backCameraInfos.maxByOrNull { info -> scoreMainCamera(info) }
            ?: throw IllegalStateException("No back camera found")
    }

    private fun buildSelectorForCameraInfo(targetInfo: CameraInfo): CameraSelector {
        val targetCameraId = Camera2CameraInfo.from(targetInfo).cameraId
        return CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .addCameraFilter { infos ->
                infos.filter { Camera2CameraInfo.from(it).cameraId == targetCameraId }
            }
            .build()
    }

    private fun scoreMainCamera(info: CameraInfo): Double {
        val camera2Info = Camera2CameraInfo.from(info)
        val focalLengths = camera2Info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        val sensorSize = camera2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        return computeLensScore(sensorSize, focalLengths)
    }

    private fun computeLensScore(sensorSize: SizeF?, focalLengths: FloatArray?): Double {
        val areaScore = sensorSize?.let { it.width.toDouble() * it.height.toDouble() } ?: 0.0
        val focal = focalLengths?.maxOrNull()?.toDouble() ?: 0.0
        val focalPenalty = abs(focal - 4.5)
        return areaScore * 1000.0 - focalPenalty
    }

    private fun observeCameraState() {
        camera?.cameraInfo?.zoomState?.observe(this) { zoomState ->
            zoomText.text = getString(R.string.zoom_ratio, zoomState.zoomRatio)
        }
        val hasFlash = camera?.cameraInfo?.hasFlashUnit() == true
        flashButton.isEnabled = hasFlash
        if (!hasFlash) {
            isTorchEnabled = false
        }
        updateFlashButton()
    }

    private fun updateFlashButton() {
        flashButton.alpha = if (flashButton.isEnabled) 1f else 0.4f
        flashButton.isSelected = isTorchEnabled
        flashButton.setImageResource(if (isTorchEnabled) R.drawable.ic_flash_on_white else R.drawable.ic_flash_white)
    }

    private fun showQuestionnaireDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_questionnaire, null)
        val editText = dialogView.findViewById<TextInputEditText>(R.id.questionnaireEditText)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.questionnaire_dialog_title)
            .setView(dialogView)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val input = editText.text?.toString()?.trim().orEmpty()
                if (input.isNotEmpty()) {
                    currentRegistrationText = input
                    overlayText.text = currentRegistrationText
                    updateIdleControlsState()
                    Toast.makeText(this, getString(R.string.questionnaire_saved, currentRegistrationText), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, R.string.questionnaire_missing, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun startRecording() {
        if (isExportingVideo) {
            Toast.makeText(this, R.string.export_in_progress, Toast.LENGTH_SHORT).show()
            return
        }

        val registrationText = currentRegistrationText.trim()
        if (registrationText.isEmpty()) {
            Toast.makeText(this, R.string.questionnaire_missing, Toast.LENGTH_SHORT).show()
            return
        }

        val currentVideoCapture = videoCapture ?: return
        currentVideoCapture.targetRotation = Surface.ROTATION_270
        isStoppingRecording = false

        val fileName = buildVideoFileName(registrationText)
        val rawFile = try {
            createRawOutputFile(fileName)
        } catch (_: IllegalStateException) {
            Toast.makeText(this, R.string.storage_create_failed, Toast.LENGTH_LONG).show()
            return
        }
        val finalFile = File(OUTPUT_DIRECTORY_PATH, fileName)

        currentRawVideoFile = rawFile
        currentFinalVideoFile = finalFile
        currentRecordingOverlayText = registrationText
        currentOutputPath = finalFile.absolutePath

        var pendingRecording: PendingRecording = currentVideoCapture.output.prepareRecording(
            this,
            FileOutputOptions.Builder(rawFile).build()
        )
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pendingRecording = pendingRecording.withAudioEnabled()
        }

        activeRecording = pendingRecording.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    isRecordingPaused = false
                    recordingStartElapsedMs = SystemClock.elapsedRealtime()
                    pausedDurationMs = 0L
                    pauseStartElapsedMs = 0L
                    overlayText.text = registrationText
                    overlayText.visibility = View.VISIBLE
                    startTimerUpdates()
                    updateRecordingUi(isRecording = true)
                    Toast.makeText(this, getString(R.string.recording_started, fileName), Toast.LENGTH_SHORT).show()
                }

                is VideoRecordEvent.Pause -> {
                    isRecordingPaused = true
                    pauseStartElapsedMs = SystemClock.elapsedRealtime()
                    stopTimerUpdates(reset = false)
                    pauseButton.setImageResource(android.R.drawable.ic_media_play)
                    Toast.makeText(this, R.string.recording_paused, Toast.LENGTH_SHORT).show()
                }

                is VideoRecordEvent.Resume -> {
                    isRecordingPaused = false
                    if (pauseStartElapsedMs > 0L) {
                        pausedDurationMs += SystemClock.elapsedRealtime() - pauseStartElapsedMs
                    }
                    pauseStartElapsedMs = 0L
                    startTimerUpdates()
                    pauseButton.setImageResource(R.drawable.ic_pause_white)
                    Toast.makeText(this, R.string.recording_resumed, Toast.LENGTH_SHORT).show()
                }

                is VideoRecordEvent.Finalize -> {
                    handleRecordingFinalize(event)
                }

                else -> Unit
            }
        }
    }

    private fun handleRecordingFinalize(event: VideoRecordEvent.Finalize) {
        val rawFile = currentRawVideoFile
        val finalFile = currentFinalVideoFile
        val overlayContent = currentRecordingOverlayText.orEmpty()
        val savedPath = currentOutputPath.orEmpty()

        isStoppingRecording = false
        activeRecording = null
        isRecordingPaused = false
        overlayText.visibility = View.GONE
        stopTimerUpdates(reset = true)
        updateRecordingUi(isRecording = false)

        currentRawVideoFile = null
        currentFinalVideoFile = null
        currentRecordingOverlayText = null
        currentOutputPath = null

        if (event.hasError()) {
            Toast.makeText(this, getString(R.string.recording_error, event.error.toString()), Toast.LENGTH_LONG).show()
            return
        }

        if (rawFile == null || finalFile == null) {
            Toast.makeText(this, getString(R.string.recording_saved, savedPath), Toast.LENGTH_LONG).show()
            return
        }

        exportRecordedVideo(rawFile, finalFile, overlayContent)
    }

    private fun exportRecordedVideo(rawFile: File, finalFile: File, overlayContent: String) {
        val overlayEffect = createVideoOverlayEffect(overlayContent)
        val editedMediaItem = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(rawFile)))
            .setEffects(Effects(emptyList(), listOf(overlayEffect)))
            .build()

        if (finalFile.exists()) {
            finalFile.delete()
        }

        isExportingVideo = true
        var hevcExportApplied = true
        Toast.makeText(this, R.string.export_started, Toast.LENGTH_SHORT).show()

        val transformer = Transformer.Builder(this)
            .setVideoMimeType(MimeTypes.VIDEO_H265)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    exportTransformer = null
                    isExportingVideo = false
                    rawFile.delete()
                    val messageId = if (hevcExportApplied) R.string.recording_saved else R.string.recording_saved_fallback
                    Toast.makeText(
                        this@MainActivity,
                        getString(messageId, finalFile.absolutePath),
                        Toast.LENGTH_LONG
                    ).show()
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    exportTransformer = null
                    isExportingVideo = false
                    if (finalFile.exists()) {
                        finalFile.delete()
                    }
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.export_failed_raw_kept, rawFile.absolutePath),
                        Toast.LENGTH_LONG
                    ).show()
                }

                override fun onFallbackApplied(
                    composition: Composition,
                    originalTransformationRequest: TransformationRequest,
                    fallbackTransformationRequest: TransformationRequest
                ) {
                    if (fallbackTransformationRequest.videoMimeType != MimeTypes.VIDEO_H265) {
                        hevcExportApplied = false
                    }
                }
            })
            .build()

        exportTransformer = transformer

        try {
            transformer.start(editedMediaItem, finalFile.absolutePath)
        } catch (_: Exception) {
            exportTransformer = null
            isExportingVideo = false
            Toast.makeText(this, getString(R.string.export_failed_raw_kept, rawFile.absolutePath), Toast.LENGTH_LONG).show()
        }
    }

    private fun createVideoOverlayEffect(overlayContent: String): OverlayEffect {
        val styledText = SpannableString(overlayContent).apply {
            setSpan(ForegroundColorSpan(Color.WHITE), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.BOLD), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(AbsoluteSizeSpan(24, true), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val overlaySettings = StaticOverlaySettings.Builder()
            .setBackgroundFrameAnchor(-0.94f, -0.92f)
            .setOverlayFrameAnchor(-1f, -1f)
            .build()
        val textOverlay = TextOverlay.createStaticTextOverlay(styledText, overlaySettings)
        return OverlayEffect(listOf(textOverlay))
    }

    private fun startTimerUpdates() {
        recordingTimeText.visibility = View.VISIBLE
        recordingTimeText.text = formatRecordingDuration(getActiveRecordingDurationMs())
        timerHandler.removeCallbacks(timerRunnable)
        timerHandler.post(timerRunnable)
    }

    private fun stopTimerUpdates(reset: Boolean) {
        timerHandler.removeCallbacks(timerRunnable)
        if (reset) {
            recordingStartElapsedMs = 0L
            pausedDurationMs = 0L
            pauseStartElapsedMs = 0L
            recordingTimeText.text = getString(R.string.recording_time_default)
            recordingTimeText.visibility = View.GONE
        }
    }

    private fun getActiveRecordingDurationMs(): Long {
        if (recordingStartElapsedMs == 0L) {
            return 0L
        }
        val now = if (isRecordingPaused && pauseStartElapsedMs > 0L) pauseStartElapsedMs else SystemClock.elapsedRealtime()
        return (now - recordingStartElapsedMs - pausedDurationMs).coerceAtLeast(0L)
    }

    private fun formatRecordingDuration(durationMs: Long): String {
        val totalSeconds = durationMs / 1000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }

    private fun createRawOutputFile(finalFileName: String): File {
        val outputDirectory = File(OUTPUT_DIRECTORY_PATH)
        if (!outputDirectory.exists() && !outputDirectory.mkdirs()) {
            throw IllegalStateException("Unable to create output directory")
        }

        val rawFile = File(outputDirectory, finalFileName.removeSuffix(".mp4") + RAW_FILE_SUFFIX)
        if (rawFile.exists()) {
            rawFile.delete()
        }
        return rawFile
    }

    private fun togglePauseResume() {
        val recording = activeRecording ?: return
        if (isRecordingPaused) {
            recording.resume()
        } else {
            recording.pause()
        }
    }

    private fun stopRecording() {
        val recording = activeRecording ?: return
        if (isStoppingRecording) {
            return
        }
        isStoppingRecording = true
        pauseButton.isEnabled = false
        recordButton.isEnabled = false
        recording.stop()
        recording.close()
    }

    private fun updateRecordingUi(isRecording: Boolean) {
        signInButton.visibility = if (isRecording) View.INVISIBLE else View.VISIBLE
        registerButton.visibility = if (isRecording) View.GONE else View.VISIBLE
        pauseButton.visibility = if (isRecording) View.VISIBLE else View.GONE
        pauseButton.isEnabled = isRecording && !isStoppingRecording
        recordButton.setBackgroundResource(if (isRecording) R.drawable.bg_stop_button else R.drawable.bg_record_button)
        recordButton.contentDescription = getString(if (isRecording) R.string.stop else R.string.record)
        pauseButton.setImageResource(R.drawable.ic_pause_white)

        if (isRecording) {
            recordButton.isEnabled = !isStoppingRecording
            recordButton.alpha = if (recordButton.isEnabled) 1f else 0.55f
        } else {
            updateIdleControlsState()
        }
    }

    private fun updateIdleControlsState() {
        val hasRegistrationText = currentRegistrationText.isNotBlank()
        recordButton.isEnabled = hasRegistrationText && !isExportingVideo
        recordButton.alpha = if (recordButton.isEnabled) 1f else 0.55f
        registerButton.isEnabled = !isExportingVideo
        registerButton.alpha = if (registerButton.isEnabled) 1f else 0.7f
    }

    private fun buildVideoFileName(registrationText: String): String {
        val prefix = SimpleDateFormat("yyyyMMddHHmm", Locale.getDefault()).format(Date())
        val safeName = sanitizeForFileName(registrationText).ifBlank { buildDefaultQuestionnaireId() }
        return "$prefix-$safeName.mp4"
    }

    private fun sanitizeForFileName(value: String): String {
        return value
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .replace(Regex("\\s+"), "_")
            .trim('_')
            .take(MAX_FILE_NAME_PART_LENGTH)
    }

    private fun buildDefaultQuestionnaireId(): String {
        return SimpleDateFormat("yyMMdd'01'", Locale.getDefault()).format(Date())
    }

    private fun toastAndFinish(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    companion object {
        private const val OUTPUT_DIRECTORY_PATH = "/storage/emulated/0/Documents/PioneerCamera"
        private const val RAW_FILE_SUFFIX = "_raw.mp4"
        private const val MAX_FILE_NAME_PART_LENGTH = 48

        private val REQUIRED_RUNTIME_PERMISSIONS = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            if (Build.VERSION.SDK_INT in Build.VERSION_CODES.Q..Build.VERSION_CODES.S_V2) {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }
}