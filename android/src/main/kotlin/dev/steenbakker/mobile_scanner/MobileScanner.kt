package dev.steenbakker.mobile_scanner

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ExperimentalLensFacing
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import io.flutter.plugins.camerapreview.AlreadyStarted
import io.flutter.plugins.camerapreview.CameraError
import io.flutter.plugins.camerapreview.CameraSurfacePreview
import io.flutter.plugins.camerapreview.NoCamera
import dev.steenbakker.mobile_scanner.objects.DetectionSpeed
import dev.steenbakker.mobile_scanner.objects.MobileScannerErrorCodes
import dev.steenbakker.mobile_scanner.utils.invertBitmapColors
import dev.steenbakker.mobile_scanner.utils.rotateBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * A native view implementation for the mobile scanner that renders
 * the camera preview using a SurfaceView and performs barcode scanning.
 *
 * This class extends [CameraSurfacePreview] to add barcode scanning
 * functionality via ML Kit.
 *
 * Usage in MainActivity.kt:
 * ```kotlin
 * class MainActivity : NativeViewFlutterActivity() {
 *     override fun onRegisterNativeViews() {
 *         registerNativeViewFactory("mobile_scanner_native_view") {
 *             MobileScannerNativeView()
 *         }
 *     }
 * }
 * ```
 */
class MobileScannerNativeView : CameraSurfacePreview() {

    companion object {
        private const val TAG = "MobileScannerNativeView"

        private var barcodeHandler: BarcodeHandler? = null
        private var activity: Activity? = null
        private var currentInstance: MobileScannerNativeView? = null

        /**
         * Initialize the native view with the barcode handler.
         * Called by MobileScannerPlugin when attached to the activity.
         */
        fun initialize(activity: Activity, barcodeHandler: BarcodeHandler) {
            Log.d(TAG, "Initializing")
            this.activity = activity
            this.barcodeHandler = barcodeHandler
        }

        /**
         * Dispose and clear references.
         * Called by MobileScannerPlugin when detached from the activity.
         */
        fun dispose() {
            Log.d(TAG, "Disposing")
            barcodeHandler = null
            activity = null
            currentInstance = null
        }

        /**
         * Toggle the torch on the current instance.
         */
        fun toggleTorchStatic() {
            currentInstance?.toggleTorch()
        }

        /**
         * Set the zoom scale on the current instance.
         */
        fun setScaleStatic(scale: Double) {
            currentInstance?.setScale(scale)
        }

        /**
         * Reset the zoom scale on the current instance.
         */
        fun resetScaleStatic() {
            currentInstance?.resetScale()
        }

        /**
         * Set the focus point on the current instance.
         */
        fun setFocusStatic(x: Float, y: Float) {
            currentInstance?.setFocus(x, y)
        }

        /**
         * Update the scan window on the current instance.
         */
        fun updateScanWindowStatic(scanWindow: List<Float>?) {
            currentInstance?.scanWindow = scanWindow
        }

        fun defaultBarcodeScannerFactory(options: BarcodeScannerOptions?): BarcodeScanner {
            return if (options == null) BarcodeScanning.getClient() else BarcodeScanning.getClient(options)
        }

        private fun Exception.toErrorCode(): String = when (this) {
            is AlreadyStarted -> MobileScannerErrorCodes.ALREADY_STARTED_ERROR
            is CameraError -> MobileScannerErrorCodes.CAMERA_ERROR
            is NoCamera -> MobileScannerErrorCodes.NO_CAMERA_ERROR
            else -> MobileScannerErrorCodes.GENERIC_ERROR
        }
    }

    // Barcode scanning state
    private var scanner: BarcodeScanner? = null
    private var lastScanned: List<String?>? = null
    private var scannerTimeout = false

    // Barcode scanning configuration
    var scanWindow: List<Float>? = null
    private var invertImage: Boolean = false
    private var detectionSpeed: DetectionSpeed = DetectionSpeed.NO_DUPLICATES
    private var detectionTimeout: Long = 250
    private var returnImage = false
    private var barcodeScannerOptions: BarcodeScannerOptions? = null

    init {
        Log.d(TAG, "Creating native view")
        currentInstance = this
        setupCallbacks()
    }

