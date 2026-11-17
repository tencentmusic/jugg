package com.sickworm.intellij.jugg.cmdline.ci

import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.cmdline.StdLogger
import com.sickworm.intellij.jugg.deploy.DeployHistoryDb
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import com.sickworm.intellij.jugg.deploy.DeployHistoryData
import com.sickworm.intellij.jugg.git.GitManager
import com.sickworm.intellij.jugg.project.ChangedFile
import com.sickworm.intellij.jugg.project.IFileChangesHandler
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GetChangedFilesCommandTest {
    @get:Rule val temporary = TemporaryFolder()
    private val unknownFiles = object : IFileChangesHandler {
        override fun init(compileContext: ICompileContext) = Unit
        override fun filter(file: List<File>): List<ChangedFile> = emptyList()
        override fun updateBuildFileRules(rules: List<String>, doNotIgnoreModulePaths: List<String>) = Unit
    }

    private fun repository(name: String): File = temporary.newFolder(name).also { root ->
        GitManager(root).init()
        File(root, ".git/config").appendText("\n[user]\n name = CI\n email = ci@example.com\n")
        File(root, "README.md").writeText("initial")
        commit(root)
    }

    private fun commit(root: File): String {
        val git = GitManager(root)
        git.addAllAndCommit("change")
        return git.getLastCommitHash()!!
    }

    @Test fun `external-only changes preserve git root and every repository revision`() {
        val source = repository("app")
        val library = repository("library")
        val baseline = temporary.newFolder("baseline")
        val mainBase = GitManager(source).getLastCommitHash()!!
        val libraryBase = GitManager(library).getLastCommitHash()!!
        DeployHistoryData(mainBase, mapOf(library.path to libraryBase), 0, emptyMap())
            .save(File(baseline, "database/deploy_history.db/deploy_history.json"))
        val changed = File(library, "Library.kt").apply { writeText("class Library") }
        val libraryTarget = commit(library)
        val result = GetChangedFilesCommand.collect(source, baseline, mainBase, mainBase,
            listOf(source, library), unknownFiles)
        assertEquals(listOf(changed.path), result.files.map { it.file })
        assertEquals(library.path, result.files.single().gitRoot)
        assertEquals("Unknown", result.files.single().type)
        assertEquals(2, result.repositories.size)
        assertEquals(libraryTarget, result.repositories.single { it.gitRoot == library.path }.targetCommit)
        assertEquals(libraryBase, result.repositories.single { it.gitRoot == library.path }.baseCommit)
    }

    @Test fun `full build history records external module repository commits`() {
        val source = repository("app")
        val library = repository("library")
        val baseline = temporary.newFolder("baseline")
        val db = DeployHistoryDb(source, File(baseline, "database/deploy_history.db"), unknownFiles,
            StdLogger("GetChangedFilesCommandTest"))
        db.resetHistoryAfterFullCompiled(mapOf(
            "app" to ModuleInfo.virtualModule.copy(moduleRootDir = source),
            "library" to ModuleInfo.virtualModule.copy(moduleRootDir = library),
        ), System.currentTimeMillis())
        val result = GetChangedFilesCommand.collect(source, baseline, GitManager(source).getLastCommitHash()!!,
            "HEAD", listOf(source, library), unknownFiles)
        assertEquals(2, result.repositories.size)
        assertTrue(result.files.isEmpty())
    }

    @Test fun `git root is the containing repository rather than the project subdirectory`() {
        val root = repository("repository")
        val source = File(root, "android").apply { mkdirs() }
        val base = GitManager(root).getLastCommitHash()!!
        File(source, "Source.kt").writeText("class Source")
        val target = commit(root)
        val result = GetChangedFilesCommand.collect(source, temporary.newFolder("baseline"), base, target,
            listOf(source), unknownFiles)
        assertEquals(root.path, result.files.single().gitRoot)
    }

    @Test fun `legacy single-repository baselines remain readable`() {
        val source = repository("app")
        val base = GitManager(source).getLastCommitHash()!!
        File(source, "Source.kt").writeText("class Source")
        val target = commit(source)
        val result = GetChangedFilesCommand.collect(source, temporary.newFolder("baseline"), base, target,
            listOf(source), unknownFiles)
        assertEquals(source.path, result.files.single().gitRoot)
        assertEquals(1, result.repositories.size)
    }

    @Test fun `baseline repository paths containing parent segments remain readable`() {
        val source = repository("app")
        val library = repository("library")
        val baseline = temporary.newFolder("baseline")
        val base = GitManager(source).getLastCommitHash()!!
        val libraryBase = GitManager(library).getLastCommitHash()!!
        val savedPath = File(source, "../library").path
        DeployHistoryData(base, mapOf(savedPath to libraryBase), 0, emptyMap())
            .save(File(baseline, "database/deploy_history.db/deploy_history.json"))
        val result = GetChangedFilesCommand.collect(source, baseline, base, base,
            listOf(source, library), unknownFiles)
        assertEquals(libraryBase, result.repositories.single { it.gitRoot == library.path }.baseCommit)
    }

    @Test fun `external repositories without baseline commits fail explicitly`() {
        val source = repository("app")
        val library = repository("library")
        val base = GitManager(source).getLastCommitHash()!!
        val failure = assertFailsWith<IllegalArgumentException> {
            GetChangedFilesCommand.collect(source, temporary.newFolder("baseline"), base, base,
                listOf(source, library), unknownFiles)
        }
        assertTrue(failure.message!!.contains(library.path))
    }

    @Test fun `query compares committed revisions when external working tree is dirty`() {
        val source = repository("app")
        val library = repository("library")
        val base = GitManager(source).getLastCommitHash()!!
        val baseline = temporary.newFolder("baseline")
        DeployHistoryData(base, mapOf(library.path to GitManager(library).getLastCommitHash()), 0, emptyMap())
            .save(File(baseline, "database/deploy_history.db/deploy_history.json"))
        File(library, "README.md").appendText("dirty")
        val result = GetChangedFilesCommand.collect(source, baseline, base, base,
            listOf(source, library), unknownFiles)
        assertTrue(result.files.isEmpty())
        assertEquals(2, result.repositories.size)
    }
}
