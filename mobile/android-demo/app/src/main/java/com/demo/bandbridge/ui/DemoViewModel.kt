package com.demo.bandbridge.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.demo.bandbridge.ai.AiAnalyzeResult
import com.demo.bandbridge.ai.AiAnalyzer
import com.demo.bandbridge.bridge.WearableBandBridge
import com.demo.bandbridge.camera.CameraBlePermissions
import com.demo.bandbridge.camera.CameraBridge
import com.demo.bandbridge.camera.CameraFetchResult
import com.demo.bandbridge.camera.Esp32BleCameraBridge
import com.demo.bandbridge.image.ImageRepository
import com.demo.bandbridge.image.LoadedImage
import com.demo.bandbridge.model.AiProvider
import com.demo.bandbridge.model.AiRoute
import com.demo.bandbridge.model.AiRunConfig
import com.demo.bandbridge.model.AppLog
import com.demo.bandbridge.model.AppSettings
import com.demo.bandbridge.model.ConnectionState
import com.demo.bandbridge.model.GestureState
import com.demo.bandbridge.model.PhoneMessage
import com.demo.bandbridge.model.ProviderEndpoint
import com.demo.bandbridge.model.SelectedImageState
import com.demo.bandbridge.model.SendHistoryItem
import com.demo.bandbridge.model.defaultEndpoints
import com.demo.bandbridge.model.defaultPriorityRoutes
import com.demo.bandbridge.service.KeepAliveService
import com.demo.bandbridge.storage.AppSettingsStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class DemoTab(val label: String) {
    HOME("首页"),
    HISTORY("历史"),
    SETTINGS("设置"),
    DEBUG("调试")
}

class DemoViewModel(application: Application) : AndroidViewModel(application) {
    private val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val cameraFrameSizes = listOf("SVGA", "XGA", "UXGA")
    private val autoRefreshIntervalMs = 5000L
    private val bandBridge by lazy(LazyThreadSafetyMode.NONE) {
        WearableBandBridge(
            context = application,
            onLog = { addLog("手环", it) },
            onCommand = { message ->
                viewModelScope.launch {
                    handleBandCommand(message)
                }
            }
        )
    }
    private val imageRepository = ImageRepository(application)
    private val aiAnalyzer = AiAnalyzer()
    private val settingsStore = AppSettingsStore(application)
    private val selectedImageUris = mutableStateListOf<Uri>()
    private val boardImageQueue = mutableStateListOf<LoadedImage>()
    private var activeAnalyzeJob: Job? = null
    private var autoRefreshJob: Job? = null

    private var lastAnalysisSourceKey: String? = null
    private var lastSuccessfulRouteIndex: Int? = null
    private var latestBoardImage: LoadedImage? = null
    private var pendingQueueClearOnNextCapture = false
    private var timedBatchActive = false
    private var timedBatchExpectedCount = 0
    private var lastTimedCaptureRequestMs = 0L
    private var lastBandLiveStatusContent = ""
    private var lastBandLiveStatusAt = 0L

    private val cameraBridge: CameraBridge by lazy(LazyThreadSafetyMode.NONE) {
        Esp32BleCameraBridge(
            context = application,
            onLog = { message ->
                viewModelScope.launch {
                    handleCameraLog(message)
                }
            },
            onStateChanged = { cameraState = it },
            onImageReceived = { result ->
                viewModelScope.launch {
                    handleCameraUploadResult(
                        result = result,
                        analyzeAfterAppend = false,
                        triggeredByBoardButton = false
                    )
                }
            },
            onAnalyzeRequested = {
                viewModelScope.launch {
                    handleBoardAnalyzeRequest()
                }
            },
            onTimedCaptureRequested = {
                viewModelScope.launch {
                    handleTimedCaptureRequest()
                }
            }
        )
    }

    var currentTab by mutableStateOf(DemoTab.HOME)
    var customBandText by mutableStateOf("")
    var isSending by mutableStateOf(false)
    var isAnalyzing by mutableStateOf(false)
    var latestResult by mutableStateOf("还没有分析结果。")
    var latestOcr by mutableStateOf("当前方案不再单独显示本地 OCR 结果。")
    var aiTestStatus by mutableStateOf("AI 测试尚未开始。")
    var settings by mutableStateOf(settingsStore.load().normalized())
    var selectedImageState by mutableStateOf(SelectedImageState())
    var latestPreviewImage by mutableStateOf<LoadedImage?>(null)

    var cameraState by mutableStateOf(
        ConnectionState(
            title = "相机模块未连接",
            detail = "等待发现 BandBridgeCam 相机设备。",
            connected = false
        )
    )
    var bandState by mutableStateOf(
        ConnectionState(
            title = "正在检查手环连接",
            detail = "当前可以向手环发送文本。",
            connected = false
        )
    )
    var keepAliveState by mutableStateOf(
        ConnectionState(
            title = "后台保活未确认",
            detail = "应用启动后会自动拉起前台服务。",
            connected = false
        )
    )
    var gestureState by mutableStateOf(
        GestureState(
            title = "控制规则",
            detail = "GPIO14 负责拍照并发图到手机，GPIO21 只负责请求分析。"
        )
    )

