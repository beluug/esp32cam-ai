package com.demo.bandbridge.camera

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.demo.bandbridge.image.LoadedImage
import com.demo.bandbridge.model.ConnectionState
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.UUID
import org.json.JSONObject

data class CameraFetchResult(
    val ok: Boolean,
    val message: String,
    val image: LoadedImage? = null
)

interface CameraBridge {
    fun refreshConnection(): ConnectionState

    fun requestCapture(callback: (String) -> Unit)

    fun requestPreview(callback: (CameraFetchResult) -> Unit)

    fun requestUpload(callback: (CameraFetchResult) -> Unit)

    fun acknowledgeTimedCaptureArmed(callback: (String) -> Unit)

    fun requestTimedCaptureStart(callback: (String) -> Unit)

    fun requestAnalyzeCommand(callback: (String) -> Unit)

    fun applyCameraConfig(
        frameSize: String,
        jpegQuality: Int,
        callback: (String) -> Unit
    )
}

object CameraBlePermissions {
    fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    fun hasRequiredPermissions(context: Context): Boolean {
        return requiredPermissions().all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }
}

private data class FrameMeta(
    val frameId: Long,
    val totalSize: Int,
    val chunkSize: Int,
    val width: Int,
    val height: Int
)

private data class WifiBoardRegistration(
    val boardIp: String,
    val apIp: String,
    val staIp: String,
    val frameSize: String,
    val jpegQuality: Int,
    val registeredAtMs: Long
)

private data class PendingBleCommand(
    val command: String,
    val callback: (Boolean, String) -> Unit
)

