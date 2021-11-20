package com.sickworm.intellij.jugg.mock

import com.intellij.openapi.vfs.AsyncFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import java.io.File

class FileChangeEventSender(private val listener: AsyncFileListener) {

    fun copyAndNotifyFileChanges(filePairs: List<Pair<File, File>>) {
        val events = filePairs.map { (sourceFile, destFile) ->
            sourceFile.copyTo(destFile, overwrite = true)
            val file = MockIoVirtualFile(destFile)
            VFileContentChangeEvent(Any(), file, 0L, 0L, false)
        }
        val changeApplier = listener.prepareChange(events)
        changeApplier?.afterVfsChange()
    }
}