    /**
     * Configure the scanner parameters before starting.
     */
    fun configure(
        barcodeScannerOptions: BarcodeScannerOptions?,
        returnImage: Boolean,
        cameraPosition: CameraSelector,
        torch: Boolean,
        detectionSpeed: DetectionSpeed,
        detectionTimeout: Long,
        cameraResolutionWanted: Size?,
        invertImage: Boolean,
        initialZoom: Double?,
    ) {
        // Configure base camera settings
        super.configure(cameraPosition, torch, cameraResolutionWanted, initialZoom)

        // Configure barcode scanning settings
        this.barcodeScannerOptions = barcodeScannerOptions
        this.returnImage = returnImage
        this.detectionSpeed = detectionSpeed
        this.detectionTimeout = detectionTimeout
        this.invertImage = invertImage
    }

    @ExperimentalLensFacing
    override fun startCamera() {
        // Initialize scanner before starting camera
        lastScanned = null
        scanner = defaultBarcodeScannerFactory(barcodeScannerOptions)

        // Map error callback
        cameraStartErrorCallback = { exception ->
            Handler(Looper.getMainLooper()).post {
                Log.e(TAG, "Scanner start error: ${exception.message}")
                barcodeHandler?.publishError(
                    exception.toErrorCode(),
                    exception.message ?: "Unknown error",
                    null
                )
            }
        }

        super.startCamera()
    }

    @ExperimentalGetImage
    override fun createImageAnalysis(displayRotation: Int, cameraResolution: Size): ImageAnalysis {
        val analysisBuilder = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .setTargetRotation(displayRotation)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            cameraResolution,
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()
            )

