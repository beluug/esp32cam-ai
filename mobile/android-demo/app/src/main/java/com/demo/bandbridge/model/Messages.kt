package com.demo.bandbridge.model

import org.json.JSONObject

data class PhoneMessage(
    val type: String,
    val title: String,
    val content: String,
    val sentAt: Long = System.currentTimeMillis()
) {
    fun toPayload(): String {
        return JSONObject()
            .put("type", type)
            .put("title", title)
            .put("content", content)
            .put("sentAt", sentAt)
            .toString()
    }
}

enum class AiProvider(val label: String, val supportsVision: Boolean) {
    CUSTOM("自定义 AI", true)
}

data class ProviderEndpoint(
    val apiKey: String = "",
    val model: String = "",
    val baseUrl: String = ""
)

data class AiRoute(
    val providerName: String = AiProvider.CUSTOM.name,
    val apiSlot: Int = 1,
    val enabled: Boolean = false
) {
    fun providerOrNull(): AiProvider? {
        return if (enabled) AiProvider.CUSTOM else null
    }
}

fun defaultEndpoints(provider: AiProvider): List<ProviderEndpoint> {
    return when (provider) {
        AiProvider.CUSTOM -> listOf(
            ProviderEndpoint(
                model = "custom-vision-model",
                baseUrl = "https://example.com/v1"
            )
        )
    }
}

fun defaultPriorityRoutes(): List<AiRoute> {
    return listOf(
        AiRoute(apiSlot = 1, enabled = true),
        AiRoute(),
        AiRoute(),
        AiRoute(),
        AiRoute(),
        AiRoute(),
        AiRoute(),
        AiRoute()
    )
}

data class AppSettings(
    val editProvider: AiProvider = AiProvider.CUSTOM,
    val editSlot: Int = 1,
    val prompt: String = "请输出完整、清楚、便于手机阅读的中文结果，必要时分点写清楚。",
    val delayMs: String = "1200",
    val cameraFrameSize: String = "XGA",
    val cameraJpegQuality: String = "12",
    val customEndpoints: List<ProviderEndpoint> = defaultEndpoints(AiProvider.CUSTOM),
    val priorityRoutes: List<AiRoute> = defaultPriorityRoutes()
)

data class AiRunConfig(
    val provider: AiProvider = AiProvider.CUSTOM,
    val apiSlot: Int,
    val prompt: String,
    val apiKey: String,
    val model: String,
    val baseUrl: String = ""
) {
    val displayName: String
        get() = "自定义 AI API $apiSlot"
}

data class ConnectionState(
    val title: String,
    val detail: String,
    val connected: Boolean
)

data class GestureState(
    val title: String,
    val detail: String
)

data class SelectedImageState(
    val label: String = "还没有选择测试图片。",
    val detail: String = "支持多张图片一起分析。",
    val count: Int = 0,
    val ready: Boolean = false
)

data class BandSendResult(
    val ok: Boolean,
    val message: String
)

data class SendHistoryItem(
    val timeLabel: String,
    val title: String,
    val content: String,
    val status: String
)

data class AppLog(
    val timeLabel: String,
    val source: String,
    val message: String
)
