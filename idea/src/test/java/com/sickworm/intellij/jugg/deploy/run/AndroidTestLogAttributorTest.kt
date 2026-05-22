package com.sickworm.intellij.jugg.deploy.run

import com.sickworm.intellij.jugg.deploy.run.instrument.AndroidTestLogAttributor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidTestLogAttributorTest {

    @Test
    fun `buffers method window before filtering by pid`() {
        val outputs = mutableListOf<MethodLogLine>()
        val attributor = AndroidTestLogAttributor { className, testName, line ->
            outputs.add(MethodLogLine(className, testName, line))
        }

        attributor.onTestStarted("com.example.FooTest", "testBar")
        attributor.onLogLine("05-17 15:58:58.061 24834 24851 I Foo: delayed test process")
        attributor.onLogLine("05-17 15:58:58.062  9503  9509 W .apps.wellbeing: delayed noise")
        attributor.setAllowedPids(setOf(24834))
        attributor.onTestFinished("com.example.FooTest", "testBar")
        attributor.finish()

        assertEquals(1, outputs.size)
        assertEquals("com.example.FooTest", outputs.single().className)
        assertEquals("testBar", outputs.single().testName)
        assertTrue(outputs.single().line.contains("Foo: delayed test process"))
    }

    @Test
    fun `next test start closes previous buffered window`() {
        val outputs = mutableListOf<MethodLogLine>()
        val attributor = AndroidTestLogAttributor { className, testName, line ->
            outputs.add(MethodLogLine(className, testName, line))
        }
        attributor.setAllowedPids(setOf(1234))

        attributor.onTestStarted("com.example.FooTest", "testA")
        attributor.onLogLine("05-17 15:58:58.061 1234 1234 I Foo: a")
        attributor.onTestFinished("com.example.FooTest", "testA")
        attributor.onLogLine("05-17 15:58:58.062 1234 1234 I Foo: a delayed")
        attributor.onTestStarted("com.example.FooTest", "testB")
        attributor.onLogLine("05-17 15:58:58.063 1234 1234 I Foo: b")
        attributor.onTestFinished("com.example.FooTest", "testB")
        attributor.finish()

        val detailA = outputs.filter { it.testName == "testA" }.joinToString("\n") { it.line }
        val detailB = outputs.filter { it.testName == "testB" }.joinToString("\n") { it.line }
        assertTrue(detailA.contains("Foo: a"))
        assertTrue(detailA.contains("Foo: a delayed"))
        assertTrue(detailB.contains("Foo: b"))
        assertEquals(3, outputs.size)
    }

    @Test
    fun `finish reports bounded buffer stats and releases lines`() {
        val attributor = AndroidTestLogAttributor(maxBufferedLogcatLines = 2) { _, _, _ -> }

        attributor.onLogLine("05-17 15:58:58.061 1234 1234 I Foo: one")
        attributor.onLogLine("05-17 15:58:58.062 1234 1234 I Foo: two")
        attributor.onLogLine("05-17 15:58:58.063 1234 1234 I Foo: three")

        val stats = attributor.finish()
        val statsAfterRelease = attributor.finish()

        assertEquals(2, stats.lineCount)
        assertTrue(stats.byteSize > 0)
        assertEquals(3L, stats.totalLineCount)
        assertEquals(1L, stats.truncatedLineCount)
        assertEquals(2, stats.maxLines)
        assertEquals(0, statsAfterRelease.lineCount)
        assertEquals(0L, statsAfterRelease.byteSize)
    }

    private data class MethodLogLine(
        val className: String,
        val testName: String,
        val line: String,
    )
}
