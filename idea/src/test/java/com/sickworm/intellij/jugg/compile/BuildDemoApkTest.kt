package com.sickworm.intellij.jugg.compile

import com.sickworm.intellij.jugg.compiler.obfuscation.ClassObfuscator
import com.sickworm.intellij.jugg.compiler.obfuscation.R8MappingReader
import com.sickworm.intellij.jugg.deploy.data.ApkParser
import com.sickworm.intellij.jugg.deploy.data.ParsedApk
import com.sickworm.intellij.jugg.mock.GradleBuildHelper
import com.sickworm.intellij.jugg.mock.TestGlobal
import com.sickworm.intellij.jugg.mock.projectInfo
import com.sun.management.HotSpotDiagnosticMXBean
import org.jetbrains.kotlin.utils.addToStdlib.measureTimeMillisWithResult
import org.junit.Test
import java.io.File
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
    fun testBuildApkRelease() {
        GradleBuildHelper.appAssembleRelease()
        val releaseApkFile = File(projectInfo.apkFile.absolutePath.replace("debug", "release"))
        assertTrue(releaseApkFile.exists())
    }

    @Test
    fun testCleanAndBuildApk() {
        testClean()
        testBuildApk()
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

    @Test
    fun testParseMapping() {
        println("testParseMapping start")
        val mappingFile = File(TestGlobal.projectInfo.projectRootDir, "build/app/outputs/mapping/release/mapping.txt")
        if (!mappingFile.exists()) {
            testBuildApkRelease()
        }
        assertTrue(mappingFile.exists())

        System.gc()
        JVMemorySize.printMemory(isStart = true)
        val (costTime, classObfuscator) = measureTimeMillisWithResult {
//            val reader = R8MappingReader.fromFile(mappingFile) // this will make gc not clean :(
            ClassObfuscator(R8MappingReader.fromFile(mappingFile))
        }
        System.gc()
        JVMemorySize.printMemory(isStart = false)
        println("testParseMapping end, cost ${costTime}ms")

        println(classObfuscator)
//        JVMemorySize.dumpHeap("jvm_heap_dump.hprof", true)
    }
}


object JVMemorySize {

    private var startUseHeapMb = 0L

    fun printMemory(isStart: Boolean = true) {
        // 获取MemoryMXBean实例
        val memoryMXBean = ManagementFactory.getMemoryMXBean()

        // 获取堆内存使用情况
        val heapMemoryUsage = memoryMXBean.heapMemoryUsage
        println("Heap Memory:")
        println("   - Initial: " + heapMemoryUsage.init / 1024 / 1024 + "MB")
        println("   - Used: " + heapMemoryUsage.used / 1024 / 1024 + "MB")
        println("   - Committed: " + heapMemoryUsage.committed / 1024 / 1024 + " MB")
        println("   - Max: " + heapMemoryUsage.max / 1024 / 1024 + "MB")
        val useHeapMb = heapMemoryUsage.used / 1024 / 1024

        // 获取非堆内存使用情况
        val nonHeapMemoryUsage = memoryMXBean.nonHeapMemoryUsage
        println("Non-Heap Memory:")
        println("   - Initial: " + nonHeapMemoryUsage.init / 1024 / 1024 + "MB")
        println("   - Used: " + nonHeapMemoryUsage.used / 1024 / 1024 + "MB")
        println("   - Committed: " + nonHeapMemoryUsage.committed / 1024 / 1024 + "MB")
        println("   - Max: " + nonHeapMemoryUsage.max / 1024 / 1024 + "MB")

        if (!isStart) {
            val increaseMb = useHeapMb - startUseHeapMb
            println("increaseMb: ${increaseMb}MB, ${startUseHeapMb}MB -> ${useHeapMb}MB")
        } else {
            startUseHeapMb = useHeapMb
        }
    }

    fun dumpHeap(filePath: String, live: Boolean) {
        File(filePath).delete()
        val server = ManagementFactory.getPlatformMBeanServer()
        val mxBean = ManagementFactory.newPlatformMXBeanProxy(
            server,
            "com.sun.management:type=HotSpotDiagnostic",
            HotSpotDiagnosticMXBean::class.java
        )
        mxBean.dumpHeap(filePath, live)
        println("Heap dump created at: $filePath")
    }
}
