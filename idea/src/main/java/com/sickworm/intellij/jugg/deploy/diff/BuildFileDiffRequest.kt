package com.sickworm.intellij.jugg.deploy.diff

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.requests.ContentDiffRequest
import com.intellij.diff.util.DiffUserDataKeysEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.LocalFilePath
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
//      LocalFileSystem.getInstance().refreshAndFindFileByIoFile(oldFile) // VirtualFile content won't refresh immediately
        oldContent = oldFile
            ?.takeIf { it.exists() }
            ?.let {
                contentFactory.createFromBytes(project, it.readBytes(), LocalFilePath(it.path, false))
            }
        newContent = newFile
            .takeIf { it.exists() }
            ?.let {
                contentFactory.createFromBytes(project, it.readBytes(), LocalFilePath(it.path, false))
            }

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