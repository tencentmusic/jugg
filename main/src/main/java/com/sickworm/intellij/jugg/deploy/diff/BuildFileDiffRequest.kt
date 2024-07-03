package com.sickworm.intellij.jugg.deploy.diff

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.impl.DiffSettingsHolder
import com.intellij.diff.requests.ContentDiffRequest
import com.intellij.diff.tools.fragmented.UnifiedDiffTool
import com.intellij.diff.tools.util.base.TextDiffSettingsHolder
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.util.DiffUserDataKeysEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.io.File

class BuildFileDiffRequest(
    project: Project,
    private val newFile: File,
    private val oldFile: File?,
) : ContentDiffRequest() {

    private val oldContent: DiffContent?
    private val newContent: DiffContent?

    init {
        val contentFactory = DiffContentFactory.getInstance()
        val localFileSystem = LocalFileSystem.getInstance()
        val vOldFile: VirtualFile? = if (oldFile?.exists() == true) localFileSystem.findFileByIoFile(oldFile) else null
        val vNewFile: VirtualFile? = if (newFile.exists()) localFileSystem.findFileByIoFile(newFile) else null
        oldContent = vOldFile?.run { contentFactory.create(project, vOldFile) }
        newContent = vNewFile?.run { contentFactory.create(project, vNewFile) }

        putUserData(DiffUserDataKeysEx.EDITORS_HIDE_TITLE, true)
    }


    override fun getTitle(): String {
        return ""
    }

    override fun getContents(): MutableList<DiffContent> {
        val contentFactory = DiffContentFactory.getInstance()
        if (newContent == null) {
            return mutableListOf(
                contentFactory.create(""),
                contentFactory.create("Failed to load content from ${newFile.absolutePath}"),
            )
        }
        return mutableListOf(oldContent ?: contentFactory.create(""), newContent)
    }

    override fun getContentTitles(): MutableList<String> {
        return mutableListOf("old", "new")
    }

}