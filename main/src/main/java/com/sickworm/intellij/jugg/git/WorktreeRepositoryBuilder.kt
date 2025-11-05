package com.sickworm.intellij.jugg.git

import org.eclipse.jgit.errors.RepositoryNotFoundException
import org.eclipse.jgit.lib.BaseRepositoryBuilder
import java.io.File

class WorktreeRepositoryBuilder : BaseRepositoryBuilder<WorktreeRepositoryBuilder, WorktreeFileRepository>() {

    private var worktreeGitDir: File? = null

    fun setWorktreeGitDir(worktreeGitDir: File?) = apply {
        this.worktreeGitDir = worktreeGitDir
    }

    @Suppress("FoldInitializerAndIfToElvis")
    override fun build(): WorktreeFileRepository {
        val worktreeGitDir = this.worktreeGitDir
        if (worktreeGitDir == null) {
            return super.build()
        }

        indexFile = File(worktreeGitDir, "index")
        val repo = WorktreeFileRepository(this.setup(), worktreeGitDir)
        if (this.isMustExist && !repo.objectDatabase.exists()) {
            throw RepositoryNotFoundException(this.gitDir)
        } else {
            return repo
        }
    }
}