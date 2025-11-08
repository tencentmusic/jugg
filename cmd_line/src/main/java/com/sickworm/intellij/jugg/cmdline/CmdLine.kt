package com.sickworm.intellij.jugg.cmdline

import com.sickworm.intellij.jugg.cmdline.incremental.BuildIncrementalApkCommand
import com.sickworm.intellij.jugg.cmdline.base.BuildGradleBaseCommand
import com.sickworm.intellij.jugg.cmdline.logger.CmdLineLogger
import com.sickworm.intellij.jugg.platform.PlatformApi
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val result = CmdLine().run(args)
    if (!result) {
        exitProcess(-1)
    }
}

class CmdLine {

    companion object {
        init {
            PlatformApi.impl = CmdPlatformApi()
        }
    }

    fun run(args: Array<String>): Boolean {
        println("Welcome to Jugg cmdline! args:${args.toList()}")

        val cmd = args.find { it.startsWith("cmd=") }?.substringAfter("cmd=")
        val result = when (cmd) {
            Command.BUILD_INCREMENTAL_APK.value -> {
                CmdLineLogger.stdLogger.info("Going to run cmd: buildIncrementalApk.")
                BuildIncrementalApkCommand.run(args)
            }
            Command.BUILD_GRADLE_BASE.value -> {
                CmdLineLogger.stdLogger.info("Going to run cmd: buildGradleBase.")
                BuildGradleBaseCommand.run(args)
            }
            null -> {
                CmdLineLogger.stdLogger.warn("No cmd specified, exit.")
                false
            }
            else -> {
                CmdLineLogger.stdLogger.warn("unknown cmd:$cmd")
                false
            }
        }
        println("Jugg cmdline exit. result: $result")
        return result
    }

    enum class Command(
        val value: String,
    ) {
        BUILD_INCREMENTAL_APK("buildIncrementalApk"),
        BUILD_GRADLE_BASE("buildGradleBase"),
        ;
    }
}
