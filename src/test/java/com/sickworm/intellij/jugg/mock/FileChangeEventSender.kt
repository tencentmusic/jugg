package com.sickworm.intellij.jugg.mock

import com.intellij.openapi.vfs.AsyncFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import java.io.File

class FileChangeEventSender(private val listener: AsyncFileListener) {

    fun copyAndNotifyFileChanges(sourceFile: File, destFile: File) {
        sourceFile.copyTo(destFile, overwrite = true)
        val file = MockIoVirtualFile(destFile)
        val event = VFileContentChangeEvent(Any(), file, 0L, 0L, false)
        val changeApplier = listener.prepareChange(mutableListOf(event))
        changeApplier?.afterVfsChange()
    }
}

