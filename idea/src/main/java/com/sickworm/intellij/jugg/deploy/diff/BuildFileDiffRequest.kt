package com.sickworm.intellij.jugg.deploy.diff

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.requests.ContentDiffRequest
import com.intellij.diff.util.DiffUserDataKeysEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.LocalFilePath
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

    private val oldContent: DiffContent?
    private val newContent: DiffContent?

    init {
        oldContent = oldFile?.createDiffContent(project)
        newContent = newFile.createDiffContent(project)
        putUserData(DiffUserDataKeysEx.EDITORS_HIDE_TITLE, true)
    }

    private fun File.createDiffContent(project: Project): DiffContent? {
        if (!this.exists()) {
            return null
        }
        val contentFactory = DiffContentFactory.getInstance()
        return if (this.extension == "jar" || this.extension == "aar") {
            val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(this) ?: return null
            return contentFactory.create(project, virtualFile)
        } else {
            // VirtualFile content won't refresh immediately, so we read it to bytes
            contentFactory.createFromBytes(project, this.readBytes(), LocalFilePath(this.path, false))
        }
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