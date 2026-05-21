package com.sickworm.intellij.jugg.compiler.constref

import com.sickworm.intellij.jugg.mock.logger
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ConstRefAnalyzerTest : ConstRefTempDirCleanupSupport() {
    @Test
    fun `parseReferenceCandidates should tolerate concurrent resetEnvironment calls`() {
        val rootDir = createTempDirectory("const_ref_analyzer_concurrent")
        val file = File(rootDir, "Sample.kt").apply {
            writeText(
                """
                package com.example
                import com.example.other.Other.CONST
                const val LOCAL = 1
                fun use() = CONST + LOCAL
                """.trimIndent()
            )
        }
        val analyzer = ConstRefAnalyzer(logger)
        val pool = Executors.newFixedThreadPool(4)
        val startGate = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        try {
            repeat(40) {
                pool.submit {
                    startGate.await()
                    try {
                        repeat(20) {
                            analyzer.parseReferenceCandidates(listOf(file))
                            analyzer.resetEnvironment()
                        }
                    } catch (t: Throwable) {
                        failure.compareAndSet(null, t)
                    }
                }
            }
            startGate.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
            val error = failure.get()
            if (error != null) {
                throw AssertionError("concurrent parse failed: ${error.message}", error)
            }
        } finally {
            analyzer.dispose()
            pool.shutdownNow()
        }
    }
}
