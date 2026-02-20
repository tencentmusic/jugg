package com.sickworm.intellij.jugg.server

import com.google.gson.Gson
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.logger.getInstance
import com.sickworm.intellij.jugg.server.protocols.InteractionProcessFlow
import com.sickworm.intellij.jugg.server.protocols.InteractionStep
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * JuggRemoteCompileApplier calls remote-apply endpoints and maps JSON responses
 * to [InteractionProcessFlow]/[InteractionStep] with fail-safe null on network errors.
 */
class JuggRemoteCompileApplier(logger: Logger) {

    private val logger = logger.getInstance("JuggRemoteCompileApplier")

    private val client = OkHttpClient.Builder()
        .callTimeout(3, TimeUnit.MINUTES)
        .readTimeout(3, TimeUnit.MINUTES)
        .writeTimeout(3, TimeUnit.MINUTES)
        .connectTimeout(3, TimeUnit.MINUTES)
        .build()
    private val serverUrl get() = JuggSettings.serverUrl

    private val gson = Gson()

    fun getInitialProcessFlow(username: String, isWindows: Boolean): InteractionProcessFlow? {
        val url = "$serverUrl/remote_apply"
        val content = gson.toJson(mapOf("username" to username, "isWindows" to "$isWindows"))
        val request = Request.Builder()
            .url(url)
            .post(content.toRequestBody("application/json".toMediaTypeOrNull()))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Unexpected code $response")
                response.body?.string()?.let { json ->
                    gson.fromJson(json, InteractionProcessFlow::class.java)
                }
            }
        } catch (e: IOException) {
            logger.debug("getInitialProcessFlow failed: ", e)
            null
        }
    }

    fun getInteractionStep(nextStepUrl: String, token: String, args: List<String> = emptyList()): InteractionStep? {
        val url = "$serverUrl/$nextStepUrl"
        val params = mutableMapOf<String, String>()
        params["token"] = token
        if (args.isNotEmpty()) {
            args.forEachIndexed { index, arg ->
                params["arg_$index"] = arg
            }
        }

        val content = gson.toJson(params)
        val request = Request.Builder()
            .url(url)
            .post(content.toRequestBody("application/json".toMediaTypeOrNull()))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Unexpected code $response")
                response.body?.string()?.let { json ->
                    gson.fromJson(json, InteractionStep::class.java)
                }
            }
        } catch (e: IOException) {
            logger.debug("getInteractionStep failed: ", e)
            null
        }
    }

}
