package com.sickworm.intellij.jugg.project.runtime

import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.BuildTarget
import com.sickworm.intellij.jugg.compiler.context.CompileContextManager
import com.sickworm.intellij.jugg.deploy.run.SuggestRunConfiguration
import com.sickworm.intellij.jugg.ide.JuggConfigurationType
import com.sickworm.intellij.jugg.ide.JuggRunConfiguration
import com.sickworm.intellij.jugg.ide.JuggRunConfigurationOptions
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.project.info.JuggProjectInfo
import com.sickworm.intellij.jugg.project.info.ModuleInfo
import java.util.UUID

/**
 * Synchronizes IDEA Jugg run configurations with the shared project configuration collection.
 */
class IdeaCliRunConfigurationManager(
    private val runManager: RunManager,
    private val compileContextManager: CompileContextManager,
    private val store: CliRunConfigurationStore,
    private val logger: Logger,
) {

    /** Imports existing profiles and creates suggestion targets without a Gradle project-info fallback. */
    fun ensureConfiguration(suggestions: List<SuggestRunConfiguration> = emptyList()): Boolean {
        val existingSettings = runManager.getConfigurationSettingsList(JuggConfigurationType::class.java)
        if (hasUsableConfiguration(existingSettings)) {
            importConfigurations(existingSettings)
            return true
        }
        val created = createMissingConfigurations(
            suggestions.mapNotNull(::toSuggestedTarget),
            existingSettings,
            defaultFactory(),
        )
        val selected = created.firstOrNull() ?: return false
        runManager.selectedConfiguration = selected.first
        selectConfiguration(selected.second.id)
        return true
    }

    /** Reconciles profiles and follows IDEA active variants only when source and target are exact generated configs. */
    fun reconcileActiveBuildVariants(suggestions: List<SuggestRunConfiguration>): List<CliRunConfiguration> {
        val projectInfo = compileContextManager.getProjectInfo()
        val settings = runManager.getConfigurationSettingsList(JuggConfigurationType::class.java).toMutableList()
        val configurations = settings.mapNotNull { toCliConfiguration(it, projectInfo) }.toMutableList()
        configurations.forEach(::saveConfiguration)
        val factory = (settings.firstOrNull()?.configuration as? JuggRunConfiguration)?.factory ?: defaultFactory()
        createMissingConfigurations(suggestions.mapNotNull(::toSuggestedTarget), settings, factory)
            .forEach { (createdSettings, createdConfiguration) ->
                settings += createdSettings
                configurations += createdConfiguration
            }
        selectActiveVariant(settings, configurations, projectInfo, factory, suggestions)
        return configurations
    }

    /** Creates one deterministic Gradle project-info profile when no usable Jugg configuration exists. */
    fun ensureFallbackConfiguration(): Boolean {
        val existingSettings = runManager.getConfigurationSettingsList(JuggConfigurationType::class.java)
        if (hasUsableConfiguration(existingSettings)) {
            return false
        }
        val configuration = runCatching { CliRunConfigurationGenerator.generate(compileContextManager.getProjectInfo()) }
            .getOrElse {
                logger.debug("Skip ProjectInfo run configuration fallback because no application module is available")
                return false
            }
        val created = createMissingConfigurations(
            listOf(ConfigurationTarget(configuration)), existingSettings, defaultFactory(),
        )
        val selected = created.firstOrNull() ?: return false
        runManager.selectedConfiguration = selected.first
        selectConfiguration(selected.second.id)
        return true
    }

    private fun defaultFactory(): ConfigurationFactory {
        return JuggConfigurationType.getInstance().configurationFactories[0]
    }

    /** True when the RunManager already exposes a Jugg configuration the user can run. */
    private fun hasUsableConfiguration(settings: List<RunnerAndConfigurationSettings>): Boolean {
        return settings.any { !SuggestRunConfiguration.isDefaultRunConfigName(it.name) }
    }

    /** Resolves a suggestion into a stable profile only when its exact Gradle identity is unambiguous. */
    private fun toSuggestedTarget(suggestion: SuggestRunConfiguration): ConfigurationTarget? {
        val command = generatedCommand(suggestion.compileCommand) ?: return null
        val variant = suggestion.variantName?.let(::normalizeVariantName)?.takeIf { it.isNotBlank() } ?: return null
        if (command.variant != variant) {
            return null
        }
        val configuration = CliRunConfigurationGenerator.generateForModuleIdentity(
            modulePath = command.modulePath,
            moduleName = suggestion.moduleName,
            variant = variant,
            outputApkName = suggestion.outputApkPath,
        )
        return ConfigurationTarget(configuration, suggestion.baseRunConfigName, suggestion.runConfigName)
    }

    /** Creates the targets no existing command already covers and persists each one under its final name. */
    private fun createMissingConfigurations(
        targets: List<ConfigurationTarget>,
        existingSettings: List<RunnerAndConfigurationSettings>,
        factory: ConfigurationFactory,
    ): List<Pair<RunnerAndConfigurationSettings, CliRunConfiguration>> {
        val usedNames = existingSettings.map { it.name }.toMutableList()
        val existingCommands = existingSettings.map { it.compileCommand().orEmpty() }
        val created = mutableListOf<Pair<RunnerAndConfigurationSettings, CliRunConfiguration>>()
        targets
            .distinctBy { singleGradleTask(it.configuration.compileCommand) ?: it.configuration.compileCommand.trim() }
            .filterNot { target -> existingCommands.any { matchesCompileTarget(it, target.configuration.compileCommand) } }
            .filterNot { target -> ownsStableId(existingSettings, target.configuration) }
            .forEach { target ->
                val name = when {
                    target.variantName == null -> target.configuration.name
                    SuggestRunConfiguration.isDefaultRunConfigName(target.variantName) || target.baseName in usedNames -> {
                        target.variantName
                    }
                    else -> target.baseName ?: target.configuration.name
                }
                createConfiguration(target.configuration.copy(name = name), usedNames, factory)?.let { created += it }
            }
        return created
    }

    /** True when the stable id already belongs to a target whose command is not the exact generated one. */
    private fun ownsStableId(
        existingSettings: List<RunnerAndConfigurationSettings>,
        target: CliRunConfiguration,
    ): Boolean {
        val owned = existingSettings.any { setting ->
            setting.cliRunConfigurationId() == target.id && setting.compileCommand()?.trim() != target.compileCommand
        }
        if (owned) {
            logger.debug("Skip run configuration ${target.name} because its stable id already belongs to a custom target")
        }
        return owned
    }

    /** True when both commands resolve to the same unique Gradle task, otherwise compares the exact commands. */
    private fun matchesCompileTarget(existingCommand: String, targetCommand: String): Boolean {
        val existingTask = singleGradleTask(existingCommand)
        val targetTask = singleGradleTask(targetCommand)
        if (existingTask != null && targetTask != null) {
            return existingTask == targetTask
        }
        return existingCommand.trim() == targetCommand.trim()
    }

    /** Returns the unique Gradle task of a command, or null when it is not a single supported task. */
    private fun singleGradleTask(compileCommand: String): String? {
        val executableNames = setOf("gradle", "gradlew", "gradle.bat", "gradlew.bat")
        return compileCommand.split(Regex("\\s+"))
            .asSequence()
            .map { it.trim('\'', '"') }
            .filter {
                it.isNotEmpty() &&
                    it.substringAfterLast('/').substringAfterLast('\\') !in executableNames &&
                    !it.startsWith("-") &&
                    !it.contains("=")
            }
            .map { it.trimStart(':') }
            .toList()
            .singleOrNull()
    }

    fun onRunConfigurationSelected(settings: RunnerAndConfigurationSettings?) {
        val configuration = settings?.configuration
        if (configuration !is JuggRunConfiguration || runManager.selectedConfiguration?.configuration !== configuration) {
            return
        }
        val cliConfiguration = toCliConfiguration(settings, compileContextManager.getProjectInfo()) ?: return
        saveConfiguration(cliConfiguration)
        selectConfiguration(cliConfiguration.id)
    }

    fun onRunConfigurationChanged(settings: RunnerAndConfigurationSettings) {
        val configuration = toCliConfiguration(settings, compileContextManager.getProjectInfo()) ?: return
        saveConfiguration(configuration)
        val selectedId = (runManager.selectedConfiguration?.configuration as? JuggRunConfiguration)?.state?.cliRunConfigurationId
        if (selectedId == configuration.id) {
            selectConfiguration(configuration.id)
        }
    }

    fun updateAfterSuccessfulGradleBuild(options: JuggGradleCompileOptions) {
        val projectInfo = compileContextManager.getProjectInfo()
        val current = store.loadCurrent() ?: selectedConfiguration(projectInfo)
            ?: runCatching { CliRunConfigurationGenerator.generate(projectInfo) }.getOrNull()
            ?: run {
                logger.debug("Skip CLI run configuration update because no build identity is confirmed yet")
                return
        }
        val updated = CliRunConfigurationGenerator.fromCompileOptions(current, options, projectInfo)
        saveConfiguration(updated)
        selectConfiguration(updated.id)
    }

    private fun selectedConfiguration(projectInfo: JuggProjectInfo): CliRunConfiguration? {
        val settings = runManager.selectedConfiguration ?: return null
        return toCliConfiguration(settings, projectInfo)
    }

    /** Creates the IDEA configuration under its final unique name and persists the same name to the shared store. */
    private fun createConfiguration(
        target: CliRunConfiguration,
        usedNames: MutableList<String>,
        factory: ConfigurationFactory,
    ): Pair<RunnerAndConfigurationSettings, CliRunConfiguration>? {
        val name = RunManager.suggestUniqueName(target.name, usedNames)
        usedNames += name
        val settings = runManager.createConfiguration(name, factory)
        val ideaConfiguration = settings.configuration as? JuggRunConfiguration ?: return null
        val configuration = target.copy(name = name)
        configuration.applyTo(ideaConfiguration.state ?: return null)
        settings.isActivateToolWindowBeforeRun = false
        runManager.addConfiguration(settings)
        saveConfiguration(configuration)
        return settings to configuration
    }

    private fun selectActiveVariant(
        settings: MutableList<RunnerAndConfigurationSettings>,
        configurations: MutableList<CliRunConfiguration>,
        projectInfo: JuggProjectInfo,
        factory: ConfigurationFactory,
        suggestions: List<SuggestRunConfiguration>,
    ) {
        val selectedSettings = runManager.selectedConfiguration ?: return
        if (selectedSettings.configuration !is JuggRunConfiguration) return
        val selected = toCliConfiguration(selectedSettings, projectInfo) ?: return
        val selectedCommand = generatedCommand(selected.compileCommand)
        if (selectedCommand == null) {
            logger.debug("Keep selected Jugg configuration because its command is custom, " +
                    "configuration=${selectedSettings.name}")
            selectConfiguration(selected.id)
            return
        }
        val activeSuggestions = suggestions.filter { suggestion ->
            val command = generatedCommand(suggestion.compileCommand)
            val activeVariant = suggestion.variantName?.let(::normalizeVariantName) ?: return@filter false
            command?.modulePath == selectedCommand.modulePath &&
                command.variant == activeVariant
        }
        if (activeSuggestions.size != 1) {
            logger.debug("Keep selected Jugg configuration because active variant suggestion is not unique, " +
                    "modulePath=${selectedCommand.modulePath}, count=${activeSuggestions.size}")
            selectConfiguration(selected.id)
            return
        }
        val activeSuggestion = activeSuggestions.single()
        val activeCommand = generatedCommand(activeSuggestion.compileCommand) ?: return
        if (selectedCommand.variant == activeCommand.variant) {
            selectConfiguration(selected.id)
            return
        }
        selectOrCreateActiveConfiguration(
            settings, configurations, projectInfo, factory, selected, activeSuggestion, activeCommand,
        )
    }

    private fun selectOrCreateActiveConfiguration(
        settings: MutableList<RunnerAndConfigurationSettings>,
        configurations: MutableList<CliRunConfiguration>,
        projectInfo: JuggProjectInfo,
        factory: ConfigurationFactory,
        selected: CliRunConfiguration,
        suggestion: SuggestRunConfiguration,
        activeCommand: GeneratedCommand,
    ) {
        val expected = CliRunConfigurationGenerator.generateForModuleIdentity(
            modulePath = activeCommand.modulePath,
            moduleName = suggestion.moduleName,
            variant = activeCommand.variant,
            outputApkName = suggestion.outputApkPath,
        )
        val activeSettings = findOrCreateActiveSettings(
            settings, configurations, projectInfo, factory, suggestion, expected, activeCommand.variant,
        )
        if (activeSettings == null) {
            logger.debug("Keep selected Jugg configuration because active target is missing or ambiguous, " +
                    "modulePath=${activeCommand.modulePath}, variant=${activeCommand.variant}")
            selectConfiguration(selected.id)
            return
        }
        val activeId = (activeSettings.configuration as? JuggRunConfiguration)?.state?.cliRunConfigurationId
        val active = configurations.singleOrNull { it.id == activeId }?.copy(
            moduleName = suggestion.moduleName,
            variant = activeCommand.variant,
        ) ?: run {
            selectConfiguration(selected.id)
            return
        }
        logger.info("Active Build Variant changed, select ${activeSettings.name} configuration.")
        runManager.selectedConfiguration = activeSettings
        saveConfiguration(active)
        selectConfiguration(active.id)
    }

    /** Resolves a unique generated target and creates it only when no custom target already owns the variant. */
    private fun findOrCreateActiveSettings(
        settings: MutableList<RunnerAndConfigurationSettings>,
        configurations: MutableList<CliRunConfiguration>,
        projectInfo: JuggProjectInfo,
        factory: ConfigurationFactory,
        suggestion: SuggestRunConfiguration,
        expected: CliRunConfiguration,
        activeVariant: String,
    ): RunnerAndConfigurationSettings? {
        if (hasCustomActiveTarget(configurations, projectInfo, expected, activeVariant)) {
            return null
        }
        val stableIdTargets = settings.filter { it.cliRunConfigurationId() == expected.id }
        val exactTargets = settings.filter {
            it.compileCommand()?.trim() == suggestion.compileCommand.trim() &&
                it.outputApkName() == suggestion.outputApkPath
        }
        return when {
            stableIdTargets.size > 1 -> null
            stableIdTargets.size == 1 -> stableIdTargets.single()
                .takeIf { it.compileCommand()?.trim() == expected.compileCommand }
            exactTargets.size > 1 -> null
            exactTargets.size == 1 -> exactTargets.single()
            else -> createConfiguration(expected, settings.map { it.name }.toMutableList(), factory)
                ?.also { (createdSettings, createdConfiguration) ->
                    settings += createdSettings
                    configurations += createdConfiguration
                }?.first
        }
    }

    /** True when the active variant is owned by a target whose command is not the exact generated one. */
    private fun hasCustomActiveTarget(
        configurations: List<CliRunConfiguration>,
        projectInfo: JuggProjectInfo,
        expected: CliRunConfiguration,
        activeVariant: String,
    ): Boolean {
        val module = projectInfo.modules.values.singleOrNull { candidate ->
            candidate.moduleType == ModuleInfo.Type.Application && !candidate.isAndroidTestModule &&
                CliRunConfigurationGenerator.generateForModule(candidate.copy(buildVariant = activeVariant))
                    .compileCommand == expected.compileCommand
        } ?: return false
        return configurations.any { configuration ->
            configuration.compileCommand.trim() != expected.compileCommand &&
                CliRunConfigurationGenerator.matchesBuildIdentity(configuration.compileCommand, module, activeVariant)
        }
    }

    /** Accepts only the exact single-task command generated by Jugg. */
    private fun generatedCommand(compileCommand: String): GeneratedCommand? {
        val match = Regex("^\\./gradlew (:\\S+):assemble([A-Z][A-Za-z0-9]*)$")
            .matchEntire(compileCommand.trim()) ?: return null
        val modulePath = match.groupValues[1]
        if (modulePath.split(':').drop(1).any { it.isEmpty() }) return null
        val variant = normalizeVariantName(match.groupValues[2])
        return GeneratedCommand(modulePath, variant)
    }

    private fun normalizeVariantName(variant: String): String {
        return variant.replaceFirstChar {
            if (it.isUpperCase()) it.lowercase() else it.toString()
        }
    }

    /** Imports every configuration whose build identity is confirmed and keeps the shared pointer consistent. */
    private fun importConfigurations(settings: List<RunnerAndConfigurationSettings>) {
        val projectInfo = compileContextManager.getProjectInfo()
        val configurations = settings.mapNotNull { toCliConfiguration(it, projectInfo) }
        configurations.forEach(::saveConfiguration)
        val selectedId = runManager.selectedConfiguration?.cliRunConfigurationId()
        if (selectedId != null && configurations.any { it.id == selectedId }) {
            selectConfiguration(selectedId)
        }
    }

    private fun saveConfiguration(configuration: CliRunConfiguration) {
        try {
            store.save(configuration)
        } catch (e: Throwable) {
            logger.warn("Save CLI run configuration failed, configuration=${configuration.name}", e)
        }
    }

    private fun selectConfiguration(id: String) {
        try {
            store.select(id)
        } catch (e: Throwable) {
            logger.warn("Select CLI run configuration failed, id=$id", e)
        }
    }

    /** Resolves the shared profile of an IDEA configuration, or null when no source confirms its identity. */
    private fun toCliConfiguration(settings: RunnerAndConfigurationSettings, projectInfo: JuggProjectInfo): CliRunConfiguration? {
        val ideaConfiguration = settings.configuration as? JuggRunConfiguration ?: return null
        val options = ideaConfiguration.state ?: return null
        val id = options.cliRunConfigurationId?.takeIf(::isUuid) ?: UUID.randomUUID().toString().also {
            options.cliRunConfigurationId = it
        }
        val identity = CliRunConfigurationGenerator.resolveBuildIdentity(
            projectInfo,
            options.compileCommand.orEmpty(),
            store.load(id)?.let { it.moduleName to it.variant },
        ) ?: run {
            logger.debug("Skip importing Jugg configuration ${settings.name} because its build identity is unknown")
            return null
        }
        return CliRunConfiguration(
            id = id,
            name = settings.name,
            generatedBy = "idea",
            generatedAt = System.currentTimeMillis(),
            moduleName = identity.first,
            variant = identity.second,
            buildTarget = if (options.enableAndroidTest) BuildTarget.ANDROID_TEST else BuildTarget.APP,
            compileCommand = options.compileCommand.orEmpty(),
            outputApkName = options.outputApkName.orEmpty(),
            isRemoteCompile = options.isRemoteCompile,
            isSyncAllProjects = options.isSyncAllProjects,
            remoteSshUser = options.remoteSshUser.orEmpty(),
            remoteSshPassword = options.remoteSshPassword.orEmpty(),
            remoteSshIp = options.remoteSshIp.orEmpty(),
            remoteSshPort = options.remoteSshPort,
            localToRemoteIftConfigName = options.localToRemoteIftConfigName.orEmpty(),
            localToRemoteSyncPath = options.localToRemoteSyncPath.orEmpty(),
            remoteSyncPath = options.remoteSyncPath.orEmpty(),
            remoteToLocalIftConfigName = options.remoteToLocalIftConfigName.orEmpty(),
            remoteToLocalSyncPath = options.remoteToLocalSyncPath.orEmpty(),
            httpProxyIp = options.httpProxyIp.orEmpty(),
            httpProxyPort = options.httpProxyPort,
            syncMode = options.syncMode.orEmpty(),
            environmentVariables = options.environmentVariables.orEmpty(),
            remoteSyncExcludePatterns = options.remoteSyncExcludePatterns.orEmpty(),
            isRemoteSyncExcludePatternsCustomized = options.isRemoteSyncExcludePatternsCustomized,
        )
    }

    private fun RunnerAndConfigurationSettings.compileCommand(): String? {
        return (configuration as? JuggRunConfiguration)?.state?.compileCommand
    }

    private fun RunnerAndConfigurationSettings.outputApkName(): String? {
        return (configuration as? JuggRunConfiguration)?.state?.outputApkName
    }

    private fun RunnerAndConfigurationSettings.cliRunConfigurationId(): String? {
        return (configuration as? JuggRunConfiguration)?.state?.cliRunConfigurationId
    }

    private fun CliRunConfiguration.applyTo(options: JuggRunConfigurationOptions) {
        options.cliRunConfigurationId = id
        options.compileCommand = compileCommand
        options.outputApkName = outputApkName
        options.isRemoteCompile = isRemoteCompile
        options.isSyncAllProjects = isSyncAllProjects
        options.remoteSshUser = remoteSshUser
        options.remoteSshPassword = remoteSshPassword
        options.remoteSshIp = remoteSshIp
        options.remoteSshPort = remoteSshPort
        options.localToRemoteIftConfigName = localToRemoteIftConfigName
        options.localToRemoteSyncPath = localToRemoteSyncPath
        options.remoteSyncPath = remoteSyncPath
        options.remoteToLocalIftConfigName = remoteToLocalIftConfigName
        options.remoteToLocalSyncPath = remoteToLocalSyncPath
        options.httpProxyIp = httpProxyIp
        options.httpProxyPort = httpProxyPort
        options.syncMode = syncMode
        options.environmentVariables = environmentVariables
        options.enableAndroidTest = buildTarget == BuildTarget.ANDROID_TEST
        options.remoteSyncExcludePatterns = remoteSyncExcludePatterns
        options.isRemoteSyncExcludePatternsCustomized = isRemoteSyncExcludePatternsCustomized
    }

    private fun isUuid(value: String): Boolean {
        return runCatching { UUID.fromString(value) }.isSuccess
    }

    private data class ConfigurationTarget(
        val configuration: CliRunConfiguration,
        val baseName: String? = null,
        val variantName: String? = null,
    )

    private data class GeneratedCommand(val modulePath: String, val variant: String)
}