    val history = mutableStateListOf<SendHistoryItem>()
    val logs = mutableStateListOf<AppLog>()

    init {
        runCatching {
            refreshCameraConnection(logChange = true)
            refreshBandConnection()
            refreshKeepAliveState()
            startAutoRefreshLoop()
        }.onFailure {
            latestResult = "启动时已跳过部分自动检查，请进入首页后手动刷新设备状态。"
        }
    }

    fun refreshAllStates() {
        runCatching { refreshCameraConnection() }
            .onFailure { addLog("相机", "自动检查失败: ${it.message}") }
        runCatching { refreshBandConnection() }
            .onFailure { addLog("手环", "自动检查失败: ${it.message}") }
        runCatching { refreshKeepAliveState() }
            .onFailure { addLog("保活", "自动检查失败: ${it.message}") }
    }

    fun onLocalImagesSelected(uris: List<Uri>) {
        selectedImageUris.clear()
        selectedImageUris.addAll(uris.distinct())
        boardImageQueue.clear()
        latestPreviewImage = null
        refreshSelectedImageState()
        latestBoardImage = null
        lastAnalysisSourceKey = null
        lastSuccessfulRouteIndex = null
        pendingQueueClearOnNextCapture = false
        timedBatchActive = false
        timedBatchExpectedCount = 0

        if (selectedImageUris.isNotEmpty()) {
            latestResult = "已选择 ${selectedImageUris.size} 张本地测试图片，可以直接上传分析。"
            addLog("图片", latestResult)
        }
    }

    fun refreshCameraConnection(logChange: Boolean = true) {
        val previous = cameraState
        cameraState = cameraBridge.refreshConnection()
        if (logChange || previous != cameraState) {
            addLog("相机", cameraState.detail)
        }
    }

    fun refreshBandConnection() {
        bandState = bandState.copy(
            title = "正在检查手环连接",
            detail = "正在查询当前已连接的穿戴设备。"
        )
        addLog("手环", "开始检查手环连接状态")
        bandBridge.refreshConnection { state ->
            bandState = state
            addLog("手环", "${state.title} | ${state.detail}")
        }
    }

    fun refreshKeepAliveState() {
        KeepAliveService.start(getApplication())
        keepAliveState = if (KeepAliveService.isRunning) {
            ConnectionState(
                title = "后台保活运行中",
                detail = "前台服务已启动，手机端会尽量保持在线。",
                connected = true
            )
        } else {
            ConnectionState(
                title = "后台保活未运行",
                detail = "如果系统限制后台，请手动允许自启动和后台运行。",
                connected = false
            )
        }
        addLog("保活", keepAliveState.detail)
    }

    fun updateCustomBandText(value: String) {
        customBandText = value
    }

    fun pushCustomTextToBand() {
        val content = customBandText.trim()
        if (content.isEmpty() || isSending) return

        isSending = true
        latestResult = "姝ｅ湪鍙戦€佸埌鎵嬬幆..."
        addLog("发送", "准备发送自定义文本")

        val timeLabel = formatter.format(Date())
        val message = PhoneMessage(
            type = "text_result",
            title = timeLabel,
            content = content
        )

        bandBridge.send(message) { result ->
            isSending = false
            latestResult = result.message
            if (result.ok) {
                history.add(
                    0,
                    SendHistoryItem(
                        timeLabel = timeLabel,
                        title = "自定义文本",
                        content = content,
                        status = "发送成功"
                    )
                )
                customBandText = ""
            }
            addLog("发送", "${if (result.ok) "成功" else "失败"} | ${result.message}")
            refreshBandConnection()
        }
    }

    fun clickCapture() {
        if (isAnalyzing) {
            activeAnalyzeJob?.cancel(CancellationException("capture_requested"))
            latestResult = "已触发拍照，当前分析已中断。"
            addLog("分析", "检测到拍照请求，已中断当前分析流程")
        }
        isAnalyzing = false
        activeAnalyzeJob = null
        lastAnalysisSourceKey = null
        lastSuccessfulRouteIndex = null
        clearCurrentImageQueueForNextCaptureIfNeeded("手机拍照")
        sendLiveStatusToBand(
            title = "手机拍照",
            content = "手机已触发拍照，请等待新图片回传。"
        )

        applyCameraConfigThen {
            cameraBridge.requestCapture { message ->
            viewModelScope.launch {
                latestResult = message
                addLog("相机", message)
                refreshCameraConnection()
                if (message.contains("拍照", ignoreCase = true) || message.contains("capture", ignoreCase = true)) {
                    delay(1200)
                    cameraBridge.requestPreview { result ->
                        viewModelScope.launch {
                            handleCameraPreviewResult(result)
                        }
                    }
                }
            }
            }
        }
    }

