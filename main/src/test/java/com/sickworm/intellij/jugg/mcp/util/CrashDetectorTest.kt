package com.sickworm.intellij.jugg.mcp.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CrashDetectorTest verifies crash signal classification and snippet extraction.
 */
class CrashDetectorTest {

    // --- classify ---

    @Test
    fun testFatalExceptionIsStrong() {
        val line = "04-19 10:00:00.000  1234  1234 E AndroidRuntime: FATAL EXCEPTION: main"
        assertEquals(CrashSignal.STRONG, CrashDetector.classify(line))
    }

    @Test
    fun testFatalSignalIsStrong() {
        val line = "04-19 10:00:00.000  1234  1234 F libc: Fatal signal 11 (SIGSEGV)"
        assertEquals(CrashSignal.STRONG, CrashDetector.classify(line))
    }

    @Test
    fun testBacktraceIsWeak() {
        val line = "04-19 10:00:00.000  1234  1234 F DEBUG: backtrace:"
        assertEquals(CrashSignal.WEAK, CrashDetector.classify(line))
    }

    @Test
    fun testProcessLineIsWeak() {
        val line = "04-19 10:00:00.000  1234  1234 E AndroidRuntime: Process: com.example.app, PID: 1234"
        assertEquals(CrashSignal.WEAK, CrashDetector.classify(line))
    }

    @Test
    fun testNormalLineIsNone() {
        val line = "04-19 10:00:00.000  1234  1234 I MyTag: [JUGG_AR] DONE"
        assertEquals(CrashSignal.NONE, CrashDetector.classify(line))
    }

    @Test
    fun testEmptyLineIsNone() {
        assertEquals(CrashSignal.NONE, CrashDetector.classify(""))
    }

    // --- CRASH_TAGS ---

    @Test
    fun testCrashTagsContainsAndroidRuntime() {
        assertTrue(CrashDetector.CRASH_TAGS.contains("AndroidRuntime"))
    }

    @Test
    fun testCrashTagsContainsLibc() {
        assertTrue(CrashDetector.CRASH_TAGS.contains("libc"))
    }

    @Test
    fun testCrashTagsContainsDEBUG() {
        assertTrue(CrashDetector.CRASH_TAGS.contains("DEBUG"))
    }

    // --- extractSnippet ---

    @Test
    fun testExtractSnippetReturnsLinesAfterHit() {
        val lines = listOf(
            "04-19 10:00:00.000  1234  1234 I Normal: before crash",
            "04-19 10:00:00.000  1234  1234 E AndroidRuntime: FATAL EXCEPTION: main",
            "04-19 10:00:00.000  1234  1234 E AndroidRuntime: java.lang.NullPointerException",
            "04-19 10:00:00.000  1234  1234 E AndroidRuntime:     at com.example.Foo.bar(Foo.kt:42)",
        )
        val snippet = CrashDetector.extractSnippet(lines, 1)
        assertTrue(snippet.contains("FATAL EXCEPTION"))
        assertTrue(snippet.contains("NullPointerException"))
    }

    @Test
    fun testExtractSnippetCapsAt80Lines() {
        val lines = (0..100).map { i ->
            "04-19 10:00:00.000  1234  1234 E AndroidRuntime: line $i"
        }
        val snippet = CrashDetector.extractSnippet(lines, 0)
        val snipLines = snippet.split("\n").filter { it.isNotBlank() }
        assertTrue("snippet should be at most 80 lines", snipLines.size <= 80)
    }

    @Test
    fun testExtractSnippetStopsAtBufferBoundary() {
        val lines = listOf(
            "04-19 10:00:00.000  1234  1234 E AndroidRuntime: FATAL EXCEPTION: main",
            "--------- beginning of crash",
            "04-19 10:00:00.000  9999  9999 E Other: another crash",
        )
        val snippet = CrashDetector.extractSnippet(lines, 0)
        assertFalse("should not cross buffer boundary", snippet.contains("another crash"))
    }

    @Test
    fun testExtractSnippetFromMiddleIndex() {
        val lines = (0 until 20).map { i ->
            "04-19 10:00:0${i / 10}.00${i % 10}  1234  1234 E AndroidRuntime: line $i"
        }
        val snippet = CrashDetector.extractSnippet(lines, 10)
        assertTrue(snippet.contains("line 10"))
        assertFalse("should not include lines before hit index", snippet.split("\n").any { it.contains("line 9") && !it.contains("line 9") })
    }
}
