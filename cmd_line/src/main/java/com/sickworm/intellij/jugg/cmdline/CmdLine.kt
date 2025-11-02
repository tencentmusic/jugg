package com.sickworm.intellij.jugg.cmdline

import com.sickworm.intellij.jugg.cmdline.incremental.BuildIncrementalApkCommand
import com.sickworm.intellij.jugg.cmdline.base.BuildGradleBaseCommand
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val result = CmdLine().run(args)
    if (!result) {
        exitProcess(-1)
    }
}

class CmdLine {

    fun run(args: Array<String>): Boolean {
        println("Welcome to Jugg cmdline! args:${args.toList()}")
        val cmd = args.find { it.startsWith("cmd=") }?.substringAfter("cmd=")

        return when (cmd) {
            "buildIncrementalApk" -> {
                BuildIncrementalApkCommand.run(args)
            }
            "buildGradleBase" -> {
                BuildGradleBaseCommand.run(args)
            }
            null -> {
                println("No cmd specified")
                false
            }
            else -> {
                println("unknown cmd:$cmd")
                false
            }
        }
    }
}
