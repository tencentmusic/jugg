package com.sickworm.intellij.jugg.cmdline.ci

import com.intellij.openapi.util.Disposer
import com.sickworm.intellij.jugg.cmdline.incremental.CmdLineContextManager
import com.sickworm.intellij.jugg.cmdline.logger.CmdLineLogger
import com.sickworm.intellij.jugg.git.GitManager
import com.sickworm.intellij.jugg.project.JuggPathManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import java.io.File

data class ChangedFileEntry(val file: String, val type: String, val module: String)
data class ChangedFilesDocument(val schemaVersion: Int = 1, val baseCommit: String, val resolvedBaseCommit: String, val targetCommit: String, val resolvedTargetCommit: String, val sourceProjectDir: String, val files: List<ChangedFileEntry>)

object GetChangedFilesCommand {
    fun run(args: Array<String>): Boolean = try {
        val params = args.associate { it.substringBefore('=') to it.substringAfter('=', "") }
        val source = File(params.req("sourceProjectDir"))
        val baseRoot = File(params.req("baseBuildJuggRootDir"))
        val base = params.req("baseCommit")
        val target = params.req("targetCommit")
        val git = GitManager(source)
        val scope = CoroutineScope(Dispatchers.IO)
        val manager = CmdLineContextManager(JuggPathManager(source, baseRoot), scope, CmdLineLogger.stdLogger)
        try {
            manager.init()
            val files = git.getChangedFiles(base, target)
            val recognized = manager.fileChangesHandler.filter(files).associateBy { it.file.absolutePath }
            // Preserve the Git diff even when Jugg has no compiler or module for a path.
            // Unknown is a CI reporting type, not a new input type for the core compiler.
            val entries = files.map { file ->
                val changed = recognized[file.absolutePath]
                ChangedFileEntry(file.absolutePath, changed?.type?.name ?: "Unknown", changed?.module?.name ?: "")
            }
            val body = entries.joinToString(",") {
                "{\"file\":${q(it.file)},\"type\":${q(it.type)},\"module\":${q(it.module)}}"
            }
            CiJsonWriter.write(File(params.req("output")),
                "{\"schemaVersion\":1,\"baseCommit\":${q(base)},\"resolvedBaseCommit\":${q(git.resolveCommit(base))}," +
                        "\"targetCommit\":${q(target)},\"resolvedTargetCommit\":${q(git.resolveCommit(target))}," +
                        "\"sourceProjectDir\":${q(source.absolutePath)},\"files\":[$body]}")
        } finally {
            scope.cancel()
            Disposer.dispose(manager.disposer)
        }
        true
    } catch (exception: Exception) {
        CmdLineLogger.stdLogger.warn("getChangedFiles failed: ${exception.message}")
        false
    }

    private fun q(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun Map<String, String>.req(key: String) =
        get(key)?.takeIf { it.isNotEmpty() } ?: error("Param '$key' not found")
}
