package com.sickworm.intellij.jugg.ide.ui

import com.intellij.execution.RunManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.sickworm.intellij.jugg.JuggManager
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
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

    open fun updateSetting(setting: Setting, enabled: Boolean) {
        when (setting) {
            Setting.CONFIRM_FALLBACK -> JuggSettings.isConfirmFallbackWhenNoFileChanges = enabled
            Setting.ALWAYS_RESTART -> JuggSettings.isAlwaysRestartAppAfterDeployment = enabled
            Setting.COMPAT_DEPLOY -> {
                JuggSettings.isEnableCompatibleDeploymentMode = enabled
                manager.updateCompatibleDeploymentMode()
            }
            Setting.QUICK_DEPLOY -> JuggSettings.isEnableDirectOverlayDeploy = enabled
            Setting.AUTO_FALLBACK -> JuggSettings.isAutoFallbackToGradleWhenDeployError = enabled
            Setting.EMBED_APK -> JuggSettings.isEmbeddedToApk = enabled
            Setting.PROJECT_KOTLIN -> JuggSettings.isUseProjectKotlinCompiler = enabled
            Setting.BACKUP_CLASSPATH -> JuggSettings.isEnableBackupClasspath = enabled
        }
        model.updateSettings(currentSettings())
    }

    open fun fullGradleBuild() = manager.gradleCompile()

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
        if (confirmed) manager.cleanAndReinstall()
    }

    open fun resetJuggCache() = manager.resetJuggCache()

    open fun reportIssue() = manager.reportIssue()

    open fun installSkills() = manager.installSkills()

    open fun checkUpdates() = manager.checkUpdates()

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
            compatibleDeployment = JuggSettings.isEnableCompatibleDeploymentMode,
            quickDeploy = JuggSettings.isEnableDirectOverlayDeploy,
            autoFallbackAfterDeployFailure = JuggSettings.isAutoFallbackToGradleWhenDeployError,
            embedChangesIntoApk = JuggSettings.isEmbeddedToApk,
            useProjectKotlinCompiler = JuggSettings.isUseProjectKotlinCompiler,
            backupClasspath = JuggSettings.isEnableBackupClasspath,
        )
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
    enum class Setting {
        CONFIRM_FALLBACK,
        ALWAYS_RESTART,
        COMPAT_DEPLOY,
        QUICK_DEPLOY,
        AUTO_FALLBACK,
        EMBED_APK,
        PROJECT_KOTLIN,
        BACKUP_CLASSPATH,
    }
}
