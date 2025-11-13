package com.example.rtedge

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.rtedge.databinding.ActivityMainBinding
import com.example.rtedge.nativebridge.EdgeProcessor
import java.nio.ByteBuffer
import java.text.DecimalFormat
import java.util.Collections

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val cameraManager by lazy { getSystemService(CameraManager::class.java) }
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewSize: Size = Size(1280, 720)
    private var imageReader: android.media.ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private val edgeProcessor = EdgeProcessor()
    private var outputBuffer: ByteBuffer? = null
    private val fpsWindow = ArrayDeque<Long>()
    private val decimalFormat = DecimalFormat("0.0")
    private var showEdges = true

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Log.d(TAG, "Camera permission granted")
            ensureCamera()
        } else {
            Toast.makeText(this, R.string.permission_camera_rationale, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.cameraTexture.surfaceTextureListener = surfaceListener
        binding.modeToggle.setOnClickListener {
            showEdges = !showEdges
            binding.modeToggle.setText(if (showEdges) R.string.label_show_raw else R.string.label_show_edges)
        }

        ensureCamera()
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        binding.glSurface.onResume()
    }

    override fun onPause() {
        binding.glSurface.onPause()
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    override fun onDestroy() {
        edgeProcessor.close()
        super.onDestroy()
    }

    private fun ensureCamera() {
        Log.d(TAG, "ensureCamera")
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> {
                Log.d(TAG, "ensureCamera: Permission granted")
                // Don't open camera here, wait for surface to be available
            }
            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                Log.d(TAG, "ensureCamera: Showing permission rationale")
                AlertDialog.Builder(this)
                    .setMessage(R.string.permission_camera_rationale)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            else -> {
                Log.d(TAG, "ensureCamera: Requesting permission")
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun openCamera(surfaceTexture: SurfaceTexture) {
        Log.d(TAG, "openCamera")
        if (cameraDevice != null) {
            Log.d(TAG, "openCamera: Camera already open")
            return
        }
        try {
            if (backgroundHandler == null) {
                startBackgroundThread()
            }
            val cameraId = chooseCameraId()
            if (cameraId == null) {
                Log.e(TAG, "openCamera: No camera found")
                return
            }
            Log.d(TAG, "openCamera: Camera ID: $cameraId")
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            if (map == null) {
                Log.e(TAG, "openCamera: Stream configuration map is null")
                return
            }
            previewSize = choosePreviewSize(map)
            Log.d(TAG, "openCamera: Preview size: $previewSize")
            surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
            cameraManager.openCamera(cameraId, cameraStateCallback, backgroundHandler)
        } catch (ex: SecurityException) {
            Log.e(TAG, "openCamera: Security exception", ex)
            Toast.makeText(this, R.string.permission_camera_rationale, Toast.LENGTH_LONG).show()
        } catch (ex: CameraAccessException) {
            Log.e(TAG, "openCamera: Camera access exception", ex)
            showCameraError(ex)
        }
    }

    private fun chooseCameraId(): String? {
        return cameraManager.cameraIdList.firstOrNull { id ->
            val characteristics = cameraManager.getCameraCharacteristics(id)
            characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: cameraManager.cameraIdList.firstOrNull()
    }

    private fun choosePreviewSize(map: StreamConfigurationMap): Size {
        val choices = map.getOutputSizes(SurfaceTexture::class.java)
        if (choices.isNullOrEmpty()) {
            Log.w(TAG, "choosePreviewSize: No choices for SurfaceTexture")
            return previewSize
        }
        val desired = choices.firstOrNull { size -> size.width >= 1280 && size.height >= 720 }
        Log.d(TAG, "choosePreviewSize: Desired size: $desired")
        return desired ?: Collections.max(choices.toList(), compareBy { it.width * it.height })
    }

    private fun startCaptureSession(camera: CameraDevice, surfaceTexture: SurfaceTexture) {
        Log.d(TAG, "startCaptureSession")
        try {
            val previewSurface = Surface(surfaceTexture)
            val reader = android.media.ImageReader.newInstance(
                previewSize.width,
                previewSize.height,
                android.graphics.ImageFormat.YUV_420_888,
                3
            )
            imageReader = reader
            reader.setOnImageAvailableListener(onImageAvailableListener, backgroundHandler)

            camera.createCaptureSession(
                listOf(previewSurface, reader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        Log.d(TAG, "startCaptureSession: onConfigured")
                        captureSession = session
                        try {
                            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(previewSurface)
                                addTarget(reader.surface)
                                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                            }
                            session.setRepeatingRequest(request.build(), null, backgroundHandler)
                        } catch (ex: CameraAccessException) {
                            Log.e(TAG, "startCaptureSession: Failed to set repeating request", ex)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "startCaptureSession: onConfigureFailed")
                        Toast.makeText(this@MainActivity, "Camera configuration failed", Toast.LENGTH_SHORT).show()
                    }
                },
                backgroundHandler
            )
        } catch (ex: CameraAccessException) {
            Log.e(TAG, "startCaptureSession: Camera access exception", ex)
            showCameraError(ex)
        }
    }

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            Log.d(TAG, "cameraStateCallback: onOpened")
            cameraDevice = camera
            binding.cameraTexture.surfaceTexture?.let { startCaptureSession(camera, it) }
        }

        override fun onDisconnected(camera: CameraDevice) {
            Log.d(TAG, "cameraStateCallback: onDisconnected")
            camera.close()
            cameraDevice = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            Log.e(TAG, "cameraStateCallback: onError: $error")
            camera.close()
            cameraDevice = null
        }
    }

    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            Log.d(TAG, "surfaceListener: onSurfaceTextureAvailable")
            openCamera(surface)
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            Log.d(TAG, "surfaceListener: onSurfaceTextureDestroyed")
            closeCamera()
            return true
        }
    }

    private val onImageAvailableListener = android.media.ImageReader.OnImageAvailableListener { reader ->
        val image = reader.acquireLatestImage() ?: return@OnImageAvailableListener
        try {
            val nv21 = image.toNv21()
            val width = image.width
            val height = image.height

            val buffer = ensureOutputBuffer(width, height)
            val elapsed = edgeProcessor.processFrame(nv21, width, height, buffer, showEdges)

            runOnUiThread {
                binding.glSurface.submitFrame(buffer.duplicate().apply { rewind() }, width, height)
                updateStats(elapsed, width, height)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to process frame", t)
        } finally {
            image.close()
        }
    }

    private fun ensureOutputBuffer(width: Int, height: Int): ByteBuffer {
        val required = width * height * 4
        val current = outputBuffer
        return if (current == null || current.capacity() != required) {
            val buffer = ByteBuffer.allocateDirect(required).order(java.nio.ByteOrder.nativeOrder())
            outputBuffer = buffer
            buffer
        } else {
            current.clear()
            current
        }
    }

    private fun updateStats(processMs: Float, width: Int, height: Int) {
        val now = System.nanoTime()
        fpsWindow.addLast(now)
        while (fpsWindow.size > 30) {
            fpsWindow.removeFirst()
        }
        val fps = if (fpsWindow.size >= 2) {
            val duration = (fpsWindow.last() - fpsWindow.first()).coerceAtLeast(1L)
            1e9 * (fpsWindow.size - 1) / duration
        } else 0.0
        val text = buildString {
            append("FPS: ").append(decimalFormat.format(fps))
            append("\nProc: ").append(decimalFormat.format(processMs)).append(" ms")
            append("\nRes: ").append(width).append("x").append(height)
            append("\nMode: ").append(if (showEdges) "Edges" else "Raw")
        }
        binding.statsView.text = text
    }

    private fun closeCamera() {
        Log.d(TAG, "closeCamera")
        captureSession?.close()
        captureSession = null
        imageReader?.close()
        imageReader = null
        cameraDevice?.close()
        cameraDevice = null
    }

    private fun startBackgroundThread() {
        Log.d(TAG, "startBackgroundThread")
        if (backgroundThread != null) return
        backgroundThread = HandlerThread("CameraBackground").also {
            it.start()
            backgroundHandler = Handler(it.looper)
        }
    }

    private fun stopBackgroundThread() {
        Log.d(TAG, "stopBackgroundThread")
        backgroundThread?.quitSafely()
        backgroundThread?.join()
        backgroundHandler = null
        backgroundThread = null
    }

    private fun showCameraError(ex: CameraAccessException) {
        Toast.makeText(this, "Camera error: ${ex.message}", Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}

private fun android.media.Image.toNv21(): ByteArray {
    val yPlane = planes[0]
    val uPlane = planes[1]
    val vPlane = planes[2]

    val width = this.width
    val height = this.height
    val ySize = width * height
    val nv21 = ByteArray(ySize + (width * height / 2))

    val yRowStride = yPlane.rowStride
    val yPixelStride = yPlane.pixelStride
    val yBuffer = yPlane.buffer
    var outputOffset = 0

    if (yPixelStride == 1 && yRowStride == width) {
        yBuffer.get(nv21, 0, ySize)
        outputOffset = ySize
    } else {
        val yRow = ByteArray(yRowStride)
        for (row in 0 until height) {
            val bytesPerRow = minOf(yRowStride, yBuffer.remaining())
            yBuffer.position(row * yRowStride)
            yBuffer.get(yRow, 0, bytesPerRow)
            for (col in 0 until width) {
                nv21[outputOffset + col] = yRow[col * yPixelStride]
            }
            outputOffset += width
        }
    }

    val uvRowStride = uPlane.rowStride
    val uvPixelStride = uPlane.pixelStride
    val uBuffer = uPlane.buffer
    val vBuffer = vPlane.buffer
    val uvRow = ByteArray(uvRowStride)
    val vRowStride = vPlane.rowStride
    val vuRow = ByteArray(vRowStride)
    var uvOffset = ySize
    for (row in 0 until height / 2) {
        val uvBytesPerRow = minOf(uvRowStride, uBuffer.remaining())
        val vBytesPerRow = minOf(vRowStride, vBuffer.remaining())
        uBuffer.position(row * uvRowStride)
        vBuffer.position(row * vRowStride)
        uBuffer.get(uvRow, 0, uvBytesPerRow)
        vBuffer.get(vuRow, 0, vBytesPerRow)

        var col = 0
        while (col < width / 2 && uvOffset + 1 < nv21.size) {
            val u = uvRow[col * uvPixelStride]
            val v = vuRow[col * vPlane.pixelStride]
            nv21[uvOffset++] = v
            nv21[uvOffset++] = u
            col++
        }
    }
    return nv21
}
