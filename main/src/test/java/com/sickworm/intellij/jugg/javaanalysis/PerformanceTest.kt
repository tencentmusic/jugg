package com.sickworm.intellij.jugg.javaanalysis

import com.sickworm.intellij.jugg.mock.TestGlobal
import com.sickworm.intellij.jugg.javaanalysis.analyzer.JDTAnalyzer
import com.sickworm.intellij.jugg.javaanalysis.analyzer.JavaParserAnalyzer
import org.junit.Test
import java.lang.management.ManagementFactory
import java.nio.file.Path

class PerformanceTest {

    @Test
    @Throws(Exception::class)
    fun benchmarkJavaParser() {
        warm(JavaParserAnalyzer(TEST_DIR))
        val mem = ManagementFactory.getMemoryMXBean()
        val before = mem.heapMemoryUsage.used
        val t = System.nanoTime()
        val analyzer = JavaParserAnalyzer(TEST_DIR)
        val results = analyzer.analyze()
        val took = System.nanoTime() - t
        val after = mem.heapMemoryUsage.used
        System.out.printf(
            "JavaParser: %d files (total=%d), %d ms, heap Δ=%d KB%n",
            results.size, analyzer.totalFiles, took / 1000000, (after - before) / 1024
        )
    }

    @Test
    @Throws(Exception::class)
    fun benchmarkJDT() {
        warm(JDTAnalyzer(TEST_DIR))
        val mem = ManagementFactory.getMemoryMXBean()
        val before = mem.heapMemoryUsage.used
        val t = System.nanoTime()
        val analyzer = JDTAnalyzer(TEST_DIR)
        val results = analyzer.analyze()
        val took = System.nanoTime() - t
        val after = mem.heapMemoryUsage.used
        System.out.printf(
            "JDT:        %d files (total=%d), %d ms, heap Δ=%d KB%n",
            results.size, analyzer.totalFiles, took / 1000000, (after - before) / 1024
        )
    }

    @Throws(Exception::class)
    private fun warm(analyzer: Any) {
        if (analyzer is JavaParserAnalyzer) {
            analyzer.analyze()
        } else {
            (analyzer as JDTAnalyzer).analyze()
        }
    }

    companion object {
        private val TEST_DIR: Path = TestGlobal.projectRootDir.toPath()
    }
}