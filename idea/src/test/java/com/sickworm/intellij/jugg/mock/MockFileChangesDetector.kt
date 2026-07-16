package com.sickworm.intellij.jugg.mock

import com.sickworm.intellij.jugg.project.change.FileChangesListener
import com.sickworm.intellij.jugg.project.change.IFileChangesDetector
import java.io.File

class MockFileChangesDetector: IFileChangesDetector {

    private var listener: FileChangesListener? = null

    fun copyAndNotifyFileChanges(filePairs: List<Pair<File, File>>) {
        filePairs.forEach { (sourceFile, destFile) ->
            sourceFile.copyTo(destFile, overwrite = true)
        }
        val files = filePairs.map { it.second }
        notifyFileChanges(files)
    }


    fun notifyFileChanges(files: List<File>) {
        listener?.onFileChanges(files, emptyList())
    }

    override fun startListen(listener: FileChangesListener) {
        this.listener = listener
    }
}