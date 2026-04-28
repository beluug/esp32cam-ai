package com.demo.bandbridge.storage

import android.content.Context
import com.demo.bandbridge.model.AiProvider
import com.demo.bandbridge.model.AiRoute
import com.demo.bandbridge.model.AppSettings
import com.demo.bandbridge.model.ProviderEndpoint
import com.demo.bandbridge.model.defaultEndpoints
import com.demo.bandbridge.model.defaultPriorityRoutes
import org.json.JSONArray
import org.json.JSONObject

class AppSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): AppSettings {
        val raw = preferences.getString(KEY_SETTINGS_JSON, null) ?: return AppSettings()
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return AppSettings()
        val defaults = AppSettings()

        return AppSettings(
            editProvider = AiProvider.CUSTOM,
            editSlot = json.optInt(KEY_EDIT_SLOT, defaults.editSlot).coerceAtLeast(1),
            prompt = json.optString(KEY_PROMPT, defaults.prompt),
            delayMs = json.optString(KEY_DELAY_MS, defaults.delayMs),
            cameraFrameSize = json.optString(KEY_CAMERA_FRAME_SIZE, defaults.cameraFrameSize),
            cameraJpegQuality = json.optString(KEY_CAMERA_JPEG_QUALITY, defaults.cameraJpegQuality),
            customEndpoints = readEndpoints(json.optJSONArray(KEY_CUSTOM_ENDPOINTS)),
            priorityRoutes = readRoutes(json.optJSONArray(KEY_PRIORITY_ROUTES))
        )
    }

    fun save(settings: AppSettings) {
        val json = JSONObject()
            .put(KEY_EDIT_SLOT, settings.editSlot)
            .put(KEY_PROMPT, settings.prompt)
            .put(KEY_DELAY_MS, settings.delayMs)
            .put(KEY_CAMERA_FRAME_SIZE, settings.cameraFrameSize)
            .put(KEY_CAMERA_JPEG_QUALITY, settings.cameraJpegQuality)
            .put(KEY_CUSTOM_ENDPOINTS, writeEndpoints(settings.customEndpoints))
            .put(KEY_PRIORITY_ROUTES, writeRoutes(settings.priorityRoutes))

        preferences.edit().putString(KEY_SETTINGS_JSON, json.toString()).apply()
    }

    private fun readEndpoints(array: JSONArray?): List<ProviderEndpoint> {
        val defaults = defaultEndpoints(AiProvider.CUSTOM)
        if (array == null) return defaults

        val endpoints = mutableListOf<ProviderEndpoint>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            endpoints += ProviderEndpoint(
                apiKey = item.optString("apiKey", ""),
                model = item.optString("model", ""),
                baseUrl = item.optString("baseUrl", "")
            )
        }
        return if (endpoints.isEmpty()) defaults else endpoints
    }

    private fun writeEndpoints(endpoints: List<ProviderEndpoint>): JSONArray {
        val array = JSONArray()
        endpoints.forEach { endpoint ->
            array.put(
                JSONObject()
                    .put("apiKey", endpoint.apiKey)
                    .put("model", endpoint.model)
                    .put("baseUrl", endpoint.baseUrl)
            )
        }
        return array
    }

    private fun readRoutes(array: JSONArray?): List<AiRoute> {
        val defaults = defaultPriorityRoutes()
        if (array == null) return defaults

        val routes = mutableListOf<AiRoute>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            routes += AiRoute(
                providerName = AiProvider.CUSTOM.name,
                apiSlot = item.optInt("apiSlot", 1).coerceAtLeast(1),
                enabled = item.optBoolean("enabled", false)
            )
        }

        return if (routes.isEmpty()) defaults else routes.take(MAX_PRIORITY_COUNT).let { current ->
            if (current.size >= MAX_PRIORITY_COUNT) current else current + List(MAX_PRIORITY_COUNT - current.size) { AiRoute() }
        }
    }

    private fun writeRoutes(routes: List<AiRoute>): JSONArray {
        val array = JSONArray()
        routes.take(MAX_PRIORITY_COUNT).forEach { route ->
            array.put(
                JSONObject()
                    .put("providerName", AiProvider.CUSTOM.name)
                    .put("apiSlot", route.apiSlot)
                    .put("enabled", route.enabled)
            )
        }
        return array
    }

    companion object {
        private const val PREFS_NAME = "band_bridge_settings"
        private const val KEY_SETTINGS_JSON = "settings_json"
        private const val KEY_EDIT_SLOT = "edit_slot"
        private const val KEY_PROMPT = "prompt"
        private const val KEY_DELAY_MS = "delay_ms"
        private const val KEY_CAMERA_FRAME_SIZE = "camera_frame_size"
        private const val KEY_CAMERA_JPEG_QUALITY = "camera_jpeg_quality"
        private const val KEY_CUSTOM_ENDPOINTS = "custom_endpoints"
        private const val KEY_PRIORITY_ROUTES = "priority_routes"
        private const val MAX_PRIORITY_COUNT = 8
    }
}
