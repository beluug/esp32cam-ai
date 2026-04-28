package com.demo.bandbridge.ai

import android.util.Base64
import com.demo.bandbridge.image.LoadedImage
import com.demo.bandbridge.model.AiRunConfig
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class AiAnalyzeResult(
    val ok: Boolean,
    val text: String,
    val raw: String = ""
)

private enum class CustomEndpointKind {
    RESPONSES,
    CHAT_COMPLETIONS
}

private data class CustomEndpoint(
    val url: String,
    val kind: CustomEndpointKind
)

class AiAnalyzer {
    suspend fun test(config: AiRunConfig): AiAnalyzeResult = withContext(Dispatchers.IO) {
        if (config.apiKey.isBlank()) {
            return@withContext AiAnalyzeResult(false, "自定义 AI Key 为空。")
        }

        val endpoints = resolveCustomEndpoints(config.baseUrl)
        if (endpoints.isEmpty()) {
            return@withContext AiAnalyzeResult(false, "自定义 AI URL 为空，或不是有效地址。请填写 baseUrl，例如 https://example.com/v1。")
        }

        runCustomWithFallback(endpoints) { endpoint ->
            when (endpoint.kind) {
                CustomEndpointKind.RESPONSES -> {
                    val body = JSONObject()
                        .put("model", config.model)
                        .put("max_output_tokens", 40)
                        .put(
                            "input",
                            JSONArray().put(
                                JSONObject()
                                    .put("role", "user")
                                    .put(
                                        "content",
                                        JSONArray().put(
                                            JSONObject()
                                                .put("type", "input_text")
                                                .put("text", "请只回复 CUSTOM_OK")
                                        )
                                    )
                            )
                        )

                    postJson(
                        url = endpoint.url,
                        headers = mapOf("Authorization" to "Bearer ${config.apiKey}"),
                        body = body.toString(),
                        parser = { _, raw -> AiAnalyzeResult(true, "自定义 AI 测试成功。", raw) }
                    )
                }

                CustomEndpointKind.CHAT_COMPLETIONS -> {
                    val body = JSONObject()
                        .put("model", config.model)
                        .put("max_tokens", 40)
                        .put("stream", false)
                        .put(
                            "messages",
                            JSONArray().put(
                                JSONObject()
                                    .put("role", "user")
                                    .put("content", "请只回复 CUSTOM_OK")
                            )
                        )

                    postJson(
                        url = endpoint.url,
                        headers = mapOf("Authorization" to "Bearer ${config.apiKey}"),
                        body = body.toString(),
                        parser = { json, raw ->
                            val text = parseChatCompletionsText(json).ifBlank { parseLooseText(json) }
                            if (text.isBlank()) {
                                AiAnalyzeResult(false, "自定义 AI 已返回响应，但没有解析到文本。", raw)
                            } else {
                                AiAnalyzeResult(true, "自定义 AI 测试成功。", raw)
                            }
                        }
                    )
                }
            }
        }
    }

    suspend fun analyzeImages(
        config: AiRunConfig,
        images: List<LoadedImage>
    ): AiAnalyzeResult = withContext(Dispatchers.IO) {
        if (images.isEmpty()) {
            return@withContext AiAnalyzeResult(false, "还没有可分析的图片。")
        }
        if (config.apiKey.isBlank()) {
            return@withContext AiAnalyzeResult(false, "自定义 AI Key 为空。")
        }

        val endpoints = resolveCustomEndpoints(config.baseUrl)
        if (endpoints.isEmpty()) {
            return@withContext AiAnalyzeResult(false, "自定义 AI URL 为空，或不是有效地址。请填写 baseUrl，例如 https://example.com/v1。")
        }

        runCustomWithFallback(endpoints) { endpoint ->
            when (endpoint.kind) {
                CustomEndpointKind.RESPONSES -> {
                    val content = JSONArray().put(JSONObject().put("type", "input_text").put("text", config.prompt))
                    images.forEach { image ->
                        content.put(
                            JSONObject()
                                .put("type", "input_image")
                                .put("image_url", image.asDataUrl())
                        )
                    }

                    val body = JSONObject()
                        .put("model", config.model)
                        .put("max_output_tokens", 1600)
                        .put(
                            "input",
                            JSONArray().put(
                                JSONObject()
                                    .put("role", "user")
                                    .put("content", content)
                            )
                        )

                    postJson(
                        url = endpoint.url,
                        headers = mapOf("Authorization" to "Bearer ${config.apiKey}"),
                        body = body.toString(),
                        parser = { json, raw -> parseSingleTextResult(json, raw, ::parseOpenAiText) }
                    )
                }

                CustomEndpointKind.CHAT_COMPLETIONS -> {
                    val content = JSONArray().put(JSONObject().put("type", "text").put("text", config.prompt))
                    images.forEach { image ->
                        content.put(
                            JSONObject()
                                .put("type", "image_url")
                                .put("image_url", JSONObject().put("url", image.asDataUrl()))
                        )
                    }

                    val body = JSONObject()
                        .put("model", config.model)
                        .put("max_tokens", 1600)
                        .put("stream", false)
                        .put(
                            "messages",
                            JSONArray().put(
                                JSONObject()
                                    .put("role", "user")
                                    .put("content", content)
                            )
                        )

                    postJson(
                        url = endpoint.url,
                        headers = mapOf("Authorization" to "Bearer ${config.apiKey}"),
                        body = body.toString(),
                        parser = { json, raw -> parseSingleTextResult(json, raw, ::parseChatCompletionsText) }
                    )
                }
            }
        }
    }