class Esp32BleCameraBridge(
    context: Context,
    private val onLog: (String) -> Unit,
    private val onStateChanged: (ConnectionState) -> Unit,
    private val onImageReceived: (CameraFetchResult) -> Unit,
    private val onAnalyzeRequested: () -> Unit,
    private val onTimedCaptureRequested: () -> Unit
) : CameraBridge {
    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val scanner: BluetoothLeScanner?
        get() = bluetoothAdapter?.bluetoothLeScanner
    private val mainHandler = Handler(Looper.getMainLooper())
    private val formatter = SimpleDateFormat("HHmmss", Locale.getDefault())

    private var currentState = ConnectionState(
        title = "Camera offline",
        detail = "Waiting for Wi-Fi register or BLE scan.",
        connected = false
    )

    private var boardRegistration: WifiBoardRegistration? = null
    private var wifiServerThread: Thread? = null
    private var wifiServerSocket: ServerSocket? = null

    private var isScanning = false
    private var scanCallback: ScanCallback? = null
    private var scanTimeoutRunnable: Runnable? = null
    private var bluetoothGatt: BluetoothGatt? = null

    private var cameraService: BluetoothGattService? = null
    private var controlCharacteristic: BluetoothGattCharacteristic? = null
    private var statusCharacteristic: BluetoothGattCharacteristic? = null
    private var metaCharacteristic: BluetoothGattCharacteristic? = null
    private var chunkCharacteristic: BluetoothGattCharacteristic? = null

    private val notificationQueue = ArrayDeque<BluetoothGattDescriptor>()
    private val pendingReadyCallbacks = mutableListOf<(Boolean, String) -> Unit>()
    private var pendingUploadCallback: ((CameraFetchResult) -> Unit)? = null
    private var pendingFrameMeta: FrameMeta? = null
    private val pendingFrameBytes = ByteArrayOutputStream()
    private var uploadTimeoutRunnable: Runnable? = null

    private val bleCommandQueue = ArrayDeque<PendingBleCommand>()
    private var activeBleCommand: PendingBleCommand? = null
    private var bleWriteInFlight = false

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothGatt.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                discoverBleServices(gatt)
                return
            }

            bluetoothGatt = null
            cameraService = null
            controlCharacteristic = null
            statusCharacteristic = null
            metaCharacteristic = null
            chunkCharacteristic = null
            notificationQueue.clear()
            bleCommandQueue.clear()
            activeBleCommand = null
            bleWriteInFlight = false

            if (currentWifiRegistration() == null) {
                clearPendingUpload("BLE disconnected.")
                updateState(
                    title = "Camera disconnected",
                    detail = "BLE disconnected and Wi-Fi is not registered.",
                    connected = false
                )
            } else {
                updateState(
                    title = "Wi-Fi online",
                    detail = "BLE dropped, but Wi-Fi main path is still online.",
                    connected = true
                )
            }

            flushPendingReady(false, "BLE disconnected.")
            runCatching { gatt.close() }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS || !bindBleCharacteristics(gatt)) {
                updateState(
                    title = "BLE service failed",
                    detail = "Required BLE service was not found on the board.",
                    connected = false
                )
                flushPendingReady(false, "Required BLE service was not found.")
                return
            }
            enableBleNotifications(gatt)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                updateState(
                    title = "BLE notify failed",
                    detail = "Could not subscribe to board notifications.",
                    connected = false
                )
                flushPendingReady(false, "Could not subscribe to board notifications.")
                return
            }
            writeNextDescriptor(gatt)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            when (characteristic.uuid) {
                UUID_STATUS -> handleBleStatus(characteristic.value ?: byteArrayOf())
                UUID_META -> handleBleMeta(characteristic.value ?: byteArrayOf())
                UUID_CHUNK -> handleBleChunk(characteristic.value ?: byteArrayOf())
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (characteristic.uuid != UUID_CONTROL) {
                return
            }

            bleWriteInFlight = false
            val pending = activeBleCommand
            activeBleCommand = null
            if (pending != null) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    pending.callback(true, "BLE command sent: ${pending.command}")
                } else {
                    pending.callback(false, "BLE command failed: ${pending.command}")
                }
            }
            flushBleCommandQueue()
        }
    }

    override fun refreshConnection(): ConnectionState {
        startWifiServerIfNeeded()

        val registration = currentWifiRegistration()
        if (
            registration != null &&
            CameraBlePermissions.hasRequiredPermissions(appContext) &&
            bluetoothAdapter?.isEnabled == true &&
            !isBleReady()
        ) {
            startBleScanIfNeeded(background = true)
        }

        if (registration != null) {
            updateState(
                title = "Camera online via Wi-Fi",
                detail = "Board IP ${registration.boardIp}, frame ${registration.frameSize}, JPEG ${registration.jpegQuality}.",
                connected = true
            )
            return currentState
        }

        if (!CameraBlePermissions.hasRequiredPermissions(appContext)) {
            updateState(
                title = "BLE permission missing",
                detail = "Grant Bluetooth permission first. Wi-Fi local server is already running.",
                connected = false
            )
            return currentState
        }

        if (isBleReady()) {
            updateState(
                title = "BLE backup ready",
                detail = "BLE can control and fetch images when Wi-Fi is unavailable.",
                connected = true
            )
            return currentState
        }

        if (bluetoothAdapter?.isEnabled != true) {
            updateState(
                title = "Waiting for Wi-Fi register",
                detail = "Turn on Bluetooth if you want the BLE backup path.",
                connected = false
            )
            return currentState
        }

        startBleScanIfNeeded(background = false)
        return currentState
    }

    override fun requestCapture(callback: (String) -> Unit) {
        startWifiServerIfNeeded()
        val registration = currentWifiRegistration()
        if (registration != null) {
            sendWifiAction(
                registration = registration,
                type = "capture",
                callback = callback,
                successMessage = "Capture command sent by Wi-Fi.",
                onSuccess = {
                    updateState(
                        title = "Capture requested",
                        detail = "Board is capturing the latest frame.",
                        connected = true
                    )
                }
            )
            return
        }

        ensureBleReady(
            onReady = {
                writeBleCommand(CameraBleProtocol.COMMAND_CAPTURE) { ok, message ->
                    callback(if (ok) "Capture command sent by BLE." else message)
                }
            },
            onFailure = callback
        )
    }

    override fun requestPreview(callback: (CameraFetchResult) -> Unit) {
        val registration = currentWifiRegistration()
        if (registration != null) {
            fetchLatestPreviewOverWifi(registration, callback)
            return
        }
        requestBleImageTransfer(callback)
    }

    override fun requestUpload(callback: (CameraFetchResult) -> Unit) {
        val registration = currentWifiRegistration()
        if (registration != null) {
            prepareUploadWait(callback)
            sendWifiAction(
                registration = registration,
                type = "send",
                callback = { message ->
                    if (message.startsWith("Wi-Fi action failed")) {
                        clearPendingUpload(message)
                    }
                },
                successMessage = "Send command sent by Wi-Fi."
            )
            return
        }
        requestBleImageTransfer(callback)
    }

    override fun acknowledgeTimedCaptureArmed(callback: (String) -> Unit) {
        val registration = currentWifiRegistration()
        if (registration != null) {
            sendWifiAction(
                registration = registration,
                type = "timed_ack",
                callback = callback,
                successMessage = "Timed capture acknowledgment sent by Wi-Fi."
            )
            return
        }

        ensureBleReady(
            onReady = {
                writeBleCommand("TIMEDACK") { ok, message ->
                    callback(if (ok) "Timed capture acknowledgment sent by BLE." else message)
                }
            },
            onFailure = callback
        )
    }

    override fun requestTimedCaptureStart(callback: (String) -> Unit) {
        val registration = currentWifiRegistration()
        if (registration != null) {
            sendWifiAction(
                registration = registration,
                type = "timed_start",
                callback = callback,
                successMessage = "Timed capture start command sent by Wi-Fi."
            )
            return
        }

        ensureBleReady(
            onReady = {
                writeBleCommand(CameraBleProtocol.COMMAND_TIMED_START) { ok, message ->
                    callback(if (ok) "Timed capture start command sent by BLE." else message)
                }
            },
            onFailure = callback
        )
    }

    override fun requestAnalyzeCommand(callback: (String) -> Unit) {
        val registration = currentWifiRegistration()
        if (registration != null) {
            sendWifiAction(
                registration = registration,
                type = "analyze",
                callback = callback,
                successMessage = "Analyze command sent by Wi-Fi."
            )
            return
        }

        ensureBleReady(
            onReady = {
                writeBleCommand(CameraBleProtocol.COMMAND_ANALYZE) { ok, message ->
                    callback(if (ok) "Analyze command sent by BLE." else message)
                }
            },
            onFailure = callback
        )
    }

    override fun applyCameraConfig(
        frameSize: String,
        jpegQuality: Int,
        callback: (String) -> Unit
    ) {
        val safeQuality = jpegQuality.coerceIn(10, 30)
        val registration = currentWifiRegistration()
        if (registration != null) {
            sendWifiAction(
                registration = registration,
                type = "config",
                callback = callback,
                successMessage = "Camera config sent by Wi-Fi: $frameSize / q$safeQuality",
                extraQuery = "frameSize=${encode(frameSize)}&quality=$safeQuality"
            )
            return
        }

        ensureBleReady(
            onReady = {
                val command = CameraBleProtocol.buildConfigCommand(frameSize, safeQuality)
                writeBleCommand(command) { ok, message ->
                    callback(if (ok) "Camera config sent by BLE: $frameSize / q$safeQuality" else message)
                }
            },
            onFailure = callback
        )
    }

    private fun requestBleImageTransfer(callback: (CameraFetchResult) -> Unit) {
        ensureBleReady(
            onReady = {
                prepareUploadWait(callback)
                writeBleCommand(CameraBleProtocol.COMMAND_FETCH_LAST) { ok, message ->
                    if (!ok) {
                        clearPendingUpload(message)
                    }
                }
            },
            onFailure = { message ->
                callback(CameraFetchResult(ok = false, message = message))
            }
        )
    }

    private fun startWifiServerIfNeeded() {
        val running = wifiServerThread?.isAlive == true
        if (running) {
            return
        }

        wifiServerThread = Thread(
            {
                runCatching {
                    ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress(WIFI_SERVER_PORT))
                        wifiServerSocket = this
                    }.use { server ->
                        log("Local Wi-Fi server listening on $WIFI_SERVER_PORT")
                        while (!Thread.currentThread().isInterrupted) {
                            val socket = runCatching { server.accept() }.getOrNull() ?: break
                            Thread {
                                runCatching {
                                    socket.soTimeout = 60_000
                                    handleWifiSocket(socket)
                                }.onFailure {
                                    log("Wi-Fi socket failed: ${it.message}")
                                    runCatching { socket.close() }
                                }
                            }.apply {
                                isDaemon = true
                                start()
                            }
                        }
                    }
                }.onFailure {
                    log("Local Wi-Fi server failed: ${it.message}")
                }
            },
            "camera-wifi-server"
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun handleWifiSocket(socket: Socket) {
        socket.use { client ->
            val input = BufferedInputStream(client.getInputStream())
            val headerBytes = readHeaderBytes(input) ?: run {
                writeHttpResponse(client, 400, "bad_request")
                return
            }
            val headerText = headerBytes.toString(Charsets.ISO_8859_1)
            val headerLines = headerText.split("\r\n")
            val requestLine = headerLines.firstOrNull().orEmpty()
            val requestParts = requestLine.split(" ")
            if (requestParts.size < 2) {
                writeHttpResponse(client, 400, "bad_request")
                return
            }

            val method = requestParts[0].uppercase(Locale.US)
            val path = requestParts[1]
            val headers = linkedMapOf<String, String>()
            for (line in headerLines.drop(1)) {
                if (line.isBlank()) continue
                val separator = line.indexOf(':')
                if (separator <= 0) continue
                val key = line.substring(0, separator).trim().lowercase(Locale.US)
                val value = line.substring(separator + 1).trim()
                headers[key] = value
            }

            val contentLength = headers["content-length"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            val body = if (contentLength > 0) readExactBytes(input, contentLength) else ByteArray(0)

            when {
                method == "POST" && path == "/camera/register" -> {
                    handleWifiRegister(client, body.toString(Charsets.UTF_8))
                }

                method == "POST" && path == "/camera/upload" -> {
                    handleWifiUpload(client, headers, body)
                }

                method == "POST" && path == "/camera/analyze" -> {
                    handleWifiAnalyze(client)
                }

                method == "POST" && path == "/camera/timed-capture" -> {
                    handleWifiTimedCapture(client)
                }

                else -> writeHttpResponse(client, 404, "not_found")
            }
        }
    }

    private fun handleWifiRegister(socket: Socket, body: String) {
        val json = runCatching { JSONObject(body) }.getOrNull()
        if (json == null) {
            writeHttpResponse(socket, 400, "invalid_json")
            return
        }

        val boardIp = json.optString("board_ip").ifBlank {
            json.optString("sta_ip").ifBlank { socket.inetAddress.hostAddress.orEmpty() }
        }
        val apIp = json.optString("ap_ip")
        val staIp = json.optString("sta_ip").ifBlank { boardIp }
        val frameSize = json.optString("frame_size").ifBlank { "XGA" }
        val jpegQuality = json.optInt("jpeg_quality", 12)

        boardRegistration = WifiBoardRegistration(
            boardIp = boardIp,
            apIp = apIp,
            staIp = staIp,
            frameSize = frameSize,
            jpegQuality = jpegQuality,
            registeredAtMs = SystemClock.elapsedRealtime()
        )

        updateState(
            title = "Camera online via Wi-Fi",
            detail = "Board IP $boardIp registered to phone server.",
            connected = true
        )
        log("Wi-Fi register from board: $boardIp / $frameSize / q$jpegQuality")
        writeHttpResponse(socket, 200, "ok")
    }

    private fun handleWifiUpload(
        socket: Socket,
        headers: Map<String, String>,
        body: ByteArray
    ) {
        val width = headers["x-image-width"]?.toIntOrNull() ?: 0
        val height = headers["x-image-height"]?.toIntOrNull() ?: 0
        val frameId = headers["x-frame-id"]?.toLongOrNull() ?: System.currentTimeMillis()
        val image = LoadedImage(
            label = "camera_wifi_${formatter.format(Date())}_$frameId.jpg",
            mimeType = "image/jpeg",
            bytes = body
        )
        val result = CameraFetchResult(
            ok = true,
            message = "Image received via Wi-Fi (${body.size} bytes, ${width}x$height).",
            image = image
        )
        log("Wi-Fi upload received: ${body.size} bytes, ${width}x$height, frame=$frameId")

        boardRegistration = currentWifiRegistration()?.copy(
            registeredAtMs = SystemClock.elapsedRealtime()
        ) ?: boardRegistration

        writeHttpResponse(socket, 200, "ok")
        consumeIncomingImage(result)
    }

    private fun handleWifiAnalyze(socket: Socket) {
        boardRegistration = currentWifiRegistration()?.copy(
            registeredAtMs = SystemClock.elapsedRealtime()
        ) ?: boardRegistration
        log("Wi-Fi analyze request received from board.")
        writeHttpResponse(socket, 200, "ok")
        mainHandler.post { onAnalyzeRequested() }
    }

    private fun handleWifiTimedCapture(socket: Socket) {
        boardRegistration = currentWifiRegistration()?.copy(
            registeredAtMs = SystemClock.elapsedRealtime()
        ) ?: boardRegistration
        log("Wi-Fi timed capture request received from board.")
        writeHttpResponse(socket, 200, "ok")
        mainHandler.post { onTimedCaptureRequested() }
    }

    private fun fetchLatestPreviewOverWifi(
        registration: WifiBoardRegistration,
        callback: (CameraFetchResult) -> Unit
    ) {
        Thread {
            val result = runCatching {
                val connection = (URL("http://${registration.boardIp}/last.jpg?t=${System.currentTimeMillis()}").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 45_000
                    doInput = true
                }
                connection.inputStream.use { stream ->
                    val bytes = stream.readBytes()
                    CameraFetchResult(
                        ok = true,
                        message = "Preview loaded via Wi-Fi (${bytes.size} bytes).",
                        image = LoadedImage(
                            label = "camera_preview_${formatter.format(Date())}.jpg",
                            mimeType = "image/jpeg",
                            bytes = bytes
                        )
                    )
                }
            }.getOrElse { error ->
                CameraFetchResult(
                    ok = false,
                    message = "Wi-Fi preview failed: ${error.message}"
                )
            }
            mainHandler.post { callback(result) }
        }.start()
    }

    private fun currentWifiRegistration(): WifiBoardRegistration? {
        val registration = boardRegistration ?: return null
        val alive = SystemClock.elapsedRealtime() - registration.registeredAtMs <= WIFI_REGISTRATION_TTL_MS
        if (!alive) {
            boardRegistration = null
            if (!isBleReady()) {
                updateState(
                    title = "Wi-Fi registration expired",
                    detail = "Waiting for board to register again or BLE to reconnect.",
                    connected = false
                )
            }
            return null
        }
        return registration
    }

    private fun sendWifiAction(
        registration: WifiBoardRegistration,
        type: String,
        callback: (String) -> Unit,
        successMessage: String,
        extraQuery: String = "",
        onSuccess: (() -> Unit)? = null
    ) {
        Thread {
            val result = runCatching {
                val queryTail = buildString {
                    append("?type=")
                    append(encode(type))
                    if (extraQuery.isNotBlank()) {
                        append('&')
                        append(extraQuery)
                    }
                }
                val connection = (URL("http://${registration.boardIp}/action$queryTail").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 20_000
                    doInput = true
                }
                connection.inputStream.use { it.readBytes() }
                onSuccess?.invoke()
                successMessage
            }.getOrElse { error ->
                log("Wi-Fi action failed: ${error.message}")
                boardRegistration = null
                "Wi-Fi action failed: ${error.message}"
            }
            mainHandler.post { callback(result) }
        }.start()
    }

    private fun updateState(title: String, detail: String, connected: Boolean) {
        currentState = ConnectionState(title = title, detail = detail, connected = connected)
        mainHandler.post { onStateChanged(currentState) }
    }

    private fun log(message: String) {
        mainHandler.post { onLog(message) }
    }

    private fun isBleReady(): Boolean {
        return bluetoothGatt != null &&
            cameraService != null &&
            controlCharacteristic != null &&
            statusCharacteristic != null &&
            metaCharacteristic != null &&
            chunkCharacteristic != null
    }

    private fun ensureBleReady(
        onReady: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        if (!CameraBlePermissions.hasRequiredPermissions(appContext)) {
            onFailure("BLE permission missing.")
            return
        }

        if (bluetoothAdapter?.isEnabled != true) {
            onFailure("Bluetooth is off.")
            return
        }

        if (isBleReady()) {
            onReady()
            return
        }

        pendingReadyCallbacks.add { ok, message ->
            if (ok) onReady() else onFailure(message)
        }
        startBleScanIfNeeded(background = false)
    }

    private fun flushPendingReady(ok: Boolean, message: String) {
        val callbacks = pendingReadyCallbacks.toList()
        pendingReadyCallbacks.clear()
        callbacks.forEach { it(ok, message) }
    }

    private fun startBleScanIfNeeded(background: Boolean) {
        if (isScanning || isBleReady()) {
            return
        }
        if (!CameraBlePermissions.hasRequiredPermissions(appContext) || bluetoothAdapter?.isEnabled != true) {
            return
        }

        val bleScanner = scanner ?: run {
            if (!background) {
                updateState("BLE unavailable", "BLE scanner is not available on this phone.", false)
            }
            flushPendingReady(false, "BLE scanner is not available.")
            return
        }

        val previousState = currentState
        if (!background) {
            updateState(
                title = "Scanning BLE",
                detail = "Looking for BandBridgeCam BLE backup link.",
                connected = false
            )
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (!matchesTargetBoard(result)) {
                    return
                }
                stopBleScanInternal()
                connectBleGatt(result.device)
            }

            override fun onScanFailed(errorCode: Int) {
                stopBleScanInternal()
                updateState("BLE scan failed", "Android BLE scan failed: $errorCode", false)
                flushPendingReady(false, "Android BLE scan failed: $errorCode")
            }
        }

        scanCallback = callback
        isScanning = true
        bleScanner.startScan(
            listOf(android.bluetooth.le.ScanFilter.Builder().setServiceUuid(ParcelUuid(UUID_SERVICE)).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            callback
        )
        log("BLE scan started.")

        scanTimeoutRunnable = Runnable {
            stopBleScanInternal()
            if (currentWifiRegistration() == null) {
                updateState(
                    title = "BLE device not found",
                    detail = "Could not find BandBridgeCam over BLE.",
                    connected = false
                )
            } else if (background) {
                updateState(previousState.title, previousState.detail, previousState.connected)
            }
            flushPendingReady(false, "Could not find BandBridgeCam over BLE.")
        }
        mainHandler.postDelayed(scanTimeoutRunnable!!, BLE_SCAN_TIMEOUT_MS)
    }

    private fun stopBleScanInternal() {
        scanTimeoutRunnable?.let(mainHandler::removeCallbacks)
        scanTimeoutRunnable = null

        val callback = scanCallback
        val bleScanner = scanner
        if (callback != null && bleScanner != null) {
            runCatching { bleScanner.stopScan(callback) }
        }
        scanCallback = null
        isScanning = false
    }

    @SuppressLint("MissingPermission")
    private fun connectBleGatt(device: BluetoothDevice) {
        runCatching { bluetoothGatt?.close() }
        bluetoothGatt = null

        updateState(
            title = "Connecting BLE",
            detail = "Connecting to ${device.name ?: device.address}.",
            connected = false
        )
        bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(appContext, false, gattCallback)
        }
        log("BLE connecting to ${device.address}")
    }

    @SuppressLint("MissingPermission")
    private fun discoverBleServices(gatt: BluetoothGatt) {
        runCatching { gatt.discoverServices() }.onFailure {
            flushPendingReady(false, "BLE service discovery failed: ${it.message}")
        }
    }

    private fun bindBleCharacteristics(gatt: BluetoothGatt): Boolean {
        val service = gatt.getService(UUID_SERVICE) ?: return false
        val control = service.getCharacteristic(UUID_CONTROL) ?: return false
        val status = service.getCharacteristic(UUID_STATUS) ?: return false
        val meta = service.getCharacteristic(UUID_META) ?: return false
        val chunk = service.getCharacteristic(UUID_CHUNK) ?: return false

        cameraService = service
        controlCharacteristic = control
        statusCharacteristic = status
        metaCharacteristic = meta
        chunkCharacteristic = chunk
        return true
    }

    @SuppressLint("MissingPermission")
    private fun enableBleNotifications(gatt: BluetoothGatt) {
        notificationQueue.clear()
        listOfNotNull(statusCharacteristic, metaCharacteristic, chunkCharacteristic).forEach { characteristic ->
            gatt.setCharacteristicNotification(characteristic, true)
            characteristic.getDescriptor(CLIENT_CONFIG_UUID)?.let { descriptor ->
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                notificationQueue.addLast(descriptor)
            }
        }
        writeNextDescriptor(gatt)
    }

    @SuppressLint("MissingPermission")
    private fun writeNextDescriptor(gatt: BluetoothGatt) {
        val descriptor = notificationQueue.pollFirst()
        if (descriptor == null) {
            updateState(
                title = "BLE backup ready",
                detail = "BLE control and image fallback are ready.",
                connected = true
            )
            flushPendingReady(true, "BLE backup is ready.")
            flushBleCommandQueue()
            return
        }
        val ok = gatt.writeDescriptor(descriptor)
        if (!ok) {
            updateState(
                title = "BLE notify failed",
                detail = "Could not subscribe to board notifications.",
                connected = false
            )
            flushPendingReady(false, "Could not subscribe to board notifications.")
        }
    }

    private fun writeBleCommand(
        command: String,
        callback: (Boolean, String) -> Unit
    ) {
        if (!isBleReady()) {
            callback(false, "BLE is not ready.")
            return
        }
        bleCommandQueue.addLast(PendingBleCommand(command, callback))
        flushBleCommandQueue()
    }

    @SuppressLint("MissingPermission")
    private fun flushBleCommandQueue() {
        if (bleWriteInFlight) {
            return
        }
        if (activeBleCommand != null) {
            return
        }

        val next = bleCommandQueue.pollFirst() ?: return
        val characteristic = controlCharacteristic
        val gatt = bluetoothGatt
        if (characteristic == null || gatt == null) {
            next.callback(false, "BLE control channel is not ready.")
            return
        }

        activeBleCommand = next
        characteristic.value = next.command.toByteArray(Charsets.UTF_8)
        bleWriteInFlight = true
        val ok = gatt.writeCharacteristic(characteristic)
        if (!ok) {
            bleWriteInFlight = false
            activeBleCommand = null
            next.callback(false, "BLE write failed for command: ${next.command}")
            flushBleCommandQueue()
        }
    }

    private fun handleBleStatus(bytes: ByteArray) {
        val status = bytes.toString(Charsets.UTF_8).trim()
        if (status.isEmpty()) {
            return
        }

        log("[status] $status")
        when {
            status == "phone_connected" -> {
                updateState("BLE backup ready", "Phone and board are connected over BLE.", true)
                flushPendingReady(true, "BLE backup is ready.")
            }

            status == "sta_connected" -> {
                updateState("Wi-Fi station ready", "Board joined the phone hotspot.", true)
            }

            status == "phone_server_ready" -> {
                val registration = currentWifiRegistration()
                if (registration != null) {
                    updateState(
                        "Camera online via Wi-Fi",
                        "Board IP ${registration.boardIp} is ready for Wi-Fi control.",
                        true
                    )
                }
            }

            status.startsWith("config_applied_") -> {
                updateState("Camera config applied", status, true)
            }

            status == "capturing" -> {
                updateState("Capturing", "Board is capturing a fresh frame.", true)
            }

            status == "image_ready" -> {
                updateState("Image ready", "Board cached a fresh JPEG frame.", true)
            }

            status.startsWith("sending_") -> {
                updateState("Sending image", status, true)
            }

            status == "wifi_send_failed" -> {
                updateState("Wi-Fi send failed", "Board will fall back to BLE chunk transfer.", true)
            }

            status == "analyze_requested" -> {
                updateState("Analyze requested", "Board asked the phone to analyze the current queue.", true)
                mainHandler.post { onAnalyzeRequested() }
            }

            status == "timed_capture_requested" || status == "timed_capture_requested_ble" -> {
                updateState("Timed capture requested", "Board asked the phone to arm timed capture mode.", true)
                mainHandler.post { onTimedCaptureRequested() }
            }

            status == "frame_sent" || status == "frame_sent_wifi" -> {
                updateState("Image sent", status, true)
            }
        }
    }

    private fun handleBleMeta(bytes: ByteArray) {
        val meta = parseMeta(bytes) ?: return
        pendingFrameMeta = meta
        pendingFrameBytes.reset()
        prepareUploadWaitIfMissing()
        log("BLE meta: frame=${meta.frameId} bytes=${meta.totalSize} chunk=${meta.chunkSize}")
    }

    private fun handleBleChunk(bytes: ByteArray) {
        val meta = pendingFrameMeta ?: return
        pendingFrameBytes.write(bytes)
        if (pendingFrameBytes.size() < meta.totalSize) {
            return
        }

        val payload = pendingFrameBytes.toByteArray().copyOf(meta.totalSize)
        pendingFrameMeta = null
        pendingFrameBytes.reset()

        val result = CameraFetchResult(
            ok = true,
            message = "Image received via BLE (${payload.size} bytes, ${meta.width}x${meta.height}).",
            image = LoadedImage(
                label = "camera_ble_${formatter.format(Date())}_${meta.frameId}.jpg",
                mimeType = "image/jpeg",
                bytes = payload
            )
        )
        consumeIncomingImage(result)
    }

    private fun parseMeta(bytes: ByteArray): FrameMeta? {
        if (bytes.size < 16) {
            return null
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FrameMeta(
            frameId = buffer.int.toLong() and 0xffffffffL,
            totalSize = buffer.int,
            chunkSize = buffer.short.toInt() and 0xffff,
            width = buffer.short.toInt() and 0xffff,
            height = buffer.short.toInt() and 0xffff
        )
    }

    private fun prepareUploadWait(callback: (CameraFetchResult) -> Unit) {
        pendingUploadCallback = callback
        pendingFrameMeta = null
        pendingFrameBytes.reset()
        uploadTimeoutRunnable?.let(mainHandler::removeCallbacks)
        uploadTimeoutRunnable = Runnable {
            clearPendingUpload("Timed out while waiting for image data.")
        }
        mainHandler.postDelayed(uploadTimeoutRunnable!!, IMAGE_TRANSFER_TIMEOUT_MS)
    }

    private fun prepareUploadWaitIfMissing() {
        if (pendingUploadCallback == null) {
            prepareUploadWait(onImageReceived)
        }
    }

    private fun clearPendingUpload(message: String) {
        uploadTimeoutRunnable?.let(mainHandler::removeCallbacks)
        uploadTimeoutRunnable = null
        pendingFrameMeta = null
        pendingFrameBytes.reset()
        val callback = pendingUploadCallback
        pendingUploadCallback = null
        if (callback != null) {
            mainHandler.post {
                callback(
                    CameraFetchResult(
                        ok = false,
                        message = message
                    )
                )
            }
        }
    }

    private fun consumeIncomingImage(result: CameraFetchResult) {
        uploadTimeoutRunnable?.let(mainHandler::removeCallbacks)
        uploadTimeoutRunnable = null
        pendingFrameMeta = null
        pendingFrameBytes.reset()

        val callback = pendingUploadCallback
        pendingUploadCallback = null
        if (callback != null) {
            mainHandler.post { callback(result) }
        } else {
            mainHandler.post { onImageReceived(result) }
        }
    }

    private fun readHeaderBytes(input: BufferedInputStream): ByteArray? {
        val buffer = ByteArrayOutputStream()
        var matched = 0
        while (buffer.size() < MAX_HEADER_BYTES) {
            val value = input.read()
            if (value < 0) break
            buffer.write(value)
            matched = when {
                matched == 0 && value == '\r'.code -> 1
                matched == 1 && value == '\n'.code -> 2
                matched == 2 && value == '\r'.code -> 3
                matched == 3 && value == '\n'.code -> 4
                value == '\r'.code -> 1
                else -> 0
            }
            if (matched == 4) {
                return buffer.toByteArray()
            }
        }
        return null
    }

    private fun readExactBytes(input: BufferedInputStream, length: Int): ByteArray {
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(bytes, offset, length - offset)
            if (count <= 0) break
            offset += count
        }
        return if (offset == length) bytes else bytes.copyOf(offset)
    }

    private fun writeHttpResponse(socket: Socket, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val status = when (code) {
            200 -> "200 OK"
            400 -> "400 Bad Request"
            404 -> "404 Not Found"
            else -> "$code Error"
        }
        val output = socket.getOutputStream()
        output.write("HTTP/1.1 $status\r\n".toByteArray(Charsets.US_ASCII))
        output.write("Content-Type: text/plain; charset=utf-8\r\n".toByteArray(Charsets.US_ASCII))
        output.write("Content-Length: ${bytes.size}\r\n".toByteArray(Charsets.US_ASCII))
        output.write("Connection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    private fun encode(value: String): String {
        return URLEncoder.encode(value, Charsets.UTF_8.name())
    }

    private fun matchesTargetBoard(result: ScanResult): Boolean {
        val name = result.device.name ?: result.scanRecord?.deviceName.orEmpty()
        if (name.contains(BOARD_NAME, ignoreCase = true)) {
            return true
        }
        return result.scanRecord?.serviceUuids?.any { it.uuid == UUID_SERVICE } == true
    }

    companion object {
        private const val WIFI_SERVER_PORT = 8787
        private const val WIFI_REGISTRATION_TTL_MS = 45_000L
        private const val BLE_SCAN_TIMEOUT_MS = 12_000L
        private const val IMAGE_TRANSFER_TIMEOUT_MS = 45_000L
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val BOARD_NAME = "BandBridgeCam"

        private val UUID_SERVICE = UUID.fromString(CameraBleProtocol.SERVICE_UUID)
        private val UUID_CONTROL = UUID.fromString(CameraBleProtocol.CONTROL_CHAR_UUID)
        private val UUID_STATUS = UUID.fromString(CameraBleProtocol.STATUS_CHAR_UUID)
        private val UUID_META = UUID.fromString(CameraBleProtocol.META_CHAR_UUID)
        private val UUID_CHUNK = UUID.fromString(CameraBleProtocol.CHUNK_CHAR_UUID)
        private val CLIENT_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
