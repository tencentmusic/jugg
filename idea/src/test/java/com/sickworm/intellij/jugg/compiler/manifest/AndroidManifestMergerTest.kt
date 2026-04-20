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
        val hasChanges = AndroidManifestMerger(logger).merge(mergedFile, listOf(changedManifestFile), outputFile)
        // no diff when merging identical files — merger returns false and does not write outputFile
        assertEquals(false, hasChanges)
        assert(!outputFile.exists()) { "outputFile should not be created when there are no changes" }
    }

    override fun diff(newXml: String, oldXml: String, expectDiffResult: String): ManifestDiffResult.DiffElement {
        val diffResult = super.diff(newXml, oldXml, expectDiffResult)

        val oldFullNode = XmlParser().parse(mergedFile)
        val newFullNode = XmlParser().parse(mergedFile)
        AndroidManifestMerger(logger).merge(newFullNode, listOf(diffResult))
        val outFile = File(buildDir, "out/AndroidManifest.xml")
        outFile.parentFile.mkdirs()
        outFile.writeText(newFullNode.printXml())
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

