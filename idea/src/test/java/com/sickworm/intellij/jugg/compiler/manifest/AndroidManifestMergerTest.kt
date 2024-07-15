package com.sickworm.intellij.jugg.compiler.manifest

import com.sickworm.intellij.jugg.mock.buildDir
import com.sickworm.intellij.jugg.mock.logger
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidManifestMergerTest : ManifestDifferTest() {

    @Test
    override fun testFileEquals() {
        val changedManifestFile = ChangedManifestFile(mergedFile, mergedFile)
        val outputFile = File(buildDir, "out/AndroidManifest.xml")
        AndroidManifestMerger(logger).merge(mergedFile, listOf(changedManifestFile), outputFile)

        val newLines = XmlParser().parse(outputFile).printXml().lines()
        val oldLines = XmlParser().parse(mergedFile).printXml().lines()
        newLines.forEachIndexed { index, line ->
            assertEquals(line, oldLines[index],
                "Line: $index, context:\n${
                    newLines
                        .subListSafe(index - 2, index + 2)
                        .mapIndexed { contextIndex, string -> "${index - 2 + contextIndex}: $string" }
                        .joinToString("\n")
                }")
        }
        assertEquals(newLines.size, newLines.size)
    }

    override fun diff(newXml: String, oldXml: String, expectDiffResult: String): ManifestDiffResult.DiffElement {
        val diffResult = super.diff(newXml, oldXml, expectDiffResult)

        val oldFullNode = XmlParser().parse(mergedFile)
        val newFullNode = XmlParser().parse(mergedFile)
        AndroidManifestMerger(logger).merge(newFullNode, listOf(diffResult))
        File(buildDir, "out/AndroidManifest.xml").writeText(newFullNode.printXml())
        super.diff(newFullNode.printXml(), oldFullNode.printXml(), expectDiffResult)

        return diffResult
    }

    private fun <T> List<T>.subListSafe(fromIndex: Int, toIndex: Int): List<T> {
        if (fromIndex < toIndex) {
            return emptyList()
        }
        val realFromIndex = max(fromIndex, 0)
        val realToIndex = min(toIndex, size)
        return subList(realFromIndex, realToIndex)
    }
}

