package com.sickworm.intellij.jugg.deploy.diff

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.requests.ContentDiffRequest
import com.intellij.diff.util.DiffUserDataKeysEx
import com.intellij.ide.highlighter.ArchiveFileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File

/**
 * [ContentDiffRequest] is used to declare diff content.
 */
class BuildFileDiffRequest(
    project: Project,
    private val newFile: File,
    private val oldFile: File?,
) : ContentDiffRequest() {

    private val newContent: DiffContent?
    private val oldContent: DiffContent?

    init {
        val contents = createDiffContent(project, newFile, oldFile)
        newContent = contents.first
        oldContent = contents.second
        try {
            // no field in low Idea Version
            putUserData(DiffUserDataKeysEx.EDITORS_HIDE_TITLE, true)
        } catch (e: Error) {
            // ignore
        }
    }

    private fun createDiffContent(project: Project, newFile: File, oldFile: File?): Pair<DiffContent?, DiffContent?> {
        val contentFactory = DiffContentFactory.getInstance()
        if (newFile.extension == "jar" || newFile.extension == "aar") {
            val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(newFile) ?: return null to null
            val newContent = contentFactory.create(project, virtualFile)
            val oldContent = if (oldFile == null || !oldFile.exists()) {
                contentFactory.createFromBytes(project, byteArrayOf(), newContent.contentType ?: ArchiveFileType.INSTANCE, newFile.path)
            } else {
                val oldVirtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(oldFile) ?: return null to null
                contentFactory.create(project, oldVirtualFile)
            }
            return newContent to oldContent
        } else {
            // VirtualFile content won't refresh immediately, so we read it to bytes
            val newContent = contentFactory.createFromBytes(project, newFile.readBytes(), PlainTextFileType.INSTANCE, newFile.path)
            val oldContent = if (oldFile == null || !oldFile.exists()) {
                contentFactory.create("")
            } else {
                contentFactory.createFromBytes(project, oldFile.readBytes(), PlainTextFileType.INSTANCE, oldFile.path)
            }
            return newContent to oldContent
        }
    }

    override fun getTitle(): String {
        return ""
    }

    override fun getContents(): MutableList<DiffContent> {
        val contentFactory = DiffContentFactory.getInstance()
        if (newContent == null || oldContent == null) {
            return mutableListOf(
                contentFactory.create(""),
                contentFactory.create("Failed to load content from ${newFile.absolutePath}"),
            )
        }
        // left, right
        return mutableListOf(oldContent, newContent)
    }

    override fun getContentTitles(): MutableList<String> {
        // left, right
        return mutableListOf("old", "new")
    }

}