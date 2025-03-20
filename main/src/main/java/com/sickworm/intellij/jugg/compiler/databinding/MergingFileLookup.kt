package com.sickworm.intellij.jugg.compiler.databinding

import android.databinding.tool.LayoutXmlProcessor
import com.android.tools.r8.com.android.ide.common.blame.MergingLog
import com.android.tools.r8.com.android.ide.common.blame.SourceFile
import java.io.File

/**
 * Reference: [com.android.build.gradle.internal.databinding.MergingFileLookup]
 * Implementation of [LayoutXmlProcessor.OriginalFileLookup] over a resource merge blame file.
 */
class MergingFileLookup(private val resourceBlameLogDir: File) : LayoutXmlProcessor.OriginalFileLookup {
    override fun getOriginalFileFor(file: File): File? {
        val input = SourceFile(file)
        val original = mergingLog.find(input)
        return if (input === original) {
            null
        } else {
            original.sourceFile
        }
    }

    private val mergingLog: MergingLog by lazy {
        MergingLog(resourceBlameLogDir)
    }
}