package com.sickworm.intellij.jugg.ide

import com.android.ddmlib.IDevice
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.diagnostic.DefaultLogger
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.DumbProgressIndicator
import com.sickworm.intellij.jugg.JuggManager
import com.sickworm.intellij.jugg.compiler.JuggCompilerHelper
import com.sickworm.intellij.jugg.compiler.isWindows
import com.sickworm.intellij.jugg.deploy.*
import com.sickworm.intellij.jugg.deploy.run.IAsDeployerCompat
import com.sickworm.intellij.jugg.ide.ui.CommonConfirmDialog
import com.sickworm.intellij.jugg.ide.ui.JuggMoreOptionsItem
import com.sickworm.intellij.jugg.ide.ui.SimpleProcessHandler
import com.sickworm.intellij.jugg.logger.getInstance
import com.sickworm.intellij.jugg.project.CompileContextManager
import com.sickworm.intellij.jugg.project.JuggPathManager
import com.sickworm.intellij.jugg.project.TaskRunnerManager
import com.sickworm.intellij.jugg.project.dependency.IDependencyChangeManager
import com.sickworm.intellij.jugg.server.JuggServer

class MoreOptionsManager(
    private val juggManager: JuggManager,
    private val pathManager: JuggPathManager,
    private val taskRunnerManager: TaskRunnerManager,
    private val deployHistoryManager: IDeployHistoryManager,
    private val deployTargetManager: IDeployTargetManager,
    private val juggRunningTaskStatusManager: IJuggRunningTaskStatusManager,
    private val dependencyChangeManager: IDependencyChangeManager,
    private val juggCompilerHelper: JuggCompilerHelper,
    private val juggServer: JuggServer,
    logger: Logger,
) {

    private val logger = logger.getInstance("MoreOptionsManager")

    fun createOptions(options: JuggRunConfigurationOptions): ActionGroup {
        val group = DefaultActionGroup()
        getOptionList(options).forEach {
            if (it.isSplitLine) {
                group.addSeparator(it.name)
            } else {
                group.add(it.toAction())
            }
        }
        return group
    }

    private fun getOptionList(options: JuggRunConfigurationOptions): List<JuggMoreOptionsItem> {

        val items = mutableListOf<JuggMoreOptionsItem>()

        fun createSplitLine(name: String) {
            val item = JuggMoreOptionsItem(
                name = name,
                isSplitLine = true
            )
            items.add(item)
        }

        fun createOption(
            name: String,
            onGet: (JuggMoreOptionsItem.() -> Boolean)? = null,
            onSet: JuggMoreOptionsItem.(Boolean) -> Unit = { },
        ) {
            val item = JuggMoreOptionsItem(name, onGet, onSet)
            items.add(item)
        }

        createSplitLine("Run Options")

        createOption(
            name = "Confirm fallback when no file changes",
            { JuggSettings.isConfirmFallbackWhenNoFileChanges },
            { JuggSettings.isConfirmFallbackWhenNoFileChanges = it }
        )

        createSplitLine("Tools")

        createOption(
            name = "Copy generated source to local",
            onSet = { juggManager.copyGeneratedSourceToLocal() }
        )

        createOption(
            name = "Set custom server URL",
            onSet = { setCustomServerUrl() }
        )

        createSplitLine("Function switches")

        createOption(
            name = "Enable inject Gradle compilation",
            onGet = { JuggSettings.isEnableInjectGradleCompile },
            onSet = {
                val isConfirmed = CommonConfirmDialog.showAndGetResult(
                    "Confirm Switch Inject Gradle Compilation",
                    "<html>This will reset compiler and fallback to gradle compile next time.<br>Are you sure to continue?</html>"
                )
                if (isConfirmed) {
                    JuggSettings.isEnableInjectGradleCompile = it
                    enableInjectGradleCompilation()
                }
            }
        )

        if (!isWindows) {
            createOption(
                name = "Enable backup classpath",
                onGet = { JuggSettings.isEnableBackupClasspath },
                onSet = {
                    val isConfirmed = CommonConfirmDialog.showAndGetResult(
                        "Confirm Switch Backup Classpath",
                        "<html>This will effects compilation stability. Continue?</html>"
                    )
                    if (isConfirmed) {
                        JuggSettings.isEnableBackupClasspath = it
                        setEnableBackupClasspath()
                    }
                }
            )
        }

        if (JuggSettings.isEnableInjectGradleCompile) {
            createOption(
                name = "Enable read project info from Gradle",
                onGet = { JuggSettings.isEnableReadProjectInfoFromGradle },
                onSet = {
                    JuggSettings.isEnableReadProjectInfoFromGradle = it
                    enableReadProjectFromGradle()
                }
            )

            createOption(
                name = "Enable compatible deployment mode",
                onGet = { JuggSettings.isEnableCompatibleDeploymentMode },
                onSet = {
                    JuggSettings.isEnableCompatibleDeploymentMode = it
                    enableCompatibleDeploymentMode()
                }
            )

            val devices = getDevices()
            devices.forEach {
                val compatDeployHelper = CompatDeployHelper(logger)
                val adb = IdeaDeviceAdb(it, DefaultLogger("CompatDeployHelper"))
                createOption(
                    name = "Force use compat deploy for ${adb.displayName}",
                    onGet = { compatDeployHelper.isForceCompatDevice(adb) },
                    onSet = {
                        setForceCompatDevice(adb)
                    }
                )
            }
        }

        createSplitLine("(Test) Mock Events")

        createOption(
            name = "Mark as project synced and re-init compiler",
            onSet = {
                val isConfirmed = CommonConfirmDialog.showAndGetResult(
                    "Confirm Mark as Project Synced and Re-init Compiler",
                    "<html>This will reload project info and re-init, but dependencies won't update without sync.<br>Are you sure to continue?</html>"
                )
                if (isConfirmed) {
                    markAsSyncedAndReInitCompiler()
                }
            }
        )

        createOption(
            name = "Mark as gradle compiled and re-init compiler",
            onSet = {
                val isConfirmed = CommonConfirmDialog.showAndGetResult(
                    "Confirm Mark as Gradle Compiled and Re-init Compiler",
                    "<html>This will skip gradle compilation and re-init, but the behavior of Jugg may incorrect.<br>Are you sure to continue?</html>"
                )
                if (isConfirmed) {
                    markAsGradleCompiledAndReInitCompiler(options)
                }
            }
        )

        return items
    }

    private fun enableInjectGradleCompilation() {
        logger.info("[options] enableInjectGradleCompilation")
        deployHistoryManager.deleteDeployHistory()
        enableReadProjectFromGradle()
        enableCompatibleDeploymentMode()
        IAsDeployerCompat.updateMinApi(JuggSettings.finalIsEnableCompatibleDeploymentMode)
    }

    private fun markAsSyncedAndReInitCompiler() {
        logger.info("[test options] markAsSyncedAndReInitCompiler")
        juggManager.onSyncEvent(SyncEvent.SUCCEEDED)
    }

    private fun enableReadProjectFromGradle() {
        logger.info("[options] enableReadProjectFromGradle")
        pathManager.gradleProjectInfoFile.delete()
        juggManager.onSyncEvent(SyncEvent.SUCCEEDED)
    }

    private fun setForceCompatDevice(adb: IDeviceAdb) {
        logger.info("[options] setForceCompatDevice ${adb.displayName}")

        val compatDeployHelper = CompatDeployHelper(logger)
        val isForceCompatDevice = compatDeployHelper.isForceCompatDevice(adb)
        if (isForceCompatDevice) {
            compatDeployHelper.clearCompatDeviceRecord(adb)
        } else {
            compatDeployHelper.recordCompatDeviceRecord(adb)
        }
        juggManager.forceReInstallNextTime()
    }


    private fun markAsGradleCompiledAndReInitCompiler(options: JuggRunConfigurationOptions) {
        logger.info("[test options] markAsGradleCompiledAndReInitCompiler")
        taskRunnerManager.runTaskSafe("Mark as Gradle Compiled", {
            // login and get apks
            dependencyChangeManager.onStartBuilding()
            val compileOptions = options.toCompileOptions(pathManager)
            val result = juggCompilerHelper.gradleCompile(
                compileOptions,
                SimpleProcessHandler(),
                taskRunnerManager.currentIndicator ?: DumbProgressIndicator.INSTANCE,
                isOnlyFetchResult = true,
            )
            dependencyChangeManager.onEndBuilding(result.isSuccess, result.isCanceled)
            if (!result.isSuccess) {
                logger.warn("gradleCompile(isOnlyFetchResult) failed, please check log for details.")
                return@runTaskSafe
            }

            // re-init compiler and mark all compiled
            juggManager.initIncrementalCompileAfterFullBuild(System.currentTimeMillis(), compileOptions.isRemoteCompile)
        })
    }

    private fun setCustomServerUrl() {
        logger.info("[options] setNewServerUrl")
        juggServer.setCustomServer()
    }

    private fun enableCompatibleDeploymentMode() {
        logger.info("[options] enableCompatibleDeploymentMode")
        IAsDeployerCompat.updateMinApi(JuggSettings.finalIsEnableCompatibleDeploymentMode)

        taskRunnerManager.runTaskSafe("Remove Jugg JVMTI agents", {
            val devices = deployTargetManager.getDevices()
            devices.forEach {
                val result = JuggJvmtiAgentManager(IdeaDeviceAdb(it, logger), logger).removeAllAgents()
                logger.debug("Remove Jugg JVMTI agents result: $result, device: $it")
            }
        })
    }

    private fun setEnableBackupClasspath() {
        logger.info("[options] setEnableBackupClasspath ${JuggSettings.isEnableBackupClasspath}")
        deployHistoryManager.deleteDeployHistory()
    }

    private fun getDevices(): List<IDevice> {
        return deployTargetManager.getDevices()
    }

}