package com.sickworm.intellij.jugg.mock

import com.sickworm.intellij.jugg.compiler.isWindows

object GradleBuildHelper {

    private val gradlew = if (isWindows) "cmd.exe /c gradlew" else "./gradlew"

    fun clean() {
        val process = Runtime.getRuntime().exec("$gradlew clean", null, assetsAndroidDir)
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

    fun appAssembleDebug(initScriptPath: String? = null) {
        val command = if (isWindows) {
            mutableListOf("cmd.exe", "/c", "gradlew")
        } else {
            mutableListOf("./gradlew")
        }
        command += listOf(":app:assembleDebug", "--no-daemon")
        if (initScriptPath != null) {
            command += listOf("-I", initScriptPath)
        }
        val process = ProcessBuilder(command)
            .directory(assetsAndroidDir)
            .redirectErrorStream(true)
            .start()
        println("\n----------- assembleDebug start -----------\n")
        println(String(process.inputStream.readBytes()))
        println("\n-----------  assembleDebug end  -----------\n")
        val result = process.waitFor()
        if (result != 0) {
            throw IllegalStateException("assembleDebug failed, see log for details")
        }
    }

    fun appAssembleRelease(initScriptPath: String? = null) {
        val initArg = if (initScriptPath == null) "" else "-I $initScriptPath"
        val process = Runtime.getRuntime().exec("$gradlew :app:assembleRelease --no-daemon $initArg", null, assetsAndroidDir)
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

    /**
     * Switch Kotlin version using the switch-kotlin-version.sh script
     * @param version "1.7" for KSP1, "2.1" for KSP2
     */
    fun switchKotlinVersion(version: String) {
        val scriptPath = "${assetsAndroidDir.absolutePath}/switch-kotlin-version.sh"
        val process = Runtime.getRuntime().exec("bash $scriptPath $version", null, assetsAndroidDir)
        println("\n----------- switch kotlin version to $version start -----------\n")
        println(String(process.inputStream.readBytes()))
        println()
        println(String(process.errorStream.readBytes()))
        println("\n----------- switch kotlin version to $version end -----------\n")
        val result = process.waitFor()
        if (result != 0) {
            throw IllegalStateException("switch kotlin version failed, see log for details")
        }
    }

}