    fun clickUpload() {
        if (isAnalyzing) return
        sendLiveStatusToBand(
            title = "开始分析",
            content = "手机已触发分析，请等待分析结果。"
        )

        if (selectedImageUris.isNotEmpty()) {
            viewModelScope.launch {
                val loadedImages = runCatching { imageRepository.loadAll(selectedImageUris) }.getOrElse { error ->
                    latestResult = "读取图片失败: ${error.message}"
                    addLog("分析", latestResult)
                    return@launch
                }
                latestBoardImage = null
                startAnalyzeFlow(
                    loadedImages = loadedImages,
                    historyTitle = "鍥剧墖鍒嗘瀽",
                    sourceKey = selectedImageUris.joinToString("|") { it.toString() },
                    triggeredByBoardButton = false
                )
            }
            return
        }

        latestResult = "正在从相机板取图..."
        addLog("相机", latestResult)
        if (boardImageQueue.isNotEmpty()) {
            viewModelScope.launch {
                startAnalyzeFlow(
                    loadedImages = boardImageQueue.toList(),
                    historyTitle = "鐩告満鍥剧墖闃熷垪鍒嗘瀽",
                    sourceKey = boardImageQueue.joinToString("|") { it.label },
                    triggeredByBoardButton = false
                )
            }
            return
        }

        cameraBridge.requestUpload { result ->
            viewModelScope.launch {
                handleCameraUploadResult(
                    result = result,
                    analyzeAfterAppend = true,
                    triggeredByBoardButton = false
                )
            }
        }
    }

    fun clickTest() {
        refreshAllStates()
        latestResult = if (CameraBlePermissions.hasRequiredPermissions(getApplication())) {
            "已刷新相机、手环和后台保活状态。"
        } else {
            "请先允许蓝牙权限，再刷新相机状态。"
        }
        addLog("系统", latestResult)
    }

    fun addCustomEndpointSlot() {
        updateSettings { current ->
            current.copy(
                customEndpoints = current.customEndpoints + ProviderEndpoint()
            )
        }
    }

    fun removeCurrentEndpointSlot() {
        if (settings.customEndpoints.size <= 1) return
        val removeIndex = (settings.editSlot - 1).coerceIn(0, settings.customEndpoints.lastIndex)
        updateSettings { current ->
            val updatedEndpoints = current.customEndpoints.toMutableList().apply { removeAt(removeIndex) }
            val updatedRoutes = current.priorityRoutes.map { route ->
                when {
                    !route.enabled -> route
                    route.apiSlot == removeIndex + 1 -> AiRoute()
                    route.apiSlot > removeIndex + 1 -> route.copy(apiSlot = route.apiSlot - 1)
                    else -> route
                }
            }
            current.copy(
                editSlot = current.editSlot.coerceAtMost(updatedEndpoints.size).coerceAtLeast(1),
                customEndpoints = updatedEndpoints,
                priorityRoutes = updatedRoutes
            )
        }
    }

    fun updateEditSlot(slot: Int) {
        updateSettings {
            it.copy(editSlot = slot.coerceIn(1, it.customEndpoints.size.coerceAtLeast(1)))
        }
    }

    fun updatePrompt(value: String) {
        updateSettings { it.copy(prompt = value) }
    }

    fun updateDelay(value: String) {
        updateSettings { it.copy(delayMs = value) }
    }

    fun cameraFrameSizeOptions(): List<String> = cameraFrameSizes

    fun currentCameraFrameSize(): String = settings.cameraFrameSize.ifBlank { "XGA" }

    fun currentCameraJpegQuality(): String = settings.cameraJpegQuality

    fun updateCameraFrameSize(value: String) {
        updateSettings { current ->
            current.copy(cameraFrameSize = value.uppercase(Locale.US))
        }
    }

    fun updateCameraJpegQuality(value: String) {
        updateSettings { current ->
            current.copy(cameraJpegQuality = value.filter { it.isDigit() }.take(2))
        }
    }

    fun applyCameraConfig() {
        applyCameraConfigThen {}
    }

    fun cameraConfigSummary(): String {
        val quality = settings.cameraJpegQuality.ifBlank { "12" }
        return "??? ${currentCameraFrameSize()} / JPEG??? $quality?????????"
    }

    fun currentEditableSlots(): List<Int> {
        return (1..settings.customEndpoints.size.coerceAtLeast(1)).toList()
    }

    fun currentEditingLabel(): String = "鑷畾涔?AI / API ${settings.editSlot}"

    fun currentEditingApiKey(): String = currentEditingEndpoint().apiKey

    fun currentEditingModel(): String = currentEditingEndpoint().model

    fun currentEditingBaseUrl(): String = currentEditingEndpoint().baseUrl

    fun updateCurrentEditingApiKey(value: String) {
        updateEndpoint(settings.editSlot) { it.copy(apiKey = value) }
    }

