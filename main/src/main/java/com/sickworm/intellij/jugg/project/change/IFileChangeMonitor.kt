package com.sickworm.intellij.jugg.project.change

import java.io.File

/** Watches project files and reports normalized change batches. */
interface IFileChangeMonitor : AutoCloseable {
    fun startListen(listener: FileChangesListener)

    override fun close() = Unit
}

/** Receives changed/deleted file paths and monitor overflow signals. */
interface FileChangesListener {
    fun onFileChanges(changedFiles: List<File>, deletedFiles: List<File>)

    fun onOverflow() = Unit
}