        return analysisBuilder.build().apply {
            setAnalyzer(analysisExecutor, captureOutput)
        }
    }

    @ExperimentalLensFacing
    override fun onCameraStarted(camera: Camera, analysisResolution: Size?, numberOfCameras: Int) {
        val cameraDirection = getCameraLensFacing(camera)
        val sensorRotationDegrees = camera.cameraInfo.sensorRotationDegrees
        val portrait = sensorRotationDegrees % 180 == 0

        val width = analysisResolution?.width?.toDouble() ?: 0.0
        val height = analysisResolution?.height?.toDouble() ?: 0.0

        var currentTorchState: Int = -1
        camera.cameraInfo.let {
            if (it.hasFlashUnit()) {
                currentTorchState = it.torchState.value ?: -1
            }
        }

        Handler(Looper.getMainLooper()).post {
            Log.d(TAG, "Scanner started: ${width}x${height}")
            barcodeHandler?.publishEvent(
                mapOf(
                    "name" to "scannerStarted",
                    "size" to mapOf(
                        "width" to if (portrait) width else height,
                        "height" to if (portrait) height else width
                    ),
                    "currentTorchState" to currentTorchState,
                    "numberOfCameras" to numberOfCameras,
                    "cameraDirection" to cameraDirection
                )
            )
        }
    }

    override fun releaseCamera(isDisposing: Boolean) {
        super.releaseCamera(isDisposing)

        // Release barcode scanner resources
        scanner?.close()
        scanner = null
        lastScanned = null
    }

    // ========== Callback Setup ==========

    private fun setupCallbacks() {
        // Camera callbacks
        torchStateCallback = { state ->
            barcodeHandler?.publishEvent(mapOf("name" to "torchState", "data" to state))
        }

        zoomScaleStateCallback = { zoomScale ->
            barcodeHandler?.publishEvent(mapOf("name" to "zoomScaleState", "data" to zoomScale))
        }
    }

    // ========== Barcode Processing ==========

    private fun onBarcodeDetected(barcodes: List<Map<String, Any?>>, image: ByteArray?, width: Int?, height: Int?) {
        Log.d(TAG, "Barcode detected: ${barcodes.size} barcodes")
        barcodeHandler?.publishEvent(
            mapOf(
                "name" to "barcode",
                "data" to barcodes,
                "image" to mapOf(
                    "bytes" to image,
                    "width" to width?.toDouble(),
                    "height" to height?.toDouble(),
                )
            )
        )
    }

    private fun onScanError(error: String) {
        Log.e(TAG, "Scanner error: $error")
        barcodeHandler?.publishError(MobileScannerErrorCodes.BARCODE_ERROR, error, null)
    }

    /**
     * Image analysis callback for processing camera frames.
     */
    @ExperimentalGetImage
    private val captureOutput = ImageAnalysis.Analyzer { imageProxy ->
        val mediaImage = imageProxy.image ?: return@Analyzer

        if (detectionSpeed == DetectionSpeed.NORMAL && scannerTimeout) {
            imageProxy.close()
            return@Analyzer
        } else if (detectionSpeed == DetectionSpeed.NORMAL) {
            scannerTimeout = true
        }

        var invertedBitmap: Bitmap? = null
        val inputImage = if (invertImage) {
            val bitmap = imageProxy.toBitmap()
            invertedBitmap = invertBitmapColors(bitmap)
            bitmap.recycle()
            InputImage.fromBitmap(invertedBitmap, imageProxy.imageInfo.rotationDegrees)
        } else {
            InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        }

        scanner?.let {
            it.process(inputImage).addOnSuccessListener { barcodes ->
                if (detectionSpeed == DetectionSpeed.NO_DUPLICATES) {
                    val newScannedBarcodes = barcodes.mapNotNull { barcode ->
                        barcode.rawValue
                    }.sorted()

                    if (newScannedBarcodes == lastScanned) {
                        imageProxy.close()
                        return@addOnSuccessListener
                    }
                    if (newScannedBarcodes.isNotEmpty()) {
                        lastScanned = newScannedBarcodes
                    }
                }

                val barcodeMap: MutableList<Map<String, Any?>> = mutableListOf()

                for (barcode in barcodes) {
                    if (scanWindow == null) {
                        barcodeMap.add(barcode.data)
                        continue
                    }

                    if (isBarcodeInScanWindow(scanWindow!!, barcode, imageProxy)) {
                        barcodeMap.add(barcode.data)
                    }
                }

                if (barcodeMap.isEmpty()) {
                    imageProxy.close()
                    return@addOnSuccessListener
                }

                val portrait = (camera?.cameraInfo?.sensorRotationDegrees ?: 0) % 180 == 0

                if (!returnImage) {
                    onBarcodeDetected(
                        barcodeMap,
                        null,
                        if (portrait) inputImage.width else inputImage.height,
                        if (portrait) inputImage.height else inputImage.width
                    )
                    invertedBitmap?.recycle()
                    imageProxy.close()
                    return@addOnSuccessListener
                }

                CoroutineScope(Dispatchers.IO).launch {
                    val baseBitmap = invertedBitmap ?: imageProxy.toBitmap()
                    var rotatedBitmap = rotateBitmap(baseBitmap, camera?.cameraInfo?.sensorRotationDegrees ?: 90)

                    if (invertImage) {
                        val revertedBitmap = invertBitmapColors(rotatedBitmap)
                        rotatedBitmap.recycle()
                        rotatedBitmap = revertedBitmap
                    }

                    if (baseBitmap != rotatedBitmap) {
                        baseBitmap.recycle()
                    }

                    val stream = ByteArrayOutputStream()
                    rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
                    val byteArray = stream.toByteArray()

                    val bmWidth = rotatedBitmap.width
                    val bmHeight = rotatedBitmap.height

                    onBarcodeDetected(
                        barcodeMap,
                        byteArray,
                        bmWidth,
                        bmHeight
                    )

                    rotatedBitmap.recycle()
                    imageProxy.close()
                }
            }.addOnFailureListener { e ->
                onScanError(e.localizedMessage ?: e.toString())
            }
        }

        if (detectionSpeed == DetectionSpeed.NORMAL) {
            Handler(Looper.getMainLooper()).postDelayed({
                scannerTimeout = false
            }, detectionTimeout)
        }
    }

    private fun isBarcodeInScanWindow(
        scanWindow: List<Float>,
        barcode: Barcode,
        inputImage: ImageProxy
    ): Boolean {
        val barcodeBoundingBox = barcode.boundingBox ?: return false

        try {
            val imageWidth = inputImage.height
            val imageHeight = inputImage.width

            val left = (scanWindow[0] * imageWidth).roundToInt()
            val top = (scanWindow[1] * imageHeight).roundToInt()
            val right = (scanWindow[2] * imageWidth).roundToInt()
            val bottom = (scanWindow[3] * imageHeight).roundToInt()

            val scaledScanWindow = Rect(left, top, right, bottom)

            return scaledScanWindow.contains(barcodeBoundingBox)
        } catch (_: IllegalArgumentException) {
            return false
        }
    }
}
