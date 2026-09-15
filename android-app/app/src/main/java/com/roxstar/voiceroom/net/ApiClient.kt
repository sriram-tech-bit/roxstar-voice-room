package com.roxstar.voiceroom.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ApiClient {
    var baseUrl: String = "http://10.0.2.2:8080"
    var userId: String? = null

    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun createUser(displayName: String): JSONObject {
        val body = JSONObject().put("displayName", displayName).toString()
        return call(
            Request.Builder().url("$baseUrl/users").post(body.toRequestBody(jsonType)).build()
        )
    }

    fun createRoom(): JSONObject = authed("POST", "/rooms")

    fun joinRoom(code: String): JSONObject = authed("POST", "/rooms/${code.uppercase()}/join")

    fun leaveRoom(roomId: String): JSONObject = authed("POST", "/rooms/$roomId/leave")

    fun roomState(roomId: String): JSONObject = authed("GET", "/rooms/$roomId/state")

    fun shareDraft(roomId: String, name: String, durationMs: Long, effect: String): JSONObject {
        val payload = JSONObject()
            .put("name", name)
            .put("durationMs", durationMs)
            .put("effect", effect)
            .put("storageUrl", "local://$name")
        return authed("POST", "/rooms/$roomId/drafts", payload)
    }

    fun startSpin(roomId: String): JSONObject = authed("POST", "/rooms/$roomId/spins")

    private fun authed(method: String, path: String, json: JSONObject? = null): JSONObject {
        val builder = Request.Builder().url("$baseUrl$path")
        userId?.let { builder.addHeader("X-User-Id", it) }
        when (method) {
            "POST" -> builder.post((json?.toString() ?: "{}").toRequestBody(jsonType))
            "GET" -> builder.get()
        }
        return call(builder.build())
    }

    private fun call(request: Request): JSONObject {
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val parsed = if (text.isBlank()) JSONObject() else JSONObject(text)
            if (!response.isSuccessful) {
                val message = parsed.optString("message", "HTTP ${response.code}")
                val code = parsed.optString("code", "ERROR")
                throw ApiException(response.code, code, message)
            }
            return parsed
        }
    }
}

class ApiException(val httpStatus: Int, val code: String, message: String) : Exception("$code: $message")