    fun updateCurrentEditingModel(value: String) {
        updateEndpoint(settings.editSlot) { it.copy(model = value) }
    }

    fun updateCurrentEditingBaseUrl(value: String) {
        updateEndpoint(settings.editSlot) { it.copy(baseUrl = value) }
    }

    fun priorityRoute(index: Int): AiRoute {
        return settings.priorityRoutes
            .normalizedRoutes(settings.customEndpoints.size.coerceAtLeast(1))
            .getOrElse(index) { AiRoute() }
    }

    fun priorityRouteSlot(index: Int): Int = priorityRoute(index).apiSlot

    fun priorityRouteEnabled(index: Int): Boolean = priorityRoute(index).enabled

    fun priorityRouteSlots(): List<Int> {
        return (1..settings.customEndpoints.size.coerceAtLeast(1)).toList()
    }

    fun updatePriorityRouteEnabled(index: Int, enabled: Boolean) {
        updateSettings { current ->
            val routes = current.priorityRoutes
                .normalizedRoutes(current.customEndpoints.size.coerceAtLeast(1))
                .toMutableList()
            routes[index] = routes[index].copy(enabled = enabled)
            current.copy(priorityRoutes = routes)
        }
    }

    fun updatePriorityRouteSlot(index: Int, slot: Int) {
        updateSettings { current ->
            val routes = current.priorityRoutes
                .normalizedRoutes(current.customEndpoints.size.coerceAtLeast(1))
                .toMutableList()
            routes[index] = routes[index].copy(
                enabled = true,
                apiSlot = slot.coerceIn(1, current.customEndpoints.size.coerceAtLeast(1))
            )
            current.copy(priorityRoutes = routes)
        }
    }

    fun clearPriorityRoute(index: Int) {
        updateSettings { current ->
            val routes = current.priorityRoutes
                .normalizedRoutes(current.customEndpoints.size.coerceAtLeast(1))
                .toMutableList()
            routes[index] = AiRoute()
            current.copy(priorityRoutes = routes)
        }
    }

    fun testAiProvider() {
        if (isAnalyzing) return

        val config = resolveConfig(settings.editSlot)
        if (config == null) {
            aiTestStatus = "当前 API 配置不完整，请先填写 Key、模型和 Base URL。"
            addLog("AI", aiTestStatus)
            return
        }

        viewModelScope.launch {
            isAnalyzing = true
            aiTestStatus = "姝ｅ湪娴嬭瘯 ${config.displayName}..."
            val result = aiAnalyzer.test(config)
            aiTestStatus = result.text
            addLog("AI", aiTestStatus)
            logAiRaw(result)
            finishAnalyzeFlow()
        }
    }

    private suspend fun handleCameraUploadResult(
        result: CameraFetchResult,
        analyzeAfterAppend: Boolean,
        triggeredByBoardButton: Boolean
    ) {
        if (!result.ok || result.image == null) {
            latestResult = result.message
            addLog("相机", result.message)
            return
        }

        clearCurrentImageQueueForNextCaptureIfNeeded("板子拍照")
        appendBoardImage(result.image)
        if (timedBatchActive && timedBatchExpectedCount > 0) {
            val currentCount = boardImageQueue.size.coerceAtMost(timedBatchExpectedCount)
            sendLiveStatusToBand(
                title = "定时拍照进行中",
                content = "已收到第 $currentCount/$timedBatchExpectedCount 张图片。"
            )
        } else if (!analyzeAfterAppend) {
            sendLiveStatusToBand(
                title = "新图片已收到",
                content = "新拍照片已回到手机，并加入图片队列。"
            )
        }
        if (!analyzeAfterAppend) {
            latestResult = "板子图片已回到手机，并加入图片队列，暂不自动分析。"
            addLog("图片", "${result.message} | 已加入图片队列")
            return
        }
        latestResult = result.message
        addLog("相机", result.message)
        startAnalyzeFlow(
            loadedImages = boardImageQueue.toList(),
            historyTitle = "相机拍照分析",
            sourceKey = boardImageQueue.joinToString("|") { it.label },
            triggeredByBoardButton = triggeredByBoardButton
        )
    }

    private suspend fun handleCameraPreviewResult(result: CameraFetchResult) {
        if (!result.ok || result.image == null) {
            latestResult = result.message
            addLog("鐩告満", result.message)
            return
        }

        appendBoardImage(result.image)
        latestResult = "最新拍照已回到手机，并加入图片队列。"
        addLog("相机", "${result.message} | 已加入图片队列")
    }

