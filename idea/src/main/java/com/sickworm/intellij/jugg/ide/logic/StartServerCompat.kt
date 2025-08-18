package com.sickworm.intellij.jugg.ide.logic

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.PluginId
import com.sickworm.intellij.jugg.rpc.RpcLocalServer

object StartServerCompat {

    fun startRpcServerHereOnLowVersion(logger: Logger) {
        val installedVersion = PluginManagerCore
            .getPlugin(PluginId.getId("com.sickworm.intellij.jugg"))
            ?.version
        val isStartServerCompat = installedVersion != null && PluginVersionComparator.compare(installedVersion, "3.4.0") < 0
        logger.debug("installedVersion $installedVersion, isStartServerCompat $isStartServerCompat")
        // if installed version is lower than 3.4.0, start rpc server here
        // if installed version is higher than 3.4.0, start rpc server in [JuggInitializer.init]
        if (isStartServerCompat) {
            RpcLocalServer.start()
        }
    }


    
}