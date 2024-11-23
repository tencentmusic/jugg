package com.sickworm.intellij.jugg.project

import java.io.File

interface IFileChangesDetector {
    fun startListen(listener: FileChangesListener)
}

interface FileChangesListener {
    fun onFileChanges(changedFiles: List<File>, deletedFiles: List<File>, rollbackFiles: List<File>)
}