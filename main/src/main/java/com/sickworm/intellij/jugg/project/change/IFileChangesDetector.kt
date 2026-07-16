package com.sickworm.intellij.jugg.project.change

import java.io.File

/**
 * IFileChangesDetector watches project files and reports change batches to a listener.
 */
interface IFileChangesDetector {
    fun startListen(listener: FileChangesListener)
}

/**
 * FileChangesListener receives changed/deleted file paths detected by [IFileChangesDetector].
 */
interface FileChangesListener {
    fun onFileChanges(changedFiles: List<File>, deletedFiles: List<File>)
}
