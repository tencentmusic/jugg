package com.sickworm.intellij.jugg

import com.sickworm.intellij.jugg.compiler.ParsedApk
import com.sickworm.intellij.jugg.deploy.ApkParser
import com.sickworm.intellij.jugg.mock.*
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BuildDemoApkTest {

    @Test
    fun testHeapSpace() {
        // Get maximum size of heap in bytes. The heap cannot grow beyond this size.// Any attempt will result in an OutOfMemoryException.
        val heapMaxSize = Runtime.getRuntime().maxMemory()
        val heapSize = Runtime.getRuntime().totalMemory()
        // Get amount of free memory within the heap in bytes. This size will increase // after garbage collection and decrease as new objects are created.
        val heapFreeSize = Runtime.getRuntime().freeMemory()
        println("heap max size: " + formatSize(heapMaxSize))
        println("heap size: " + formatSize(heapSize))
        println("heap free size: " + formatSize(heapFreeSize))
    }

    @Test
    fun testClean() {
        GradleBuildHelper.clean()
        assertTrue(!projectInfo.apkFile.exists())
    }

    @Test
    fun testBuildApk() {
        GradleBuildHelper.appAssembleDebug()
        assertTrue(projectInfo.apkFile.exists())
    }

    @Test
    fun testCleanAndBuildApk() {
        testClean()
        testBuildApk()
    }

    fun buildApkIfNeeded() {
        if (projectInfo.apkFile.exists()) {
            try {
                checkApkEntryInfo()
                println("apk structure is correct, no need to rebuild")
                return
            } catch (e: AssertionError) {
                println("apk structure not correct, rebuild")
            }
        } else {
            println("apk not exists, rebuild")
        }

        testBuildApk()
        try {
            checkApkEntryInfo()
        } catch (e: AssertionError) {
            println("apk structure not correct, clean and rebuild")
            testCleanAndBuildApk()
            checkApkEntryInfo()
        }
    }

    private fun checkApkEntryInfo() {
        if (!projectInfo.apkEntryInfo.isNeedCheck) {
            println("testApkStructure no need to check")
            return
        }
        println("testApkStructure start")
        val parsedApk = ApkParser().parse(projectInfo.apkInfo, true)
        checkApkEntryInfo(parsedApk)
        println("testApkStructure end")
    }

    private fun checkApkEntryInfo(parsedApk: ParsedApk) {
        if (projectInfo.apkEntryInfo.classCount > 0) {
            assertEquals(projectInfo.apkEntryInfo.classCount,
                parsedApk.classes.entries.size)
        }
        if (projectInfo.apkEntryInfo.fieldCount > 0) {
            assertEquals(projectInfo.apkEntryInfo.fieldCount,
                parsedApk.classes.entries.sumBy { it.value.fields.size })
        }
        if (projectInfo.apkEntryInfo.methodCount > 0) {
            assertEquals(projectInfo.apkEntryInfo.methodCount,
                parsedApk.classes.entries.sumBy { it.value.methods.size })
        }
        if (projectInfo.apkEntryInfo.overlayFileCount > 0) {
            assertEquals(projectInfo.apkEntryInfo.overlayFileCount,
                parsedApk.overlayFiles.size)
        }
    }

    private fun formatSize(v: Long): String? {
        if (v < 1024) return "$v B"
        val z = (63 - java.lang.Long.numberOfLeadingZeros(v)) / 10
        return String.format("%.1f %sB", v.toDouble() / (1L shl z * 10), " KMGTPE"[z])
    }
}