    private suspend fun handleBoardAnalyzeRequest() {
        if (isAnalyzing) {
            addLog("分析", "板子触发了分析请求，但当前已经在分析中，忽略本次请求")
            return
        }

        if (boardImageQueue.isEmpty()) {
            latestResult = "板子请求分析，但当前没有可分析的图片队列。"
            addLog("分析", latestResult)
            sendLiveStatusToBand(
                title = "分析请求已收到",
                content = "但当前没有图片可分析，请先拍照。"
            )
            return
        }

        latestResult = "板子已请求分析，正在处理当前图片队列..."
        addLog("分析", latestResult)
        timedBatchActive = false
        timedBatchExpectedCount = 0
        sendLiveStatusToBand(
            title = "开始分析",
            content = "十张图片已上传完成，正在自动分析。"
        )
        startAnalyzeFlow(
            loadedImages = boardImageQueue.toList(),
            historyTitle = "板子按钮分析",
            sourceKey = boardImageQueue.joinToString("|") { it.label },
            triggeredByBoardButton = true
        )
    }

    private suspend fun handleTimedCaptureRequest() {
        val now = System.currentTimeMillis()
        if (now - lastTimedCaptureRequestMs < 1500) {
            return
        }
        lastTimedCaptureRequestMs = now
        timedBatchActive = true
        timedBatchExpectedCount = 10
        latestResult = "已收到板子的定时拍照请求，正在通知手环。"
        addLog("相机", latestResult)
        sendLiveStatusToBand(
            title = "定时拍照",
            content = "已接收",
            vibrate = true
        ) { ok ->
            if (!ok) {
                timedBatchActive = false
                timedBatchExpectedCount = 0
                latestResult = "定时拍照指令未成功同步到手环，板子暂不启动定时拍照。"
                addLog("手环", latestResult)
                return@sendLiveStatusToBand
            }
            cameraBridge.acknowledgeTimedCaptureArmed { message ->
                latestResult = message
                addLog("相机", message)
                if (message.contains("failed", ignoreCase = true) || message.contains("失败")) {
                    timedBatchActive = false
                    timedBatchExpectedCount = 0
                    return@acknowledgeTimedCaptureArmed
                }
                sendLiveStatusToBand(
                    title = "定时拍照准备完成",
                    content = "手环已接收。5 秒后开始第 1 张，之后每 10 秒拍 1 张，共 10 张。"
                )
            }
        }
    }

    private suspend fun handleBandCommand(message: PhoneMessage) {
        when (message.content.trim().lowercase(Locale.US)) {
            "timed_capture_request" -> handleBandTimedCaptureCommand()
            "analyze_request" -> handleBandAnalyzeCommand()
        }
    }

    private suspend fun handleBandTimedCaptureCommand() {
        val now = System.currentTimeMillis()
        if (now - lastTimedCaptureRequestMs < 1500) {
            addLog("手环", "手环三击触发过快，已忽略重复定时拍照指令")
            return
        }
        lastTimedCaptureRequestMs = now
        timedBatchActive = true
        timedBatchExpectedCount = 10
        latestResult = "已收到手环定时拍照指令，正在发送给板子。"
        addLog("手环", latestResult)
        sendLiveStatusToBand(
            title = "定时拍照",
            content = "手环已触发，正在发送给板子。",
            vibrate = true
        )
        cameraBridge.requestTimedCaptureStart { message ->
            latestResult = message
            addLog("相机", message)
            if (message.contains("failed", ignoreCase = true) || message.contains("失败")) {
                timedBatchActive = false
                timedBatchExpectedCount = 0
                sendLiveStatusToBand(
                    title = "定时拍照发送失败",
                    content = "手机已收到手环指令，但板子未成功进入定时模式。"
                )
                return@requestTimedCaptureStart
            }
            sendLiveStatusToBand(
                title = "定时拍照已发送",
                content = "板子已进入定时模式。5 秒后开始第 1 张，之后每 10 秒拍 1 张。"
            )
        }
    }

    private suspend fun handleBandAnalyzeCommand() {
        latestResult = "已收到手环分析指令，正在发送给板子。"
        addLog("手环", latestResult)
        sendLiveStatusToBand(
            title = "分析指令",
            content = "手环已触发，正在发送给板子。"
        )
        cameraBridge.requestAnalyzeCommand { message ->
            latestResult = message
            addLog("相机", message)
            if (message.contains("failed", ignoreCase = true) || message.contains("失败")) {
                sendLiveStatusToBand(
                    title = "分析指令发送失败",
                    content = "手机已收到手环指令，但板子没有成功接收分析请求。"
                )
                return@requestAnalyzeCommand
            }
            sendLiveStatusToBand(
                title = "分析指令已发送",
                content = "板子已收到分析请求，正在开始分析流程。"
            )
        }
    }

