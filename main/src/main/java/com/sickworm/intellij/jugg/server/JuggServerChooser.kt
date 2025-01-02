package com.sickworm.intellij.jugg.server

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.diagnostic.Logger
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

    private var isSetCustomServer
        get() = JuggSettings.serverExpireTimeMill == SERVER_WILL_NOT_EXPIRE
        set(value) {
            if (value) {
                JuggSettings.serverExpireTimeMill = SERVER_WILL_NOT_EXPIRE
            } else {
                JuggSettings.serverExpireTimeMill = 0
            }
        }

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
    fun updateServerIfExpired() {
        if (isSetCustomServer) {
            return
        }
        val isServerExpired = System.currentTimeMillis() > JuggSettings.serverExpireTimeMill
        if (isServerExpired) {
            logger.debug("Server expired, update server.")
            updateServer(serverRules)
        }
    }

    private fun selectServer(serverRules: List<ServerRule>?): ServerRule? {
        logger.debug("Update server rules: $serverRules")
        if (serverRules.isNullOrEmpty()) {
            logger.debug("No server rule found.")
            return null
        }

        serverRules.forEach { serverRule ->
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
            val reachable = address.isReachable(5000) // 5000毫秒超时时间

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
            content = "Here to set custom server url for redirecting uploading compilation cost, reporting issues etc.",
            defaultInputText = if (isSetCustomServer) JuggSettings.serverUrl else "",
        )
        logger.debug("New server url: $newServerUrl")
        if (newServerUrl == null) {
            logger.debug("User not input server url, skip update.")
            return
        } else if (newServerUrl.isEmpty()) {
            logger.debug("User input empty server url, set to default.")
            isSetCustomServer = false
            updateServerIfExpired()
            return
        } else {
            logger.debug("User input server url, set to custom.")
            JuggSettings.serverUrl = newServerUrl
            isSetCustomServer = true
        }
    }

    companion object {
        private const val SERVER_EXPIRE_AFTER_TIME = 24 * 60 * 60 * 1000L // 1 day
        private const val SERVER_WILL_NOT_EXPIRE = -1L

        private fun getEmbeddedServers(): List<ServerRule> {
            val serversJson = JuggServerChooser::class.java.classLoader
                .getResourceAsStream("config/servers.json")!!.readAllBytes()
            return Gson().fromJson(String(serversJson), object : TypeToken<List<ServerRule>>() {}.type)
        }
    }
}