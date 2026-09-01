package com.sickworm.intellij.jugg.project.runtime

import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
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

    fun ensureConfiguration(): Boolean {
        val existingSettings = runManager.getConfigurationSettingsList(JuggConfigurationType::class.java)
        if (existingSettings.isNotEmpty()) {
            ensureImportedConfigurations(existingSettings)
            return true
        }
        val configuration = CliRunConfigurationGenerator.generate(compileContextManager.getProjectInfo())
        val factory = JuggConfigurationType.getInstance().configurationFactories[0]
        val settings = runManager.createConfiguration(configuration.name, factory)
        val ideaConfiguration = settings.configuration as? JuggRunConfiguration ?: return false
        configuration.applyTo(ideaConfiguration.state ?: return false)
        settings.isActivateToolWindowBeforeRun = false
        runManager.addConfiguration(settings)
        runManager.selectedConfiguration = settings
        store.save(configuration)
        store.select(configuration.id)
        return true
    }

    fun syncExistingConfigurations(): List<CliRunConfiguration> {
        val projectInfo = compileContextManager.getProjectInfo()
        val configurations = runManager.getConfigurationSettingsList(JuggConfigurationType::class.java)
            .mapNotNull { toCliConfiguration(it, projectInfo) }
        configurations.forEach(store::save)
        val selected = runManager.selectedConfiguration
            ?.takeIf { it.configuration is JuggRunConfiguration }
            ?.let { selectedSettings -> configurations.firstOrNull { it.id == (selectedSettings.configuration as JuggRunConfiguration).state?.cliRunConfigurationId } }
        selected?.let { store.select(it.id) }
        return configurations
    }

    /** Reconciles profiles and follows IDEA active variants only when source and target are exact generated configs. */
    fun reconcileActiveBuildVariants(suggestions: List<SuggestRunConfiguration>): List<CliRunConfiguration> {
        val projectInfo = compileContextManager.getProjectInfo()
        val settings = runManager.getConfigurationSettingsList(JuggConfigurationType::class.java).toMutableList()
        val configurations = settings.mapNotNull { toCliConfiguration(it, projectInfo) }.toMutableList()
        configurations.forEach(store::save)
        val factory = (settings.firstOrNull()?.configuration as? JuggRunConfiguration)?.factory
            ?: JuggConfigurationType.getInstance().configurationFactories[0]
        projectInfo.modules.values
            .filter { it.moduleType == ModuleInfo.Type.Application && !it.isAndroidTestModule }
            .forEach { module ->
                val expected = CliRunConfigurationGenerator.generateForModule(module)
                if (configurations.none { matchesConfiguration(it, expected, module, projectInfo) }) {
                    createConfiguration(expected, factory)?.let { (createdSettings, createdConfiguration) ->
                        settings += createdSettings
                        configurations += createdConfiguration
                    }
                }
            }
        selectActiveVariant(settings, configurations, projectInfo, factory, suggestions)
        return configurations
    }

    private fun matchesConfiguration(
        existing: CliRunConfiguration,
        expected: CliRunConfiguration,
        module: ModuleInfo,
        projectInfo: JuggProjectInfo,
    ): Boolean {
        if (existing.id == expected.id) return true
        if (existing.variant != expected.variant) return false
        if (CliRunConfigurationGenerator.matchesBuildIdentity(existing.compileCommand, module, expected.variant)) return true
        val matchingNames = projectInfo.modules.values.count {
            it.moduleType == ModuleInfo.Type.Application && it.name == module.name
        }
        return matchingNames == 1 && existing.moduleName == expected.moduleName
    }

    fun onRunConfigurationSelected(settings: RunnerAndConfigurationSettings?) {
        val configuration = settings?.configuration
        if (configuration !is JuggRunConfiguration || runManager.selectedConfiguration?.configuration !== configuration) {
            return
        }
        val cliConfiguration = toCliConfiguration(settings, compileContextManager.getProjectInfo()) ?: return
        store.save(cliConfiguration)
        store.select(cliConfiguration.id)
    }

    fun onRunConfigurationChanged(settings: RunnerAndConfigurationSettings) {
        val configuration = toCliConfiguration(settings, compileContextManager.getProjectInfo()) ?: return
        store.save(configuration)
        val selectedId = (runManager.selectedConfiguration?.configuration as? JuggRunConfiguration)?.state?.cliRunConfigurationId
        if (selectedId == configuration.id) {
            store.select(configuration.id)
        }
    }

    fun updateAfterSuccessfulGradleBuild(options: JuggGradleCompileOptions) {
        val projectInfo = compileContextManager.getProjectInfo()
        val current = store.loadCurrent() ?: selectedConfiguration(projectInfo)
            ?: CliRunConfigurationGenerator.generate(projectInfo)
        val updated = CliRunConfigurationGenerator.fromCompileOptions(current, options, projectInfo)
        store.save(updated)
        store.select(updated.id)
    }

    private fun selectedConfiguration(projectInfo: JuggProjectInfo): CliRunConfiguration? {
        val settings = runManager.selectedConfiguration ?: return null
        return toCliConfiguration(settings, projectInfo)
    }

    private fun createConfiguration(
        configuration: CliRunConfiguration,
        factory: com.intellij.execution.configurations.ConfigurationFactory,
    ): Pair<RunnerAndConfigurationSettings, CliRunConfiguration>? {
        val settings = runManager.createConfiguration(configuration.name, factory)
        val ideaConfiguration = settings.configuration as? JuggRunConfiguration ?: return null
        configuration.applyTo(ideaConfiguration.state ?: return null)
        settings.isActivateToolWindowBeforeRun = false
        runManager.addConfiguration(settings)
        store.save(configuration)
        return settings to configuration
    }

    private fun selectActiveVariant(
        settings: MutableList<RunnerAndConfigurationSettings>,
        configurations: MutableList<CliRunConfiguration>,
        projectInfo: JuggProjectInfo,
        factory: com.intellij.execution.configurations.ConfigurationFactory,
        suggestions: List<SuggestRunConfiguration>,
    ) {
        val selectedSettings = runManager.selectedConfiguration ?: return
        if (selectedSettings.configuration !is JuggRunConfiguration) return
        val selected = toCliConfiguration(selectedSettings, projectInfo) ?: return
        val selectedCommand = generatedCommand(selected.compileCommand)
        if (selectedCommand == null) {
            logger.debug("Keep selected Jugg configuration because its command is custom, " +
                    "configuration=${selectedSettings.name}")
            store.select(selected.id)
            return
        }
        val activeSuggestions = suggestions.filter { suggestion ->
            val command = generatedCommand(suggestion.compileCommand)
            command?.modulePath == selectedCommand.modulePath && command.variant == suggestion.variantName
        }
        if (activeSuggestions.size != 1) {
            logger.debug("Keep selected Jugg configuration because active variant suggestion is not unique, " +
                    "modulePath=${selectedCommand.modulePath}, count=${activeSuggestions.size}")
            store.select(selected.id)
            return
        }
        val activeSuggestion = activeSuggestions.single()
        val activeCommand = generatedCommand(activeSuggestion.compileCommand) ?: return
        if (selectedCommand.variant == activeCommand.variant) {
            store.select(selected.id)
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
        factory: com.intellij.execution.configurations.ConfigurationFactory,
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
            store.select(selected.id)
            return
        }
        val activeId = (activeSettings.configuration as? JuggRunConfiguration)?.state?.cliRunConfigurationId
        val active = configurations.singleOrNull { it.id == activeId }?.copy(
            moduleName = suggestion.moduleName,
            variant = activeCommand.variant,
        ) ?: run {
            store.select(selected.id)
            return
        }
        logger.info("Active Build Variant changed, select ${activeSettings.name} configuration.")
        runManager.selectedConfiguration = activeSettings
        store.save(active)
        store.select(active.id)
    }

    /** Resolves a unique generated target and creates it only when no custom target already owns the variant. */
    private fun findOrCreateActiveSettings(
        settings: MutableList<RunnerAndConfigurationSettings>,
        configurations: MutableList<CliRunConfiguration>,
        projectInfo: JuggProjectInfo,
        factory: com.intellij.execution.configurations.ConfigurationFactory,
        suggestion: SuggestRunConfiguration,
        expected: CliRunConfiguration,
        activeVariant: String,
    ): RunnerAndConfigurationSettings? {
        val stableIdTargets = settings.filter { setting ->
            val state = (setting.configuration as? JuggRunConfiguration)?.state ?: return@filter false
            state.cliRunConfigurationId == expected.id
        }
        val exactTargets = settings.filter { setting ->
            val state = (setting.configuration as? JuggRunConfiguration)?.state ?: return@filter false
            state.compileCommand?.trim() == suggestion.compileCommand.trim() &&
                state.outputApkName == suggestion.outputApkPath
        }
        return when {
            stableIdTargets.size > 1 -> null
            stableIdTargets.size == 1 -> stableIdTargets.single().takeIf { setting ->
                (setting.configuration as? JuggRunConfiguration)?.state?.compileCommand?.trim() ==
                    expected.compileCommand
            }
            exactTargets.size > 1 -> null
            exactTargets.size == 1 -> exactTargets.single()
            hasCustomActiveTarget(configurations, projectInfo, expected, activeVariant) -> null
            else -> createConfiguration(expected, factory)?.also { (createdSettings, createdConfiguration) ->
                settings += createdSettings
                configurations += createdConfiguration
            }?.first
        }
    }

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
        return configurations.any {
            CliRunConfigurationGenerator.matchesBuildIdentity(it.compileCommand, module, activeVariant)
        }
    }

    /** Accepts only the exact single-task command generated by Jugg. */
    private fun generatedCommand(compileCommand: String): GeneratedCommand? {
        val match = Regex("^\\./gradlew (:\\S+):assemble([A-Z][A-Za-z0-9]*)$")
            .matchEntire(compileCommand.trim()) ?: return null
        val modulePath = match.groupValues[1]
        if (modulePath.split(':').drop(1).any { it.isEmpty() }) return null
        val variant = match.groupValues[2].replaceFirstChar {
            if (it.isUpperCase()) it.lowercase() else it.toString()
        }
        return GeneratedCommand(modulePath, variant)
    }

    private fun ensureImportedConfigurations(settings: List<RunnerAndConfigurationSettings>) {
        val projectInfo = compileContextManager.getProjectInfo()
        val configurations = settings.mapNotNull { toCliConfiguration(it, projectInfo) }
        configurations.forEach(store::save)
        val selectedId = (runManager.selectedConfiguration?.configuration as? JuggRunConfiguration)
            ?.state
            ?.cliRunConfigurationId
        if (selectedId != null && configurations.any { it.id == selectedId }) {
            store.select(selectedId)
        }
    }

    private fun toCliConfiguration(settings: RunnerAndConfigurationSettings, projectInfo: JuggProjectInfo): CliRunConfiguration? {
        val ideaConfiguration = settings.configuration as? JuggRunConfiguration ?: return null
        val options = ideaConfiguration.state ?: return null
        val id = options.cliRunConfigurationId?.takeIf(::isUuid) ?: UUID.randomUUID().toString().also {
            options.cliRunConfigurationId = it
        }
        val identity = CliRunConfigurationGenerator.resolveBuildIdentity(projectInfo, options.compileCommand.orEmpty())
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
        )
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
    }

    private fun isUuid(value: String): Boolean {
        return runCatching { UUID.fromString(value) }.isSuccess
    }

    private data class GeneratedCommand(val modulePath: String, val variant: String)
}
