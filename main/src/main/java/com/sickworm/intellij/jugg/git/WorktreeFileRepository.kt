package com.sickworm.intellij.jugg.git

import org.gradle.internal.impldep.org.eclipse.jgit.internal.storage.file.FileRepository
import org.gradle.internal.impldep.org.eclipse.jgit.lib.*
import java.io.File

class WorktreeFileRepository(
    builder: BaseRepositoryBuilder<*, *>,
    private val worktreeGitDir: File,
) : FileRepository(builder) {

    private val originRefs: RefDatabase get() = super.getRefDatabase()

    /**
     * redirect "HEAD" in .git/HEAD to "HEAD" in realHeadFile(e.g. .git/worktree/project_a/HEAD)
     */
    private val refsWrapper = object : RefDatabase() {
        override fun create() {
            originRefs.create()
        }

        override fun close() {
            originRefs.close()
        }

        override fun isNameConflicting(p0: String?): Boolean {
            return originRefs.isNameConflicting(p0)
        }

        override fun newUpdate(p0: String?, p1: Boolean): RefUpdate {
            return originRefs.newUpdate(p0.redirectHeadToWorktreeHead(), p1)
        }

        override fun newRename(p0: String?, p1: String?): RefRename {
            return originRefs.newRename(p0.redirectHeadToWorktreeHead(), p1)
        }

        override fun exactRef(p0: String?): Ref? {
            return originRefs.exactRef(p0.redirectHeadToWorktreeHead())
        }

        @Suppress("DEPRECATION")
        @Deprecated("Deprecated in Java")
        override fun getRefs(p0: String?): MutableMap<String, Ref> {
            return originRefs.getRefs(p0.redirectHeadToWorktreeHead())
        }

        override fun getAdditionalRefs(): MutableList<Ref> {
            return originRefs.additionalRefs
        }

        override fun peel(p0: Ref?): Ref {
            return originRefs.peel(p0)
        }
    }

    private fun String?.redirectHeadToWorktreeHead(): String? {
        if (this != "HEAD") {
            return this
        }

        val headFile = File(worktreeGitDir, "HEAD")
        if (headFile.exists()) {
            val refs =  headFile.readText(Charsets.UTF_8).trim()
            return if (refs.startsWith("ref: ")) {
                refs.substring("ref: ".length)
            } else {
                refs
            }
        }
        return "HEAD"
    }

    override fun getRefDatabase(): RefDatabase {
        return refsWrapper
    }
}