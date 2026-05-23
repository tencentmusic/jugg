package com.sickworm.intellij.jugg.project

import java.io.File

/**
 * Tracks the last Jugg Run project root across IDE projects in one JVM.
 */
interface ILastCompileProjectRegistry {

    /** Returns true when [currentProjectRoot] differs from the last recorded Run project. */
    fun detectSwitch(currentProjectRoot: String): Boolean

    /** Records [currentProjectRoot] as the most recent Run project. */
    fun record(currentProjectRoot: String)
}

class LastCompileProjectRegistry : ILastCompileProjectRegistry {

    @Volatile
    private var lastProjectRoot: String? = null

    override fun detectSwitch(currentProjectRoot: String): Boolean {
        val previous = lastProjectRoot ?: return false
        return normalizeProjectRoot(previous) != normalizeProjectRoot(currentProjectRoot)
    }

    override fun record(currentProjectRoot: String) {
        lastProjectRoot = normalizeProjectRoot(currentProjectRoot)
    }

    companion object {

        val INSTANCE = LastCompileProjectRegistry()

        fun normalizeProjectRoot(path: String): String =
            File(path).absoluteFile.normalize().path
    }
}
