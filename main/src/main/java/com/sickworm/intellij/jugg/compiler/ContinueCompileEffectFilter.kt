package com.sickworm.intellij.jugg.compiler

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.deploy.data.sources
import com.sickworm.intellij.jugg.deploy.run.JuggDeployData
import com.sickworm.intellij.jugg.logger.getInstance
import com.sickworm.intellij.jugg.project.change.ChangedFile
import java.io.File

/**
 * Resolves and deduplicates continue-compile effect triggers (trigger class -> effected source).
 */
internal object ContinueCompileEffectFilter {

    private val logger = Logger.getInstance(ContinueCompileEffectFilter::class.java)
        .getInstance("ContinueCompileEffectFilter")

    /**
     * Marks pending triggers for files compiled in this frame, then filters the next continue-compile batch.
     */
    fun resolveUncompiledEffectedFiles(
        justCompiledFiles: List<ChangedFile>,
        changedFiles: List<ChangedFile>,
        lastRoundCompiledPaths: Set<String>,
        satisfiedEffectTriggers: MutableSet<String>,
        pendingEffectTriggerKeys: MutableMap<String, String>,
        juggDeployData: JuggDeployData,
        topLevelFacadeEffectedSourcePaths: Set<String> = emptySet(),
    ): List<ChangedFile> {
        consumePendingEffectTriggers(justCompiledFiles, satisfiedEffectTriggers, pendingEffectTriggerKeys)
        return filterUncompiledEffectedFiles(
            changedFiles = changedFiles,
            lastRoundCompiledPaths = lastRoundCompiledPaths,
            satisfiedEffectTriggers = satisfiedEffectTriggers,
            topLevelFacadeEffectedSourcePaths = topLevelFacadeEffectedSourcePaths,
            juggDeployData = juggDeployData,
            logSkips = true,
        )
    }

    /**
     * Schedules effect trigger keys for sources about to be sent to a nested continue-compile round.
     */
    fun schedulePendingEffectTriggers(
        effectedFiles: List<ChangedFile>,
        juggDeployData: JuggDeployData,
        pendingEffectTriggerKeys: MutableMap<String, String>,
    ) {
        effectedFiles.forEach { changedFile ->
            pendingEffectTriggerKeys[changedFile.file.absolutePath] =
                resolveEffectTriggerKey(changedFile, juggDeployData)
        }
    }

    fun filterUncompiledEffectedFiles(
        changedFiles: List<ChangedFile>,
        lastRoundCompiledPaths: Set<String>,
        satisfiedEffectTriggers: Set<String>,
        topLevelFacadeEffectedSourcePaths: Set<String> = emptySet(),
        juggDeployData: JuggDeployData,
    ): List<ChangedFile> {
        return filterUncompiledEffectedFiles(
            changedFiles = changedFiles,
            lastRoundCompiledPaths = lastRoundCompiledPaths,
            satisfiedEffectTriggers = satisfiedEffectTriggers,
            topLevelFacadeEffectedSourcePaths = topLevelFacadeEffectedSourcePaths,
            juggDeployData = juggDeployData,
            logSkips = false,
        )
    }

    fun resolveEffectTriggerKey(changedFile: ChangedFile, juggDeployData: JuggDeployData): String {
        val effectedPath = changedFile.file.absolutePath
        val sourceNodes = juggDeployData.effectedClassNodes.sources.filter { node ->
            node.sourceFileName == changedFile.file.name
        }
        if (sourceNodes.isNotEmpty()) {
            val triggerClasses = sourceNodes
                .flatMap { it.effectedByClasses }
                .distinct()
                .sorted()
            return buildTriggerKey(effectedPath, "structure", triggerClasses)
        }
        val constRefPaths = juggDeployData.constRefEffectedSourcePaths
        if (constRefPaths.any { File(it).absolutePath == effectedPath }) {
            val definitionPaths = constRefPaths.sorted().joinToString(",")
            return buildTriggerKey(effectedPath, "constref", listOf(definitionPaths))
        }
        return buildTriggerKey(effectedPath, "unknown", emptyList())
    }

    private fun consumePendingEffectTriggers(
        justCompiledFiles: List<ChangedFile>,
        satisfiedEffectTriggers: MutableSet<String>,
        pendingEffectTriggerKeys: MutableMap<String, String>,
    ) {
        justCompiledFiles.forEach { changedFile ->
            pendingEffectTriggerKeys.remove(changedFile.file.absolutePath)?.let { triggerKey ->
                satisfiedEffectTriggers.add(triggerKey)
            }
        }
    }

    private fun filterUncompiledEffectedFiles(
        changedFiles: List<ChangedFile>,
        lastRoundCompiledPaths: Set<String>,
        satisfiedEffectTriggers: Set<String>,
        topLevelFacadeEffectedSourcePaths: Set<String>,
        juggDeployData: JuggDeployData,
        logSkips: Boolean,
    ): List<ChangedFile> {
        val pending = mutableListOf<ChangedFile>()
        changedFiles.forEach { changedFile ->
            val path = changedFile.file.absolutePath
            val triggerKey = resolveEffectTriggerKey(changedFile, juggDeployData)
            when {
                satisfiedEffectTriggers.contains(triggerKey) -> {
                    if (logSkips) {
                        logger.debug(
                            "CheckEffectByTopLevelClass ${changedFile.file.name} effect trigger already satisfied, skip",
                        )
                    }
                }
                lastRoundCompiledPaths.contains(path) && !topLevelFacadeEffectedSourcePaths.contains(path) -> {
                    if (logSkips) {
                        logger.debug(
                            "CheckEffectByTopLevelClass ${changedFile.file.name} is in last round compiled set, skip",
                        )
                    }
                }
                else -> pending.add(changedFile)
            }
        }
        return pending
    }

    private fun buildTriggerKey(effectedPath: String, kind: String, triggerParts: List<String>): String {
        return "$effectedPath|$kind|${triggerParts.joinToString(",")}"
    }
}