    private fun resolveCustomEndpoints(rawUrl: String): List<CustomEndpoint> {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return emptyList()

        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }

        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return emptyList()
        val path = uri.path.orEmpty().trimEnd('/')
        val base = withScheme.trimEnd('/')
        val endpoints = linkedSetOf<CustomEndpoint>()

        when {
            path.endsWith("/chat/completions") || path.contains("/chat/completions") -> {
                endpoints += CustomEndpoint(base, CustomEndpointKind.CHAT_COMPLETIONS)
            }

            path.endsWith("/responses") || path.contains("/responses") -> {
                endpoints += CustomEndpoint(base, CustomEndpointKind.RESPONSES)
                endpoints += CustomEndpoint(base.removeSuffix("/responses") + "/chat/completions", CustomEndpointKind.CHAT_COMPLETIONS)
            }

            path.isEmpty() || path == "/" -> {
                endpoints += CustomEndpoint("$base/v1/chat/completions", CustomEndpointKind.CHAT_COMPLETIONS)
                endpoints += CustomEndpoint("$base/v1/responses", CustomEndpointKind.RESPONSES)
            }

            path == "/v1" -> {
                endpoints += CustomEndpoint("$base/chat/completions", CustomEndpointKind.CHAT_COMPLETIONS)
                endpoints += CustomEndpoint("$base/responses", CustomEndpointKind.RESPONSES)
            }

            else -> {
                endpoints += CustomEndpoint("$base/chat/completions", CustomEndpointKind.CHAT_COMPLETIONS)
                endpoints += CustomEndpoint("$base/responses", CustomEndpointKind.RESPONSES)
            }
        }

