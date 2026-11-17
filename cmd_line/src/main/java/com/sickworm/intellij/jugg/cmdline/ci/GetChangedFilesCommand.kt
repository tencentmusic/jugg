package com.sickworm.intellij.jugg.cmdline.ci

import com.intellij.openapi.util.Disposer
import com.sickworm.intellij.jugg.cmdline.incremental.CmdLineContextManager
import com.sickworm.intellij.jugg.cmdline.logger.CmdLineLogger
import com.sickworm.intellij.jugg.deploy.DeployHistoryData
import com.sickworm.intellij.jugg.git.GitManager
import com.sickworm.intellij.jugg.project.IFileChangesHandler
import com.sickworm.intellij.jugg.project.JuggPathManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import java.io.File

data class ChangedFileEntry(val file: String, val type: String, val module: String, val gitRoot: String)
data class GitRepositoryEntry(val gitRoot: String, val baseCommit: String, val targetCommit: String)
data class ChangedFilesDocument(
    val schemaVersion: Int = 1,
    val baseCommit: String,
    val resolvedBaseCommit: String,
    val targetCommit: String,
    val resolvedTargetCommit: String,
    val sourceProjectDir: String,
    val files: List<ChangedFileEntry>,
    val repositories: List<GitRepositoryEntry>,
)

object GetChangedFilesCommand {
    fun run(args: Array<String>): Boolean = try {
        val params = args.associate { it.substringBefore('=') to it.substringAfter('=', "") }
        val source = File(params.req("sourceProjectDir"))
        val baseRoot = File(params.req("baseBuildJuggRootDir"))
        val scope = CoroutineScope(Dispatchers.IO)
        val manager = CmdLineContextManager(JuggPathManager(source, baseRoot), scope, CmdLineLogger.stdLogger)
        try {
            // File classification only needs the project model, not APK parsing or database writes.
            val context = manager.init(initializeApkData = false)
            val result = collect(source, baseRoot, params.req("baseCommit"), params.req("targetCommit"),
                context.modules.values.map { it.moduleRootDir }, manager.fileChangesHandler)
            write(File(params.req("output")), result)
        } finally {
            scope.cancel()
            Disposer.dispose(manager.disposer)
        }
        true
    } catch (exception: Exception) {
        CmdLineLogger.stdLogger.warn("getChangedFiles failed: ${exception.message}")
        false
    }

    internal fun collect(source: File, baseRoot: File, base: String, target: String,
                         moduleRoots: List<File>, handler: IFileChangesHandler): ChangedFilesDocument {
        val repositories = repositories(source, baseRoot, base, target, moduleRoots)
        val filesWithRoots = repositories.flatMap { repo ->
            if (repo.baseCommit == repo.targetCommit) emptyList() else {
                GitManager(File(repo.gitRoot)).getChangedFiles(repo.baseCommit, repo.targetCommit).map { it to repo.gitRoot }
            }
        }.distinctBy { it.first.absolutePath }
        val recognized = handler.filter(filesWithRoots.map { it.first }).associateBy { it.file.absolutePath }
        val entries = filesWithRoots.map { (file, root) ->
            val changed = recognized[file.absolutePath]
            ChangedFileEntry(file.absolutePath, changed?.type?.name ?: "Unknown", changed?.module?.name ?: "", root)
        }
        val main = repositories.first()
        return ChangedFilesDocument(baseCommit = base, resolvedBaseCommit = main.baseCommit,
            targetCommit = target, resolvedTargetCommit = main.targetCommit, sourceProjectDir = source.absolutePath,
            files = entries, repositories = repositories)
    }

    private fun repositories(source: File, baseRoot: File, base: String, target: String,
                             moduleRoots: List<File>): List<GitRepositoryEntry> {
        val main = GitManager.createGitManagerAndTrySearchParent(source.absoluteFile.normalize())
        require(main.hasInitGit) { "Git repository is unavailable: $source" }
        val history = DeployHistoryData.load(File(baseRoot, "database/deploy_history.db/deploy_history.json"), false)
        val saved = history?.subModulesFullCompileGitCommitHash.orEmpty().mapKeys { (path, _) ->
            File(path).absoluteFile.normalize().path
        }
        val roots = (listOf(main.rootDir) + moduleRoots + saved.keys.map(::File)).map { root ->
            val git = GitManager.createGitManagerAndTrySearchParent(root.absoluteFile.normalize())
            require(root.isDirectory && git.hasInitGit) { "Git repository is unavailable: $root" }
            git.rootDir
        }.distinctBy { it.path }
        return roots.map { root ->
            val git = GitManager(root)
            val baseRevision = if (root == main.rootDir) base else saved[root.path]
            require(!baseRevision.isNullOrBlank()) { "Missing baseline commit for Git repository: $root" }
            val targetRevision = if (root == main.rootDir) target else "HEAD"
            GitRepositoryEntry(root.absolutePath, git.resolveCommit(baseRevision), git.resolveCommit(targetRevision))
        }
    }

    private fun write(output: File, result: ChangedFilesDocument) {
        val files = result.files.joinToString(",") {
            "{\"file\":${q(it.file)},\"type\":${q(it.type)},\"module\":${q(it.module)},\"gitRoot\":${q(it.gitRoot)}}"
        }
        val repositories = result.repositories.joinToString(",") {
            "{\"gitRoot\":${q(it.gitRoot)},\"baseCommit\":${q(it.baseCommit)},\"targetCommit\":${q(it.targetCommit)}}"
        }
        CiJsonWriter.write(output,
            "{\"schemaVersion\":1,\"baseCommit\":${q(result.baseCommit)},\"resolvedBaseCommit\":${q(result.resolvedBaseCommit)}," +
                    "\"targetCommit\":${q(result.targetCommit)},\"resolvedTargetCommit\":${q(result.resolvedTargetCommit)}," +
                    "\"sourceProjectDir\":${q(result.sourceProjectDir)},\"files\":[$files],\"repositories\":[$repositories]}")
    }

    private fun q(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun Map<String, String>.req(key: String) =
        get(key)?.takeIf { it.isNotEmpty() } ?: error("Param '$key' not found")
}
