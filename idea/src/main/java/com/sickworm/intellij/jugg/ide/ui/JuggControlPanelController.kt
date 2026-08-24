package com.sickworm.intellij.jugg.ide.ui

import com.intellij.execution.RunManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.sickworm.intellij.jugg.JuggManager
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.deploy.CompatDeployHelper
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.deploy.IdeaDeviceAdb
import com.sickworm.intellij.jugg.deploy.run.AsDeployerCompat
import com.sickworm.intellij.jugg.ide.JuggControlPanelHost
import com.sickworm.intellij.jugg.ide.SyncEvent
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.ide.controlpanel.JuggControlPanelModel
import com.sickworm.intellij.jugg.ide.controlpanel.JuggEvent
import java.util.UUID
import javax.swing.JComponent

/**
 * Coordinates real project facts, persisted settings, and control panel actions for one JuggManager.
 */
open class JuggControlPanelController(
    private val project: Project,
    private val manager: JuggManager,
    private val deployTargetManager: IDeployTargetManager,
    private val deployHistoryManager: IDeployHistoryManager,
    private val deployFileManager: DeployFileManager,
    private val logger: Logger,
) {
    val model = JuggControlPanelModel()
    private var panel: JuggControlPanel? = null
    private var syncEventTaskId: String? = null

    open fun getPanel(page: String): JComponent {
        val currentPanel = panel ?: JuggControlPanel(project, model, this).also {
            panel = it
            Disposer.register(manager, it)
        }
        refresh()
        currentPanel.selectPage(page)
        return currentPanel
    }

    open fun refresh() {
        val configuration = RunManager.getInstance(project).selectedConfiguration?.name.orEmpty()
        val devices = deployTargetManager.getDeviceNameList()
            ?.split(",")
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            .orEmpty()
        val changedFiles = deployFileManager.getUndeployedFiles().map { changedFile ->
            JuggEvent.ChangedFileSnapshot(
                category = when (changedFile.type) {
                    CompileFile.Type.BuildFile -> JuggEvent.ChangedFileCategory.BUILD
                    CompileFile.Type.Kotlin -> JuggEvent.ChangedFileCategory.KOTLIN
                    CompileFile.Type.Java -> JuggEvent.ChangedFileCategory.JAVA
                    CompileFile.Type.AndroidManifest -> JuggEvent.ChangedFileCategory.MANIFEST
                    CompileFile.Type.NativeLib -> JuggEvent.ChangedFileCategory.SO
                    CompileFile.Type.Resource -> JuggEvent.ChangedFileCategory.XML
                    else -> JuggEvent.ChangedFileCategory.OTHER
                },
                path = changedFile.file.name,
                absolutePath = changedFile.file.absolutePath,
                moduleName = changedFile.module.gradleModuleName ?: changedFile.module.name,
            )
        }.sortedWith(compareBy({ it.category.ordinal }, { it.moduleName }, { it.path }))
        val context = JuggControlPanelModel.Context(
            configuration = configuration,
            buildTarget = deployHistoryManager.getFullBuildInfo()?.buildTarget?.name.orEmpty(),
            packageName = deployTargetManager.getPackageNameOrNull().orEmpty(),
            devices = devices,
            changedFileCount = changedFiles.size,
            changedFiles = changedFiles,
            hasBaseline = deployHistoryManager.hasBeenFullCompiled,
            isHistoryAvailable = deployHistoryManager.isRecoverFeatureAvailable,
        )
        model.updateContext(context)
        model.updateSettings(currentSettings())
        model.updateHealth(buildHealth(context))
    }

    open fun refreshSettings() {
        model.updateSettings(currentSettings())
    }

    open fun updateSetting(setting: Setting, enabled: Boolean) {
        when (setting) {
            Setting.CONFIRM_FALLBACK -> JuggSettings.isConfirmFallbackWhenNoFileChanges = enabled
            Setting.ALWAYS_RESTART -> JuggSettings.isAlwaysRestartAppAfterDeployment = enabled
            Setting.QUICK_DEPLOY -> JuggSettings.isEnableDirectOverlayDeploy = enabled
            Setting.AUTO_FALLBACK -> JuggSettings.isAutoFallbackToGradleWhenDeployError = enabled
            Setting.EMBED_APK -> return updateEmbeddedToApk(enabled)
            Setting.PROJECT_KOTLIN -> JuggSettings.isUseProjectKotlinCompiler = enabled
            Setting.BACKUP_CLASSPATH -> return updateBackupClasspath(enabled)
        }
        model.updateSettings(currentSettings())
        recordSettingChanged(setting.displayName, enabled)
    }

    open fun updateForceCompatDevice(displayName: String, enabled: Boolean) {
        val adb = try {
            deployTargetManager.getConnectedDevices()
                .map { IdeaDeviceAdb(it, logger) }
                .firstOrNull { it.displayName == displayName }
        } catch (e: Exception) {
            logger.warn("Update force compat device $displayName failed", e)
            model.updateSettings(currentSettings())
            return
        }
        if (adb == null) {
            logger.debug("Skip force compat update because $displayName disconnected")
            model.updateSettings(currentSettings())
            return
        }
        val helper = CompatDeployHelper(logger)
        if (helper.isForceCompatDevice(adb) == enabled) return
        if (enabled) helper.recordCompatDeviceRecord(adb) else helper.clearCompatDeviceRecord(adb)
        manager.forceReInstallNextTime()
        model.updateSettings(currentSettings())
        recordSettingChanged("Force use compat deploy for $displayName", enabled)
    }

    private fun updateEmbeddedToApk(enabled: Boolean) {
        if (enabled && !JuggSettings.isEmbeddedToApk && !CommonConfirmDialog.showAndGetResult(
                "Enable Embedded to APK",
                "<html>This will embed incremental changes to APK, let incremental effects for Android RemoteViews, " +
                        "but it will cost more time to deploy.<br>Are you sure to enable?</html>"
            )) {
            model.updateSettings(currentSettings())
            return
        }
        JuggSettings.isEmbeddedToApk = enabled
        model.updateSettings(currentSettings())
        recordSettingChanged(Setting.EMBED_APK.displayName, enabled)
    }

    private fun updateBackupClasspath(enabled: Boolean) {
        val confirmed = CommonConfirmDialog.showAndGetResult(
            "Confirm Switch Backup Classpath",
            "<html>This will effects compilation stability. Continue?</html>"
        )
        if (!confirmed) {
            model.updateSettings(currentSettings())
            return
        }
        JuggSettings.isEnableBackupClasspath = enabled
        deployHistoryManager.deleteDeployHistory()
        model.updateSettings(currentSettings())
        recordSettingChanged(Setting.BACKUP_CLASSPATH.displayName, enabled)
    }

    private fun recordSettingChanged(name: String, enabled: Boolean) {
        recordEvent(
            taskId = UUID.randomUUID().toString(),
            category = JuggEvent.Category.USER_ACTION,
            phase = JuggEvent.Phase.COMPLETED,
            status = JuggEvent.Status.SUCCEEDED,
            title = "Setting changed",
            detail = "$name: ${if (enabled) "enabled" else "disabled"}",
            isTerminal = true,
        )
    }

    open fun recordUserAction(action: String) {
        recordEvent(
            taskId = UUID.randomUUID().toString(),
            category = JuggEvent.Category.USER_ACTION,
            phase = JuggEvent.Phase.COMPLETED,
            status = JuggEvent.Status.SUCCEEDED,
            title = "Action triggered",
            detail = action,
            isTerminal = true,
        )
    }

    open fun fullGradleBuild() = manager.gradleCompile()

    open fun runRemoteCommand() = manager.runRemoteCommand()

    open fun restartApp() {
        val taskId = UUID.randomUUID().toString()
        recordEvent(taskId, JuggEvent.Category.APP, JuggEvent.Phase.LAUNCHING, JuggEvent.Status.STARTED, "Restart app started")
        try {
            AsDeployerCompat.getSelectedDevices(project)?.forEach(deployTargetManager::restartApp)
            recordEvent(taskId, JuggEvent.Category.APP, JuggEvent.Phase.COMPLETED, JuggEvent.Status.SUCCEEDED, "Restart app completed", isTerminal = true)
        } catch (e: Throwable) {
            recordEvent(taskId, JuggEvent.Category.APP, JuggEvent.Phase.COMPLETED, JuggEvent.Status.FAILED, "Restart app failed", e.message, true)
            throw e
        }
    }

    open fun cleanAndReinstall() {
        val confirmed = CommonConfirmDialog.showAndGetResult(
            title = "Clear App Data",
            content = "<html>This will clear app data, run a full Gradle build, and reinstall the app.<br>Are you sure you want to continue?</html>",
            okButtonText = "Clear App Data",
        )
        recordEvent(
            taskId = UUID.randomUUID().toString(),
            category = JuggEvent.Category.USER_ACTION,
            phase = JuggEvent.Phase.COMPLETED,
            status = if (confirmed) JuggEvent.Status.SUCCEEDED else JuggEvent.Status.CANCELED,
            title = "Clear app data confirmation",
            detail = if (confirmed) "confirmed" else "canceled",
            isTerminal = true,
        )
        if (confirmed) manager.cleanAndReinstall()
    }

    open fun resetJuggCache() = manager.resetJuggCache()

    open fun reportIssue() = manager.reportIssue()

    open fun installSkills() = manager.installSkills()

    open fun checkUpdates() = manager.checkUpdates()

    open fun setCustomServerUrl() = manager.setCustomServerUrl()

    open fun markAsProjectSyncedAndReInitCompiler() {
        val confirmed = CommonConfirmDialog.showAndGetResult(
            "Confirm Mark as Project Synced and Re-init Compiler",
            "<html>This will reload project info and re-init, but dependencies won't update without sync.<br>Are you sure to continue?</html>"
        )
        if (confirmed) manager.markAsProjectSyncedAndReInitCompiler()
    }

    open fun markAsGradleCompiledAndReInitCompiler() {
        val confirmed = CommonConfirmDialog.showAndGetResult(
            "Confirm Mark as Gradle Compiled and Re-init Compiler",
            "<html>This will skip gradle compilation and re-init, but the behavior of Jugg may incorrect.<br>Are you sure to continue?</html>"
        )
        if (confirmed) manager.markAsGradleCompiledAndReInitCompiler()
    }

    fun recordSyncEvent(syncEvent: SyncEvent) {
        val taskId = when (syncEvent) {
            SyncEvent.STARTED -> UUID.randomUUID().toString().also { syncEventTaskId = it }
            else -> syncEventTaskId ?: UUID.randomUUID().toString()
        }
        val status = when (syncEvent) {
            SyncEvent.STARTED -> JuggEvent.Status.STARTED
            SyncEvent.SUCCEEDED -> JuggEvent.Status.SUCCEEDED
            SyncEvent.FAILED -> JuggEvent.Status.FAILED
            SyncEvent.SKIPPED -> JuggEvent.Status.SKIPPED
        }
        recordEvent(
            taskId = taskId,
            category = JuggEvent.Category.SYNC,
            phase = if (syncEvent == SyncEvent.STARTED) JuggEvent.Phase.PREPARING else JuggEvent.Phase.COMPLETED,
            status = status,
            title = "Gradle sync ${syncEvent.name.lowercase()}",
            isTerminal = syncEvent != SyncEvent.STARTED,
        )
        if (syncEvent != SyncEvent.STARTED) syncEventTaskId = null
    }

    fun clear() {
        panel = null
        JuggControlPanelHost.clear(project)
    }

    private fun currentSettings(): JuggControlPanelModel.Settings {
        return JuggControlPanelModel.Settings(
            confirmFallbackWhenNoFileChanges = JuggSettings.isConfirmFallbackWhenNoFileChanges,
            alwaysRestartAppAfterDeployment = JuggSettings.isAlwaysRestartAppAfterDeployment,
            quickDeploy = JuggSettings.isEnableDirectOverlayDeploy,
            autoFallbackAfterDeployFailure = JuggSettings.isAutoFallbackToGradleWhenDeployError,
            embedChangesIntoApk = JuggSettings.isEmbeddedToApk,
            useProjectKotlinCompiler = JuggSettings.isUseProjectKotlinCompiler,
            backupClasspath = JuggSettings.isEnableBackupClasspath,
            isInjectGradleCompileEnabled = JuggSettings.isEnableInjectGradleCompile,
            canUseBackupClasspath = JuggSettings.isCanUseBackupClasspath,
            forceCompatDevices = forceCompatDevices(),
        )
    }

    private fun forceCompatDevices(): List<JuggControlPanelModel.Settings.ForceCompatDevice> {
        if (!JuggSettings.isEnableInjectGradleCompile) return emptyList()
        return try {
            val helper = CompatDeployHelper(logger)
            deployTargetManager.getConnectedDevices().map {
                val adb = IdeaDeviceAdb(it, logger)
                JuggControlPanelModel.Settings.ForceCompatDevice(adb.displayName, helper.isForceCompatDevice(adb))
            }
        } catch (e: Exception) {
            logger.debug("Load force compat devices failed", e)
            emptyList()
        }
    }

    private fun buildHealth(context: JuggControlPanelModel.Context): List<JuggControlPanelModel.HealthItem> {
        return buildList {
            if (context.configuration.isEmpty()) add(JuggControlPanelModel.HealthItem(JuggEvent.Level.WARN, "No Jugg run configuration"))
            if (context.devices.isEmpty()) add(JuggControlPanelModel.HealthItem(JuggEvent.Level.WARN, "No selected device"))
            if (!context.hasBaseline) add(JuggControlPanelModel.HealthItem(JuggEvent.Level.WARN, "Run a full Gradle build to create a deploy baseline"))
            if (!context.isHistoryAvailable) add(JuggControlPanelModel.HealthItem(JuggEvent.Level.WARN, "Deploy history is unavailable"))
        }
    }

    private fun recordEvent(
        taskId: String,
        category: JuggEvent.Category,
        phase: JuggEvent.Phase,
        status: JuggEvent.Status,
        title: String,
        detail: String? = null,
        isTerminal: Boolean = false,
    ) {
        model.record(JuggEvent(
            taskId = taskId,
            source = JuggEvent.Source.IDE,
            category = category,
            phase = phase,
            status = status,
            level = if (status == JuggEvent.Status.FAILED) JuggEvent.Level.WARN else JuggEvent.Level.INFO,
            title = title,
            detail = detail,
            isTaskTerminal = isTerminal,
        ))
    }

    /** Identifies the persisted Jugg switch edited by a control panel toggle. */
    enum class Setting(val displayName: String) {
        CONFIRM_FALLBACK("Confirm fallback"),
        ALWAYS_RESTART("Always restart app"),
        QUICK_DEPLOY("Quick deploy"),
        AUTO_FALLBACK("Auto fallback"),
        EMBED_APK("Embed changes into APK"),
        PROJECT_KOTLIN("Project Kotlin compiler"),
        BACKUP_CLASSPATH("Backup classpath"),
    }
}