        return endpoints.toList()
    }

    private fun runCustomWithFallback(
        endpoints: List<CustomEndpoint>,
        action: (CustomEndpoint) -> AiAnalyzeResult
    ): AiAnalyzeResult {
        var lastResult = AiAnalyzeResult(false, "自定义 AI 请求失败。")
        endpoints.forEachIndexed { index, endpoint ->
            val result = action(endpoint)
            if (result.ok) return result

            lastResult = if (index > 0) {
                result.copy(text = "${result.text}\n已尝试接口：${endpoints.joinToString(" , ") { it.url }}")
            } else {
                result
            }

            if (!shouldTryNextCustomEndpoint(result) || index == endpoints.lastIndex) {
                return lastResult
            }
        }
        return lastResult
    }

    private fun shouldTryNextCustomEndpoint(result: AiAnalyzeResult): Boolean {
        val lowerText = result.text.lowercase()
        val lowerRaw = result.raw.lowercase()
        return lowerText.contains("http 404") ||
            lowerText.contains("http 405") ||
            lowerText.contains("接口发生重定向") ||
            lowerText.contains("not implemented") ||
            lowerRaw.contains("not implemented") ||
            lowerRaw.contains("convert_request_failed")
    }

    private fun parseSingleTextResult(
        json: JSONObject,
        raw: String,
        parser: (JSONObject) -> String
    ): AiAnalyzeResult {
        val text = parser(json).ifBlank { parseLooseText(json) }
        return if (text.isBlank()) {
            AiAnalyzeResult(false, "自定义 AI 已返回响应，但没有解析到文本。", raw)
        } else {
            AiAnalyzeResult(true, text, raw)
        }
    }

    private fun parseOpenAiText(json: JSONObject): String {
        val direct = json.optString("output_text").trim()
        if (direct.isNotEmpty()) return direct

        val output = json.optJSONArray("output") ?: return ""
        val builder = StringBuilder()
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            val contents = item.optJSONArray("content") ?: continue
            for (j in 0 until contents.length()) {
                val content = contents.optJSONObject(j) ?: continue
                if (content.optString("type") == "output_text") {
                    builder.append(content.optString("text")).append('\n')
                }
            }
        }
        return builder.toString().trim()
    }

    private fun parseChatCompletionsText(json: JSONObject): String {
        val choice = json.optJSONArray("choices")?.optJSONObject(0)
        val messageContent = choice
            ?.optJSONObject("message")
            ?.opt("content")
            ?: choice?.optJSONObject("delta")?.opt("content")
            ?: return ""

        return when (messageContent) {
            is String -> messageContent.trim()
            is JSONArray -> {
                val builder = StringBuilder()
                for (i in 0 until messageContent.length()) {
                    when (val item = messageContent.opt(i)) {
                        is JSONObject -> {
                            val text = item.optString("text")
                            if (text.isNotBlank()) {
                                builder.append(text).append('\n')
                            }
                        }

                        is String -> builder.append(item).append('\n')
                    }
                }
                builder.toString().trim()
            }

            else -> ""
        }
    }

    private fun parseLooseText(json: JSONObject): String {
        val candidates = linkedSetOf<String>()

        fun collect(value: Any?, preferredKey: String? = null) {
            when (value) {
                is JSONObject -> {
                    val iterator = value.keys()
                    while (iterator.hasNext()) {
                        val key = iterator.next()
                        collect(value.opt(key), key)
                    }
                }

                is JSONArray -> {
                    for (index in 0 until value.length()) {
                        collect(value.opt(index), preferredKey)
                    }
                }

                is String -> {
                    val text = value.trim()
                    if (text.isEmpty()) return
                    if (text.startsWith("data:")) return
                    if (text.startsWith("http://") || text.startsWith("https://")) return

                    val allowByKey = preferredKey in setOf(
                        "output_text",
                        "text",
                        "content",
                        "response",
                        "answer",
                        "result",
                        "message"
                    )
                    val looksLikeNaturalText = text.any { it.isLetter() } || text.contains('\n') || text.contains(' ')
                    if ((allowByKey || looksLikeNaturalText) && text.length <= 8000) {
                        candidates += text
                    }
                }
            }
        }

        collect(json)
        return candidates
            .filterNot { candidate ->
                candidate.equals("assistant", ignoreCase = true) ||
                    candidate.equals("user", ignoreCase = true) ||
                    candidate.equals("system", ignoreCase = true)
            }
            .maxByOrNull { it.length }
            ?.trim()
            .orEmpty()
    }

    private fun postJson(
        url: String,
        headers: Map<String, String>,
        body: String,
        parser: (JSONObject, String) -> AiAnalyzeResult
    ): AiAnalyzeResult {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.instanceFollowRedirects = false
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 20000
            connection.readTimeout = 90000
            connection.setRequestProperty("Content-Type", "application/json")
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(body)
            }

            val responseCode = connection.responseCode
            val location = connection.getHeaderField("Location").orEmpty()
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.let { input ->
                BufferedReader(InputStreamReader(input)).use { it.readText() }
            }.orEmpty()

            when {
                responseCode in 200..299 -> {
                    val json = runCatching { JSONObject(raw) }.getOrElse {
                        return AiAnalyzeResult(false, "接口返回成功，但响应不是合法 JSON：${raw.take(240)}", raw)
                    }
                    parser(json, raw)
                }

                responseCode in listOf(301, 302, 303, 307, 308) -> {
                    val redirectTip = if (location.isNotBlank()) {
                        "重定向到了：$location"
                    } else {
                        "没有返回明确的重定向目标。"
                    }
                    AiAnalyzeResult(
                        false,
                        "接口发生重定向。当前填写的很可能是网页地址、网关首页，或缺少具体 API 路径。请填写完整接口地址，例如 /v1/responses 或 /chat/completions。$redirectTip",
                        raw
                    )
                }

                else -> {
                    val looseText = runCatching {
                        parseLooseText(JSONObject(raw))
                    }.getOrDefault("")
                    if (responseCode in 500..599 && looseText.isNotBlank()) {
                        AiAnalyzeResult(true, looseText, raw)
                    } else {
                        AiAnalyzeResult(false, "HTTP $responseCode: ${raw.take(240)}", raw)
                    }
                }
            }
        } catch (error: Exception) {
            AiAnalyzeResult(false, "请求失败：${error.message}")
        } finally {
            connection.disconnect()
        }
    }

    private fun LoadedImage.asDataUrl(): String {
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return "data:$mimeType;base64,$base64"
    }
}
