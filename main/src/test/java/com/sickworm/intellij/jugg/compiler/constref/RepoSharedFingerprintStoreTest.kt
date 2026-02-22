package com.sickworm.intellij.jugg.compiler.constref

import com.sickworm.intellij.jugg.mock.logger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class RepoSharedFingerprintStoreTest : ConstRefTempDirCleanupSupport() {
    @Test
    fun `should hit shared fingerprint after mtime changes`() {
        val rootDir = createTempDirectory("repo_shared_fp_mtime")
        File(rootDir, ".git").mkdirs()
        val sourceFile = File(rootDir, "src/Config.kt").apply {
            parentFile.mkdirs()
            writeText("package com.example\nconst val MAX = 1\n")
        }
        val store = RepoSharedFingerprintStore(
            logger = logger,
            dbFile = File(rootDir, "repo_fingerprint.db"),
        )

        assertNull(store.findChecksum(sourceFile))
        store.saveChecksum(sourceFile, 13579L)

        sourceFile.setLastModified(sourceFile.lastModified() + 10_000L)
        assertEquals(13579L, store.findChecksum(sourceFile))
    }

    @Test
    fun `should miss shared fingerprint when middle content changes with same head tail`() {
        val rootDir = createTempDirectory("repo_shared_fp_middle_change")
        File(rootDir, ".git").mkdirs()
        val sourceFile = File(rootDir, "src/Large.kt").apply {
            parentFile.mkdirs()
            writeBytes(buildLargeFixture('A'))
        }
        val store = RepoSharedFingerprintStore(
            logger = logger,
            dbFile = File(rootDir, "repo_fingerprint.db"),
        )

        store.saveChecksum(sourceFile, 11223L)
        sourceFile.writeBytes(buildLargeFixture('B'))
        assertNull(store.findChecksum(sourceFile))
    }

    @Test
    fun `should share fingerprint across worktrees with same repo key`() {
        val rootDir = createTempDirectory("repo_shared_fp_worktree")
        val commonGitDir = File(rootDir, "common.git").apply { mkdirs() }
        val worktreeA = File(rootDir, "worktree_a").apply { mkdirs() }
        val worktreeB = File(rootDir, "worktree_b").apply { mkdirs() }
        prepareWorktreeGitRef(worktreeA, commonGitDir, "a")
        prepareWorktreeGitRef(worktreeB, commonGitDir, "b")

        val fileInA = File(worktreeA, "src/Consts.kt").apply {
            parentFile.mkdirs()
            writeText("package com.example\nconst val VALUE = 42\n")
        }
        val fileInB = File(worktreeB, "src/Consts.kt").apply {
            parentFile.mkdirs()
            writeText(fileInA.readText())
        }
        val store = RepoSharedFingerprintStore(
            logger = logger,
            dbFile = File(rootDir, "repo_fingerprint.db"),
        )

        store.saveChecksum(fileInA, 24680L)
        assertEquals(24680L, store.findChecksum(fileInB))
    }

    private fun prepareWorktreeGitRef(worktreeDir: File, commonGitDir: File, worktreeName: String) {
        val worktreeGitDir = File(commonGitDir, "worktrees/$worktreeName").apply { mkdirs() }
        File(worktreeGitDir, "commondir").writeText("../../\n")
        File(worktreeDir, ".git").writeText("gitdir: ${worktreeGitDir.absolutePath}\n")
    }

    private fun buildLargeFixture(middleChar: Char): ByteArray {
        val totalSize = 15_000
        val headTailSize = 4_096
        return ByteArray(totalSize).apply {
            for (index in indices) {
                this[index] = when {
                    index < headTailSize -> 'H'.code.toByte()
                    index >= totalSize - headTailSize -> 'T'.code.toByte()
                    else -> middleChar.code.toByte()
                }
            }
        }
    }
}
