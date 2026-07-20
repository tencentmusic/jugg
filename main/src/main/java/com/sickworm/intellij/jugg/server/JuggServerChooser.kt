package com.sickworm.intellij.jugg.server

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.logger.getInstance
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

    private var isSetCustomServer
        get() = JuggSettings.serverExpireTimeMill == SERVER_WILL_NOT_EXPIRE
        set(value) {
            if (value) {
                JuggSettings.serverExpireTimeMill = SERVER_WILL_NOT_EXPIRE
            } else {
                JuggSettings.serverExpireTimeMill = 0
            }
        }

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
            JuggSettings.serverUrl = newServerUrl
        }
        JuggSettings.serverExpireTimeMill = System.currentTimeMillis() + SERVER_EXPIRE_AFTER_TIME
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

        val nextServerUrl = selectServer(serverRules, forbidUrls + forbidUrl)?.url
        if (nextServerUrl.isNullOrBlank() || nextServerUrl == forbidUrl) {
            logger.debug("No available alternative server for $forbidUrl, keep current server.")
            return false
        }

        forbidUrls.add(forbidUrl)
        logger.debug("Update server with forbid url: $forbidUrl")
        logger.debug("Update server from $forbidUrl to $nextServerUrl")
        JuggSettings.serverUrl = nextServerUrl
        JuggSettings.serverExpireTimeMill = System.currentTimeMillis() + SERVER_EXPIRE_AFTER_TIME
        return true
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

    fun setCustomServer(newServerUrl: String) {
        val normalizedUrl = newServerUrl.trim()
        logger.debug("New server url: $normalizedUrl")
        if (normalizedUrl.isEmpty()) {
            logger.debug("User input empty server url, set to default.")
            isSetCustomServer = false
            updateServerIfExpired()
            return
        } else {
            logger.debug("User input server url, set to custom.")
            JuggSettings.serverUrl = normalizedUrl
            isSetCustomServer = true
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
