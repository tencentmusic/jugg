package com.sickworm.intellij.jugg.compiler.constref

import org.junit.After
import java.io.File
import java.nio.file.Files

abstract class ConstRefTempDirCleanupSupport {
    private val tempDirs = mutableListOf<File>()

    protected fun createTempDirectory(prefix: String): File {
        return Files.createTempDirectory(prefix).toFile().also { tempDirs += it }
    }

    @After
    fun cleanupTempDirs() {
        tempDirs.asReversed().forEach { dir ->
            runCatching { dir.deleteRecursively() }
        }
        tempDirs.clear()
    }
}
