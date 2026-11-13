package com.sickworm.intellij.jugg.cmdline.ci
import com.sickworm.intellij.jugg.cmdline.logger.CmdLineLogger
import com.sickworm.intellij.jugg.git.GitManager
import java.io.File
object FindNearestBaseCommitCommand {
 fun run(args:Array<String>)=try { val p=args.associate{it.substringBefore('=') to it.substringAfter('=', "")}; val dir=File(p.req("projectDir")); val target=p.req("targetCommit"); val candidates=Regex("\\\"([^\\\"]+)\\\"").findAll(File(p.req("candidateCommitsFile")).readText()).map{it.groupValues[1]}.toList(); val git=GitManager(dir); val resolved=git.resolveCommit(target); val m=git.findNearestFirstParentCommit(target,candidates.toSet()); val cs=candidates.joinToString(","){q(it)}; val matched=m?.matchedCommit?.let(::q)?:"null"; CiJsonWriter.write(File(p.req("output")),"{\"schemaVersion\":1,\"targetCommit\":${q(target)},\"resolvedTargetCommit\":${q(resolved)},\"candidateCommits\":[$cs],\"matchedCommit\":$matched,\"matchStrategy\":\"first-parent\",\"matchedDistance\":${m?.distance?:"null"}}" ); m!=null } catch(e:Exception){CmdLineLogger.stdLogger.warn("findNearestBaseCommit failed: ${e.message}");false}
 private fun q(s:String)= "\""+s.replace("\\","\\\\").replace("\"","\\\"")+"\""
 private fun Map<String,String>.req(k:String)=get(k)?.takeIf{it.isNotEmpty()}?:error("Param '$k' not found")
}
