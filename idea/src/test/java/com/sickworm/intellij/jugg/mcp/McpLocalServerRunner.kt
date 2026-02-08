package com.sickworm.intellij.jugg.mcp

import com.google.gson.Gson
import com.sickworm.intellij.jugg.ide.logic.IdeaPlatformApi
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.mock.buildDir
import com.sickworm.intellij.jugg.mock.logger
import com.sickworm.intellij.jugg.platform.PlatformApi
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

fun main() {
    PlatformApi.impl = IdeaPlatformApi()
    JuggLogger.register("project", buildDir)
    JuggLogger.listenProjectLog("project", logger)
    McpLocalServer.start()
}
