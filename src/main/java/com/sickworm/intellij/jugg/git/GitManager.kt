package com.sickworm.intellij.jugg.git

import org.gradle.internal.impldep.org.eclipse.jgit.api.Git
import java.io.File

class GitManager(override val rootDir: File): IGitManager {

    override fun hasInit(): Boolean {
        if (!File(rootDir, ".git").exists()) {
            return false
        }
        return try {
            // throw RepositoryNotFoundException if structure is incorrect
            Git.open(rootDir)
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun init() {
        Git.init().setDirectory(rootDir).call()
    }

    override fun deleteGit() {
        if (!hasInit()) {
            return
        }
        File(rootDir, ".git").deleteRecursively()
    }
}