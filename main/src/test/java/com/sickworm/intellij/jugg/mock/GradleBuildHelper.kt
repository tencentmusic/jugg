package com.sickworm.intellij.jugg.mock

import com.sickworm.intellij.jugg.compiler.isWindows

object GradleBuildHelper {

    private val gradlew = if (isWindows) "cmd.exe /c gradlew" else "./gradlew"

    fun clean() {
        val process = Runtime.getRuntime().exec("$gradlew clean", null, TestGlobal.projectRootDir)
        println("\n----------- clean start -----------\n")
        println(String(process.inputStream.readBytes()))
        println()
        println(String(process.errorStream.readBytes()))
        println("\n-----------  clean end  -----------\n")
        val result = process.waitFor()
        if (result != 0) {
            throw IllegalStateException("clean failed, see log for details")
        }
    }

    fun appAssembleDebug(initScriptPath: String? = AssembleAndroidProjectOnce.scriptFile.absolutePath) {
        val initArg = if (initScriptPath == null) "" else "-I $initScriptPath"
        val process = Runtime.getRuntime().exec("$gradlew :app:assembleDebug $initArg", null, TestGlobal.projectRootDir)
        println("\n----------- assembleDebug start -----------\n")
        println(String(process.inputStream.readBytes()))
        println()
        println(String(process.errorStream.readBytes()))
        println("\n-----------  assembleDebug end  -----------\n")
        val result = process.waitFor()
        if (result != 0) {
            throw IllegalStateException("assembleDebug failed, see log for details")
        }
    }

    fun appAssembleRelease(initScriptPath: String? = AssembleAndroidProjectOnce.scriptFile.absolutePath) {
        val initArg = if (initScriptPath == null) "" else "-I $initScriptPath"
        val process = Runtime.getRuntime().exec("$gradlew :app:assembleRelease $initArg", null, TestGlobal.projectRootDir)
        println("\n----------- assembleRelease start -----------\n")
        println(String(process.inputStream.readBytes()))
        println()
        println(String(process.errorStream.readBytes()))
        println("\n-----------  assembleRelease end  -----------\n")
        val result = process.waitFor()
        if (result != 0) {
            throw IllegalStateException("assembleRelease failed, see log for details")
        }
    }
}