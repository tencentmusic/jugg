package com.sickworm.intellij.jugg.server

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.diagnostic.Logger
import com.google.gson.JsonPrimitive
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.sickworm.intellij.jugg.ide.bean.JuggSettingDefinition
import com.sickworm.intellij.jugg.ide.bean.JuggSettingsEditor
import com.sickworm.intellij.jugg.project.runtime.SettingsChangedException
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.logger.getInstance
import com.sickworm.intellij.jugg.platform.PlatformApi
import com.sickworm.intellij.jugg.server.protocols.ServerRule
import java.net.InetAddress


/**
 * Auto choose the proper server to use.
 */
class JuggServerChooser(logger: Logger) {

    private val logger: Logger = logger.getInstance("JuggServerChooser")
    private var serverRules: List<ServerRule>? = null

    fun hasAvailableServer(): Boolean {
        if (isSetCustomServer && !JuggSettings.serverUrl.isNullOrBlank()) {
            return true
        }
        return !(serverRules ?: getEmbeddedServers()).isNullOrEmpty()
    }

    private val forbidUrls = mutableSetOf<String>()

    private val isSetCustomServer get() = JuggSettings.serverExpireTimeMill == SERVER_WILL_NOT_EXPIRE

    val customServerUrl: String
        get() = if (isSetCustomServer) JuggSettings.serverUrl.orEmpty() else ""

    /**
     * Update server rules and select server on project opened.
     */
    fun updateServer(serverRules: List<ServerRule>?) {
        this.serverRules = serverRules ?: getEmbeddedServers()

        if (isSetCustomServer) {
            logger.debug("User set custom server, skip update server.")
            return
        }

        val snapshot = JuggSettings.snapshot()
        if (snapshot["serverExpireTimeMill"]?.asLong == SERVER_WILL_NOT_EXPIRE) return
        val oldServerUrl = JuggSettings.serverUrl
        val newServerUrl = selectServer(this.serverRules)?.url
        if (newServerUrl.isNullOrBlank()) {
            logger.debug("Choose server failed, keep current server: $oldServerUrl")
            return
        }
        if (oldServerUrl == newServerUrl) {
            logger.debug("No need update server, still use $oldServerUrl")
        } else {
            logger.debug("Update server from $oldServerUrl to $newServerUrl")
        }
        commitAutomaticServer(newServerUrl, snapshot)
    }

    /**
     * Update server if expired. Check before report event.
     */
    fun updateServerIfExpired(isForce: Boolean = false) {
        if (isSetCustomServer) {
            if (isForce) {
                logger.debug("isSetCustomServer=true, won't update server.")
            }
            return
        }
        if (isForce) {
            logger.debug("isForce=true, update server.")
            updateServer(serverRules)
            return
        }

        val isServerExpired = System.currentTimeMillis() > JuggSettings.serverExpireTimeMill
        if (isServerExpired) {
            logger.debug("Server expired, update server.")
            updateServer(serverRules)
        }
    }

    /**
     * Update server with forbid url.
     */
    fun updateServerWithForbidCurrentUrl(forbidUrl: String?): Boolean {
        if (isSetCustomServer) {
            logger.debug("User set custom server, skip forbid update.")
            return false
        }
        if (forbidUrl.isNullOrBlank()) {
            logger.debug("forbidUrl is null or blank, skip.")
            return false
        }
        if (forbidUrl in forbidUrls) {
            logger.debug("updateServerWithForbidUrl $forbidUrl already in forbid list, skip.")
            return false
        }
        val serverRules = serverRules
        if (serverRules == null)  {
            logger.debug("No server rule found.")
            return false
        }
        if (forbidUrl !in serverRules.map { it.url }) {
            logger.debug("$forbidUrl not in server rules, skip.")
            return false
        }

        val snapshot = JuggSettings.snapshot()
        if (snapshot["serverExpireTimeMill"]?.asLong == SERVER_WILL_NOT_EXPIRE) return false
        val nextServerUrl = selectServer(serverRules, forbidUrls + forbidUrl)?.url
        if (nextServerUrl.isNullOrBlank() || nextServerUrl == forbidUrl) {
            logger.debug("No available alternative server for $forbidUrl, keep current server.")
            return false
        }

        forbidUrls.add(forbidUrl)
        logger.debug("Update server with forbid url: $forbidUrl")
        logger.debug("Update server from $forbidUrl to $nextServerUrl")
        return commitAutomaticServer(nextServerUrl, snapshot)
    }

