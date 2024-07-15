package com.sickworm.intellij.jugg.mock

import com.intellij.mock.MockVirtualFile
import com.intellij.openapi.vfs.VirtualFile
import java.io.File

class MockIoVirtualFile(val file: File): MockVirtualFile(file.isDirectory, file.name) {

    init {
        if (!isDirectory) {
            setText(file.readText())
        }
    }

    override fun getPath(): String {
        return file.absolutePath
    }

    override fun getParent(): VirtualFile? {
        val parentFile = file.parentFile ?: return null
        return MockIoVirtualFile(parentFile)
    }
}