    private fun startAnalyzeFlow(
        loadedImages: List<LoadedImage>,
        historyTitle: String,
        sourceKey: String,
        triggeredByBoardButton: Boolean
    ) {
        if (loadedImages.isEmpty()) {
            latestResult = "没有可分析的图片。"
            addLog("分析", latestResult)
            return
        }

        val baseConfigs = buildOrderedConfigs()
        if (baseConfigs.isEmpty()) {
            latestResult = "请先在设置里至少配置一个可用顺位。"
            addLog("AI", latestResult)
            return
        }

        val rotatedConfigs = rotateConfigsForSource(sourceKey, baseConfigs)
        val orderedPairs = rotatedConfigs.mapNotNull { config ->
            val originalIndex = baseConfigs.indexOfFirst { it.apiSlot == config.apiSlot }
            if (originalIndex >= 0) originalIndex to config else null
        }

        activeAnalyzeJob = viewModelScope.launch {
            isAnalyzing = true
            latestResult = if (triggeredByBoardButton) {
                "鐩告満鏉垮凡瑙﹀彂鍒嗘瀽锛屾鍦ㄥ鐞嗗浘鐗?.."
            } else {
                "姝ｅ湪鍒嗘瀽鍥剧墖..."
            }
            addLog("鍒嗘瀽", latestResult)

            for ((originalIndex, config) in orderedPairs) {
                sendLiveStatusToBand(
                    title = "顺位 ${originalIndex + 1}",
                    content = "正在使用顺位 ${originalIndex + 1}（API ${config.apiSlot}）分析。"
                )
                latestResult = "正在使用顺位 ${originalIndex + 1}: ${config.displayName}"
                addLog("AI", latestResult)

                val result = runAnalyzeOnce(config, loadedImages) ?: run {
                    finishAnalyzeFlow()
                    return@launch
                }

                if (result.ok) {
                    latestResult = result.text
                    addLog("AI", "顺位 ${originalIndex + 1} 分析成功")
                    logAiRaw(result)
                    lastAnalysisSourceKey = sourceKey
                    lastSuccessfulRouteIndex = originalIndex
                    val delayMs = settings.delayMs.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                    if (delayMs > 0L) delay(delayMs)
                    sendResultToBand(result.text.trim(), historyTitle)
                    pendingQueueClearOnNextCapture = true
                    addLog("图片", "本次分析成功，下一次拍照时会自动清空旧队列")
                    finishAnalyzeFlow()
                    return@launch
                }

                addLog("AI", "顺位 ${originalIndex + 1} 失败: ${result.text}")
                logAiRaw(result)
            }

            latestResult = "所有已配置顺位都失败了。"
            sendLiveStatusToBand(
                title = "鍒嗘瀽澶辫触",
                content = "所有已配置顺位都失败了。"
            )
            finishAnalyzeFlow()
        }
    }

    private suspend fun runAnalyzeOnce(
        config: AiRunConfig,
        loadedImages: List<LoadedImage>
    ): AiAnalyzeResult? {
        val images = latestBoardImage?.let { listOf(it) } ?: runCatching {
            imageRepository.loadAll(selectedImageUris)
        }.getOrElse { error ->
            return AiAnalyzeResult(false, "读取图片失败: ${error.message}")
        }

        return try {
            aiAnalyzer.analyzeImages(config, loadedImages)
        } catch (error: CancellationException) {
            latestResult = "分析已中断。"
            addLog("AI", "分析流程已取消: ${error.message ?: "无额外信息"}")
            null
        } catch (error: Exception) {
            AiAnalyzeResult(false, "分析异常: ${error.message}")
        }
    }

    private fun rotateConfigsForSource(
        sourceKey: String,
        baseConfigs: List<AiRunConfig>
    ): List<AiRunConfig> {
        val lastSuccess = lastSuccessfulRouteIndex
        if (sourceKey != lastAnalysisSourceKey || lastSuccess == null || baseConfigs.size <= 1) {
            if (sourceKey != lastAnalysisSourceKey) {
                lastSuccessfulRouteIndex = null
            }
            return baseConfigs
        }

        val startIndex = (lastSuccess + 1) % baseConfigs.size
        return baseConfigs.drop(startIndex) + baseConfigs.take(startIndex)
    }

    private fun buildOrderedConfigs(): List<AiRunConfig> {
        val configs = settings.priorityRoutes
            .normalizedRoutes(settings.customEndpoints.size.coerceAtLeast(1))
            .filter { it.enabled }
            .mapNotNull { route -> resolveConfig(route.apiSlot) }
            .distinctBy { it.apiSlot }

        if (configs.isNotEmpty()) return configs
        return listOfNotNull(resolveConfig(settings.editSlot))
    }

    private fun resolveConfig(slot: Int): AiRunConfig? {
        val endpoint = settings.customEndpoints.getOrNull(slot - 1) ?: return null
        val apiKey = endpoint.apiKey.trim()
        val model = endpoint.model.trim()
        val baseUrl = endpoint.baseUrl.trim()
        if (apiKey.isEmpty() || model.isEmpty() || baseUrl.isEmpty()) return null

        return AiRunConfig(
            provider = AiProvider.CUSTOM,
            apiSlot = slot,
            prompt = settings.prompt,
            apiKey = apiKey,
            model = model,
            baseUrl = baseUrl
        )
    }

