package com.sickworm.intellij.jugg.compile

import com.sickworm.intellij.jugg.deploy.data.ApkParser
import com.sickworm.intellij.jugg.deploy.data.ParsedApk
import com.sickworm.intellij.jugg.mock.GradleBuildHelper
import com.sickworm.intellij.jugg.mock.projectInfo
import org.jetbrains.kotlin.utils.addToStdlib.measureTimeMillisWithResult
import org.junit.Test
import java.lang.management.ManagementFactory
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
        val parsedApk = ApkParser().parse(projectInfo.apkFile)
        checkApkEntryInfo(parsedApk)
        println("testApkStructure end")
    }

    private fun checkApkEntryInfo(parsedApk: ParsedApk) {
        if (projectInfo.apkEntryInfo.classCount > 0) {
            assertEquals(projectInfo.apkEntryInfo.classCount,
                parsedApk.classes.size)
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

    @Test
    fun testParseApk() {
        println("testApkStructure start")
        System.gc()
        JVMemorySize.printMemory()
        val (costTime, parsedApk) = measureTimeMillisWithResult {
            ApkParser().parse(projectInfo.apkFile)
        }
        System.gc()
        println("testApkStructure end, cost ${costTime}ms")
        JVMemorySize.printMemory()

        println(parsedApk)
    }
}


object JVMemorySize {

    fun printMemory() {
        // 获取MemoryMXBean实例
        val memoryMXBean = ManagementFactory.getMemoryMXBean()

        // 获取堆内存使用情况
        val heapMemoryUsage = memoryMXBean.heapMemoryUsage
        println("Heap Memory:")
        println("   - Initial: " + heapMemoryUsage.init / 1024 / 1024 + "MB")
        println("   - Used: " + heapMemoryUsage.used / 1024 / 1024 + "MB")
        println("   - Committed: " + heapMemoryUsage.committed / 1024 / 1024 + " MB")
        println("   - Max: " + heapMemoryUsage.max / 1024 / 1024 + "MB")

        // 获取非堆内存使用情况
        val nonHeapMemoryUsage = memoryMXBean.nonHeapMemoryUsage
        println("Non-Heap Memory:")
        println("   - Initial: " + nonHeapMemoryUsage.init / 1024 / 1024 + "MB")
        println("   - Used: " + nonHeapMemoryUsage.used / 1024 / 1024 + "MB")
        println("   - Committed: " + nonHeapMemoryUsage.committed / 1024 / 1024 + "MB")
        println("   - Max: " + nonHeapMemoryUsage.max / 1024 / 1024 + "MB")
    }
}