    private fun selectServer(serverRules: List<ServerRule>?, forbiddenUrls: Set<String> = forbidUrls): ServerRule? {
        logger.debug("Update server rules: $serverRules")
        if (serverRules.isNullOrEmpty()) {
            logger.debug("No server rule found.")
            return null
        }

        serverRules.forEach { serverRule ->
            if (serverRule.url in forbiddenUrls) {
                logger.debug("${serverRule.url} in forbid list, ignore")
                return@forEach
            }
            logger.debug("Check server rule: $serverRule")
            if (serverRule.checkReachableHost != null) {
                if (isReachable(serverRule.checkReachableHost)) {
                    logger.debug("Choose server ${serverRule.url} for check reach success.")
                    return serverRule
                }
            } else {
                logger.debug("Choose server ${serverRule.url} for no check condition.")
                return serverRule
            }
        }

        logger.debug("Choose server failed, return null.")
        return null
    }

    private fun isReachable(host: String): Boolean {
        try {
            val address = InetAddress.getByName(host)
            val reachable = address.isReachable(5000) // 5-second timeout

            if (reachable) {
                logger.debug("$host is reachable")
                return true
            } else {
                logger.debug("$host is not reachable")
            }
        } catch (e: Exception) {
            logger.debug("Error occurred: ${e.message}")
        }

        return false
    }

    fun setCustomServer() {
        val newServerUrl = PlatformApi.showUserAndPasswordInputDialog(
            title = "Set Custom Server",
            content = JuggSettingDefinition.find("serverUrl").label,
            subTitle = JuggSettingDefinition.find("serverUrl").description,
            defaultInputText = if (isSetCustomServer) JuggSettings.serverUrl else "",
        )
        logger.debug("New server url: $newServerUrl")
        if (newServerUrl == null) {
            logger.debug("User not input server url, skip update.")
            return
        }
        JuggSettingsEditor(this).setFromUi("serverUrl", newServerUrl)
    }

    /** Programmatic callers already own the trust decision. Interactive callers use the shared prompt above. */
    fun setCustomServer(newServerUrl: String) {
        JuggSettingsEditor(this).set("serverUrl", newServerUrl, confirm = true)
    }

    /** Resolves a user's return to automatic selection before acquiring the settings write lock. */
    internal fun automaticSettings(snapshot: JsonObject): Map<String, JsonElement> {
        val selected = selectServer(serverRules ?: getEmbeddedServers())?.url?.takeIf { it.isNotBlank() }
        return mapOf(
            "serverUrl" to (selected?.let(::JsonPrimitive) ?: snapshot["serverUrl"] ?: JsonNull.INSTANCE),
            "serverExpireTimeMill" to JsonPrimitive(if (selected == null) 0L else System.currentTimeMillis() + SERVER_EXPIRE_AFTER_TIME),
        )
    }

    private fun commitAutomaticServer(url: String, snapshot: com.google.gson.JsonObject): Boolean {
        return try {
            JuggSettings.commit(mapOf("serverUrl" to JsonPrimitive(url),
                "serverExpireTimeMill" to JsonPrimitive(System.currentTimeMillis() + SERVER_EXPIRE_AFTER_TIME)), snapshot)
            true
        } catch (_: SettingsChangedException) {
            logger.debug("Settings changed during automatic server selection; keep the newer settings.")
            false
        }
    }

    companion object {
        private const val SERVER_EXPIRE_AFTER_TIME = 24 * 60 * 60 * 1000L // 1 day
        private const val SERVER_WILL_NOT_EXPIRE = -1L

        private fun getEmbeddedServers(): List<ServerRule> {
            val serversJson = JuggServerChooser::class.java.classLoader
                .getResourceAsStream("config/servers.json")?.readAllBytes() ?: return emptyList()
            return try {
                Gson().fromJson(String(serversJson), object : TypeToken<List<ServerRule>>() {}.type) ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}
