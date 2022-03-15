package com.sickworm.intellij.jugg.mock

import com.intellij.openapi.vfs.AsyncFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import java.io.File

class FileChangeEventSender(private val listener: AsyncFileListener) {

    /**
     * @param filePairs <file that contains modified content, file in project to apply modified source>
     */
    fun copyAndNotifyFileChanges(filePairs: List<Pair<File, File>>) {
        filePairs.forEach { (sourceFile, destFile) ->
            sourceFile.copyTo(destFile, overwrite = true)
        }
        val files = filePairs.map { it.second }
        notifyFileChanges(files)
    }

    fun notifyFileChanges(files: List<File>) {
        val events = files.map {
            val file = MockIoVirtualFile(it)
            VFileContentChangeEvent(Any(), file, 0L, 0L, false)
        }
        val changeApplier = listener.prepareChange(events)
        changeApplier?.afterVfsChange()
    }
}

