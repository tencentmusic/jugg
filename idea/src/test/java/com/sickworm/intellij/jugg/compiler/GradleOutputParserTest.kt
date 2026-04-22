package com.sickworm.intellij.jugg.compiler

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.DumbProgressIndicator
import com.sickworm.intellij.jugg.ide.bean.IProcessHandler
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import org.junit.Assert
import org.junit.Test
import org.mockito.Mockito

class GradleOutputParserTest {

    private fun buildParser(): GradleOutputParser {
        val options = Mockito.mock(JuggGradleCompileOptions::class.java)
        Mockito.`when`(options.isRemoteCompile).thenReturn(false)
        return GradleOutputParser(
            juggGradleCompileOptions = options,
            processHandler = IProcessHandler.DEFAULT,
            indicator = DumbProgressIndicator.INSTANCE,
            logger = Logger.getInstance("GradleOutputParserTest"),
        )
    }

    @Test
    fun testJavaCompilerErrorLineIsCollected() {
        val parser = buildParser()
        parser.onOutput("/src/Foo.java:10: error: cannot find symbol", isNeedPrint = false)
        parser.onOutput("        AppCore.getMusicPlayer().play();", isNeedPrint = false)

        Assert.assertTrue(
            "Java error line should be in possibleErrorLog",
            parser.possibleErrorLog.any { it.contains(": error:") }
        )
    }

    @Test
    fun testMultipleJavaErrorLinesAreAllCollected() {
        val parser = buildParser()
        val errorLines = listOf(
            "/src/Foo.java:10: error: cannot find symbol",
            "/src/Bar.java:20: error: incompatible types",
            "/src/Baz.java:30: error: method does not override",
        )
        errorLines.forEach { parser.onOutput(it, isNeedPrint = false) }

        Assert.assertEquals(
            "All 3 Java error lines should be collected",
            3,
            parser.possibleErrorLog.count { it.contains(": error:") }
        )
    }

    @Test
    fun testKotlinErrorLineIsCollected() {
        val parser = buildParser()
        parser.onOutput("e: /src/Foo.kt: (10, 5): Unresolved reference: AppCore", isNeedPrint = false)

        Assert.assertTrue(
            "Kotlin e: error line should be in possibleErrorLog",
            parser.possibleErrorLog.any { it.startsWith("e:") }
        )
    }

    @Test
    fun testGradleTaskFailedLineIsCollected() {
        val parser = buildParser()
        parser.onOutput("> Task :wemusic:compileDebugJavaWithJavac FAILED", isNeedPrint = false)

        Assert.assertTrue(
            "FAILED task line should be in possibleErrorLog",
            parser.possibleErrorLog.any { it.contains("FAILED") }
        )
    }

    @Test
    fun testWhatWentWrongBlockIsCollected() {
        val parser = buildParser()
        parser.onOutput("* What went wrong:", isNeedPrint = false)
        parser.onOutput("Execution failed for task ':app:compileDebugKotlin'.", isNeedPrint = false)
        parser.onOutput("> Compilation failed; see the compiler error output for details.", isNeedPrint = false)
        parser.onOutput("* Try:", isNeedPrint = false)

        Assert.assertTrue(
            "What went wrong block should be in possibleErrorLog",
            parser.possibleErrorLog.any { it.contains("What went wrong") }
        )
    }

    @Test
    fun testJavaErrorContextCollectedUntilNonIndentedLine() {
        // Context lines (indented) are collected; non-indented line terminates collection
        val parser = buildParser()
        parser.onOutput("/src/AutoContentManager.java:281: error: cannot find symbol", isNeedPrint = false)
        parser.onOutput("                AppCore.getMusicPlayer().setCommonMusicPlayList(list, 0);", isNeedPrint = false)
        parser.onOutput("                ^", isNeedPrint = false)
        parser.onOutput("  symbol:   variable AppCore", isNeedPrint = false)
        parser.onOutput("  location: class AutoContentManager", isNeedPrint = false)
        parser.onOutput("Note: Some input files use unchecked operations.", isNeedPrint = false)

        Assert.assertEquals("error line + 4 indented context lines should be collected", 5, parser.possibleErrorLog.size)
        Assert.assertTrue(parser.possibleErrorLog[0].contains(": error:"))
        Assert.assertTrue(parser.possibleErrorLog[2].trim() == "^")
        Assert.assertTrue(parser.possibleErrorLog[3].contains("symbol:"))
        Assert.assertTrue(parser.possibleErrorLog[4].contains("location:"))
    }

    @Test
    fun testJavaErrorContextStopsAtNextErrorBlock() {
        val parser = buildParser()
        // First error block
        parser.onOutput("/src/Foo.java:10: error: cannot find symbol", isNeedPrint = false)
        parser.onOutput("        AppCore.play();", isNeedPrint = false)
        parser.onOutput("        ^", isNeedPrint = false)
        parser.onOutput("  symbol:   variable AppCore", isNeedPrint = false)
        parser.onOutput("  location: class Foo", isNeedPrint = false)
        // Second error block starts with non-indented line → first block context ends, second begins
        parser.onOutput("/src/Bar.java:20: error: incompatible types", isNeedPrint = false)
        parser.onOutput("        String s = 42;", isNeedPrint = false)
        parser.onOutput("                   ^", isNeedPrint = false)
        parser.onOutput("  symbol:   int", isNeedPrint = false)
        parser.onOutput("  location: class Bar", isNeedPrint = false)

        Assert.assertEquals("Both complete error blocks (10 lines) should be collected", 10, parser.possibleErrorLog.size)
    }

    @Test
    fun testJavaErrorContextStopsAtEmptyLine() {
        val parser = buildParser()
        parser.onOutput("/src/Foo.java:10: error: cannot find symbol", isNeedPrint = false)
        parser.onOutput("        AppCore.play();", isNeedPrint = false)
        parser.onOutput("", isNeedPrint = false) // empty line = non-indented → terminates context
        parser.onOutput("        this line should not be collected", isNeedPrint = false)

        Assert.assertEquals("Context should stop at empty line", 2, parser.possibleErrorLog.size)
    }

    @Test
    fun testNormalOutputLineIsNotCollected() {
        val parser = buildParser()
        parser.onOutput("Note: Some input files use unchecked or unsafe operations.", isNeedPrint = false)
        parser.onOutput("> Task :app:compileDebugKotlin UP-TO-DATE", isNeedPrint = false)
        parser.onOutput("BUILD SUCCESSFUL in 10s", isNeedPrint = false)

        Assert.assertTrue(
            "Normal output lines should not be collected into possibleErrorLog",
            parser.possibleErrorLog.isEmpty()
        )
    }
}
