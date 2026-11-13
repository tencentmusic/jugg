package com.sickworm.intellij.jugg.cmdline.ci

import com.sickworm.intellij.jugg.cmdline.incremental.CmdLineContextManager
import com.sickworm.intellij.jugg.cmdline.incremental.IncrementalException
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
 fun run(args:Array<String>)=try { val p=args.associate{it.substringBefore('=') to it.substringAfter('=', "")}; val source=File(p.req("sourceProjectDir")); val baseRoot=File(p.req("baseBuildJuggRootDir")); val base=p.req("baseCommit"); val target=p.req("targetCommit"); val git=GitManager(source); val scope=CoroutineScope(Dispatchers.IO); val manager=CmdLineContextManager(JuggPathManager(source,baseRoot),scope,CmdLineLogger.stdLogger); manager.init(); val entries=manager.fileChangesHandler.filter(git.getChangedFiles(base,target)); val body=entries.joinToString(","){ "{\"file\":${q(it.file.absolutePath)},\"type\":${q(it.type.name)},\"module\":${q(it.module.name)}}" }; CiJsonWriter.write(File(p.req("output")),"{\"schemaVersion\":1,\"baseCommit\":${q(base)},\"resolvedBaseCommit\":${q(git.resolveCommit(base))},\"targetCommit\":${q(target)},\"resolvedTargetCommit\":${q(git.resolveCommit(target))},\"sourceProjectDir\":${q(source.absolutePath)},\"files\":[$body]}"); scope.cancel(); true } catch(e:Exception){CmdLineLogger.stdLogger.warn("getChangedFiles failed: ${e.message}");false}
 private fun q(s:String)="\""+s.replace("\\","\\\\").replace("\"","\\\"")+"\""
 private fun Map<String,String>.req(k:String)=get(k)?.takeIf{it.isNotEmpty()}?:error("Param '$k' not found")
}
