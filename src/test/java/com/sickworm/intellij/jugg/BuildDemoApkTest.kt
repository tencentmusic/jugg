package com.sickworm.intellij.jugg

import com.android.tools.idea.run.ApkInfo
import com.sickworm.intellij.jugg.compiler.ParsedApk
import com.sickworm.intellij.jugg.deploy.ApkParser
import com.sickworm.intellij.jugg.mock.androidApkPackage
import com.sickworm.intellij.jugg.mock.apkInfo
import com.sickworm.intellij.jugg.mock.assetsAndroidDir
import com.sickworm.intellij.jugg.mock.assetsApkFile
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BuildDemoApkTest {

    private val gradlew = if (isWindows) "cmd.exe /c gradlew" else "./gradlew"

    @Test
    fun testClean() {
        val process = Runtime.getRuntime().exec("$gradlew clean", null, assetsAndroidDir)
        println("\n----------- clean start -----------\n")
        println(String(process.inputStream.readBytes()))
        println()
        println(String(process.errorStream.readBytes()))
        println("\n-----------  clean end  -----------\n")

        assertTrue(!assetsApkFile.exists())
    }

    @Test
    fun testBuildApk() {
        val process = Runtime.getRuntime().exec("$gradlew :app:assembleDebug", null, assetsAndroidDir)
        println("\n----------- assembleDebug start -----------\n")
        println(String(process.inputStream.readBytes()))
        println()
        println(String(process.errorStream.readBytes()))
        println("\n-----------  assembleDebug end  -----------\n")
        process.waitFor()

        assertTrue(assetsApkFile.exists())
    }

    @Test
    fun testCleanAndBuildApk() {
        testClean()
        testBuildApk()
    }

    fun buildApkIfNeeded() {
        if (assetsApkFile.exists()) {
            try {
                testApkStructure()
                println("apk structure is correct, no need to rebuild")
                return
            } catch (e: AssertionError) {
                println("apk structure not correct, rebuild")
            }
        } else {
            println("apk not exists, rebuild")
        }

        testBuildApk()
        testApkStructure()
    }

    private fun testApkStructure() {
        val apkInfo = ApkInfo(
            assetsApkFile,
            androidApkPackage
        )
        val parsedApk = ApkParser().parse(apkInfo)
        checkApkStructure(parsedApk)
    }

    fun checkApkStructure(parsedApk: ParsedApk) {
        assertEquals(apkInfo.classCount, parsedApk.classes.entries.size)
        assertEquals(apkInfo.fieldCount, parsedApk.classes.entries.sumBy { it.value.fields.size })
        assertEquals(apkInfo.methodCount, parsedApk.classes.entries.sumBy { it.value.methods.size })
        assertEquals(apkInfo.overlayFileCount, parsedApk.overlayFiles.size)
    }
}