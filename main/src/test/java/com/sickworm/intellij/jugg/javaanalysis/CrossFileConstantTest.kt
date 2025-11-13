package com.sickworm.intellij.jugg.javaanalysis

import com.sickworm.intellij.jugg.mock.TestGlobal
import com.sickworm.intellij.jugg.javaanalysis.analyzer.JDTAnalyzer
import com.sickworm.intellij.jugg.javaanalysis.analyzer.JavaParserAnalyzer
import org.junit.Assert
import org.junit.Test
import java.nio.file.Path
import kotlin.test.assertTrue

class CrossFileConstantTest {
    @Test
    @Throws(Exception::class)
    fun javaParserShouldFindABCReferenceToMainActivity2() {
        val results = JavaParserAnalyzer(TEST_DIR).analyze()
        val abc = results.stream()
            .filter { r: JavaParserAnalyzer.Result? -> r!!.file.fileName.toString() == "ABC.java" }
            .findFirst().orElse(null)
        Assert.assertNotNull("ABC.java should be recognized as referencing external constants", abc)
        assertTrue(
            abc!!.references.stream()
                .anyMatch { ref: JavaParserAnalyzer.Reference -> ref.owner.endsWith("MainActivity2") },
            "ABC should reference constants from MainActivity2"
        )
    }

    @Test
    @Throws(Exception::class)
    fun jdtShouldFindABCReferenceToMainActivity2() {
        val results = JDTAnalyzer(TEST_DIR).analyze()
        val abc = results.stream()
            .filter { r: JDTAnalyzer.Result? -> r!!.file.fileName.toString() == "ABC.java" }
            .findFirst().orElse(null)
        Assert.assertNotNull("ABC.java should be recognized as referencing external constants", abc)
        assertTrue(
            abc!!.references.stream()
                .anyMatch { ref: JDTAnalyzer.Reference -> ref.owner.endsWith("MainActivity2") },
            "ABC should reference constants from MainActivity2"
        )
    }

    companion object {
        private val TEST_DIR: Path =
            TestGlobal.projectRootDir.toPath()
    }
}