    private fun currentEditingEndpoint(): ProviderEndpoint {
        return settings.customEndpoints.getOrElse(settings.editSlot - 1) { ProviderEndpoint() }
    }

    private fun updateEndpoint(
        slot: Int,
        transform: (ProviderEndpoint) -> ProviderEndpoint
    ) {
        updateSettings { current ->
            val index = (slot - 1).coerceAtLeast(0)
            val target = current.customEndpoints.toMutableList()
            while (target.size <= index) {
                target += ProviderEndpoint()
            }
            target[index] = transform(target[index])
            current.copy(customEndpoints = target)
        }
    }

    private fun applyCameraConfigThen(onSuccess: () -> Unit) {
        val frameSize = currentCameraFrameSize()
        val quality = settings.cameraJpegQuality.toIntOrNull()?.coerceIn(10, 30) ?: 12
        cameraBridge.applyCameraConfig(frameSize, quality) { message ->
            viewModelScope.launch {
                latestResult = message
                addLog("相机", message)
                refreshCameraConnection()
                val lower = message.lowercase(Locale.getDefault())
                val isFailure = lower.contains("error") ||
                    lower.contains("timeout") ||
                    lower.contains("not permitted") ||
                    lower.contains("failed") ||
                    lower.contains("cannot") ||
                    lower.contains("denied")
                if (!isFailure) {
                    onSuccess()
                }
            }
        }
    }

    private fun sendLiveStatusToBand(
        title: String,
        content: String,
        vibrate: Boolean = false,
        vibrationMode: Int = if (vibrate) 1 else 0,
        callback: ((Boolean) -> Unit)? = null
    ) {
        val messageType = when (vibrationMode) {
            2 -> "status_vibrate_double"
            1 -> "status_vibrate"
            else -> "status_update"
        }
        bandBridge.send(
            PhoneMessage(
                type = messageType,
                title = title,
                content = content
            )
        ) { result ->
            addLog("手环", "状态同步${if (result.ok) "成功" else "失败"} | ${result.message}")
            callback?.invoke(result.ok)
        }
    }

    private fun sendResultToBand(content: String, title: String) {
        if (content.isEmpty()) return
        val timeLabel = formatter.format(Date())
        bandBridge.send(
            PhoneMessage(
                type = "text_result",
                title = timeLabel,
                content = content
            )
        ) { sendResult ->
            val status = if (sendResult.ok) "分析完成并已发送手环" else "分析完成，但手环发送失败"
            history.add(
                0,
                SendHistoryItem(
                    timeLabel = timeLabel,
                    title = title,
                    content = content,
                    status = status
                )
            )
            addLog("发送", sendResult.message)
            timedBatchActive = false
            timedBatchExpectedCount = 0
            refreshBandConnection()
        }
    }

    private fun buildSelectedImageState(uris: List<Uri>): SelectedImageState {
        if (uris.isEmpty()) return SelectedImageState()

        val preview = uris.take(4).mapIndexed { index, uri ->
            uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "鍥剧墖 ${index + 1}"
        }

        val detail = buildString {
            append(preview.joinToString("\n"))
            if (uris.size > preview.size) {
                append("\n还有 ${uris.size - preview.size} 张未展开显示。")
            }
        }

        return SelectedImageState(
            label = "已选择 ${uris.size} 张测试图片",
            detail = detail,
            count = uris.size,
            ready = true
        )
    }

    private fun buildBoardImageState(images: List<LoadedImage>): SelectedImageState {
        if (images.isEmpty()) return SelectedImageState()

        val preview = images.takeLast(4).reversed().mapIndexed { index, image ->
            image.label.ifBlank { "鐩告満鍥剧墖 ${index + 1}" }
        }
        val detail = buildString {
            append(preview.joinToString("\n"))
            if (images.size > preview.size) {
                append("\n还有 ${images.size - preview.size} 张未展开显示。")
            }
        }

        return SelectedImageState(
            label = "相机图片队列 ${images.size} 张",
            detail = detail,
            count = images.size,
            ready = true
        )
    }

    private fun refreshSelectedImageState() {
        selectedImageState = when {
            selectedImageUris.isNotEmpty() -> buildSelectedImageState(selectedImageUris)
            boardImageQueue.isNotEmpty() -> buildBoardImageState(boardImageQueue.toList())
            else -> SelectedImageState()
        }
    }

    private fun appendBoardImage(image: LoadedImage) {
        selectedImageUris.clear()
        latestBoardImage = image
        latestPreviewImage = image
        if (boardImageQueue.none { it.label == image.label && it.bytes.contentEquals(image.bytes) }) {
            boardImageQueue.add(image)
        }
        refreshSelectedImageState()
    }

    private fun clearCurrentImageQueueForNextCaptureIfNeeded(triggerSource: String) {
        if (!pendingQueueClearOnNextCapture) {
            return
        }
        pendingQueueClearOnNextCapture = false
        selectedImageUris.clear()
        boardImageQueue.clear()
        latestBoardImage = null
        latestPreviewImage = null
        refreshSelectedImageState()
        addLog("图片", "$triggerSource 已触发清空旧图片队列")
    }

    private fun updateSettings(transform: (AppSettings) -> AppSettings) {
        settings = transform(settings).normalized()
        settingsStore.save(settings)
    }

    private fun startAutoRefreshLoop() {
        autoRefreshJob?.cancel()
        autoRefreshJob = viewModelScope.launch {
            while (true) {
                delay(autoRefreshIntervalMs)
                if (!cameraState.connected) {
                    runCatching { refreshCameraConnection(logChange = false) }
                        .onFailure { addLog("相机", "自动刷新失败: ${it.message}") }
                }
            }
        }
    }

    private fun finishAnalyzeFlow() {
        isAnalyzing = false
        activeAnalyzeJob = null
    }

    private fun handleCameraLog(message: String) {
        addLog("相机", message)
        val bandContent = buildBandCameraLog(message) ?: return
        val now = System.currentTimeMillis()
        if (bandContent == lastBandLiveStatusContent && now - lastBandLiveStatusAt < 1200L) {
            return
        }
        lastBandLiveStatusContent = bandContent
        lastBandLiveStatusAt = now
        val vibrationMode = when {
            bandContent.contains("timed_capture_", ignoreCase = true) &&
                bandContent.contains("_soon", ignoreCase = true) -> 1
            bandContent.contains("timed_saved_", ignoreCase = true) -> 2
            else -> 0
        }
        sendLiveStatusToBand(
            title = "相机日志",
            content = bandContent,
            vibrationMode = vibrationMode
        )
        if (vibrationMode > 0) {
            viewModelScope.launch {
                delay(if (vibrationMode == 2) 650L else 350L)
                sendLiveStatusToBand(
                    title = "相机日志",
                    content = bandContent,
                    vibrationMode = 0
                )
            }
        }
    }

    private fun buildBandCameraLog(message: String): String? {
        val trimmed = message.trim()
        if (trimmed.isEmpty()) {
            return null
        }
        return when {
            trimmed.startsWith("[status] ", ignoreCase = true) ->
                trimmed.removePrefix("[status] ").trim().ifEmpty { null }
            trimmed.startsWith("Wi-Fi register from board:", ignoreCase = true) -> trimmed
            trimmed.startsWith("Wi-Fi upload received:", ignoreCase = true) -> trimmed
            trimmed.startsWith("Wi-Fi socket failed:", ignoreCase = true) -> trimmed
            trimmed.startsWith("BLE", ignoreCase = true) -> trimmed
            else -> trimmed
        }
    }

    private fun logAiRaw(result: AiAnalyzeResult) {
        val raw = result.raw.trim()
        if (raw.isEmpty()) return
        val snippet = if (raw.length > 800) raw.take(800) + "... [raw truncated]" else raw
        addLog("AI-RAW", snippet)
    }

    private fun addLog(source: String, message: String) {
        logs.add(
            0,
            AppLog(
                timeLabel = formatter.format(Date()),
                source = source,
                message = message
            )
        )
    }

    private fun LoadedImage.extractSourceKey(): String {
        return label.substringAfterLast('_').substringBeforeLast('.', label)
    }

    override fun onCleared() {
        autoRefreshJob?.cancel()
        activeAnalyzeJob?.cancel()
        super.onCleared()
    }
}

private fun AppSettings.normalized(): AppSettings {
    val endpoints = customEndpoints.ifEmpty { defaultEndpoints(AiProvider.CUSTOM) }
    val clampedRoutes = priorityRoutes.normalizedRoutes(endpoints.size)
    val normalizedFrameSize = cameraFrameSize.uppercase(Locale.US).let { value ->
        if (value in listOf("SVGA", "XGA", "UXGA")) value else "XGA"
    }
    val normalizedQuality = (cameraJpegQuality.toIntOrNull() ?: 12).coerceIn(10, 30).toString()
    return copy(
        cameraFrameSize = normalizedFrameSize,
        cameraJpegQuality = normalizedQuality,
        customEndpoints = endpoints,
        priorityRoutes = clampedRoutes,
        editSlot = editSlot.coerceIn(1, endpoints.size.coerceAtLeast(1))
    )
}

private fun List<AiRoute>.normalizedRoutes(maxSlot: Int = 1): List<AiRoute> {
    val defaults = defaultPriorityRoutes()
    return List(8) { index ->
        val source = this.getOrNull(index) ?: defaults.getOrNull(index) ?: AiRoute()
        if (!source.enabled) {
            AiRoute()
        } else {
            source.copy(
                providerName = AiProvider.CUSTOM.name,
                apiSlot = source.apiSlot.coerceIn(1, maxSlot.coerceAtLeast(1)),
                enabled = true
            )
        }
    }
}
