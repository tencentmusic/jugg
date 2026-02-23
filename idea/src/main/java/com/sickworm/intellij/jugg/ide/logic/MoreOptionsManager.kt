package com.sickworm.intellij.jugg.ide.logic

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.DefaultLogger
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.DumbProgressIndicator
import com.sickworm.intellij.jugg.JuggManager
import com.sickworm.intellij.jugg.compiler.JuggCompileUiHandler
import com.sickworm.intellij.jugg.compiler.JuggCompilerHelper
import com.sickworm.intellij.jugg.deploy.*
import com.sickworm.intellij.jugg.deploy.run.IAsDeployerCompat
import com.sickworm.intellij.jugg.ide.JuggRunConfigurationOptions
import com.sickworm.intellij.jugg.ide.SyncEvent
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.ide.ui.CheckUpdatesProgressDialog
import com.sickworm.intellij.jugg.ide.ui.CommonConfirmDialog
import com.sickworm.intellij.jugg.ide.ui.JuggMoreOptionsItem
import com.sickworm.intellij.jugg.loader.JuggInitializer
import com.sickworm.intellij.jugg.logger.getInstance
import com.sickworm.intellij.jugg.project.JuggPathManager
import com.sickworm.intellij.jugg.project.TaskRunnerManager
import com.sickworm.intellij.jugg.project.dependency.IDependencyChangeManager
import com.sickworm.intellij.jugg.server.JuggHotUpdateDownloader
import com.sickworm.intellij.jugg.server.JuggServer
import kotlinx.coroutines.*

class MoreOptionsManager(
    private val juggManager: JuggManager,
    private val pathManager: JuggPathManager,
    private val taskRunnerManager: TaskRunnerManager,
    private val deployHistoryManager: IDeployHistoryManager,
    private val deployTargetManager: IDeployTargetManager,
    private val dependencyChangeManager: IDependencyChangeManager,
    private val juggCompilerHelper: JuggCompilerHelper,
    private val juggServer: JuggServer,
    private val juggHotUpdateDownloader: JuggHotUpdateDownloader,
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

        createOption(
            name = "Always restart app after deployment",
            { JuggSettings.isAlwaysRestartAppAfterDeployment  },
            { JuggSettings.isAlwaysRestartAppAfterDeployment = it }
        )

        if (JuggSettings.isEnableInjectGradleCompile) {
            createOption(
                name = "Embedded to APK(for Android RemoteViews)",
                { JuggSettings.isEmbeddedToApk  },
                {
                    var isConfirmed = true
                    if (!JuggSettings.isEmbeddedToApk) {
                        isConfirmed = CommonConfirmDialog.showAndGetResult(
                            "Enable Embedded to APK",
                            "<html>This will embed incremental changes to APK, let incremental effects for Android RemoteViews, " +
                                    "but it will cost more time to deploy.<br>Are you sure to enable?</html>"
                        )
                        if (isConfirmed) {
                            JuggSettings.isEmbeddedToApk = true
                        }
                    } else {
                        JuggSettings.isEmbeddedToApk = false
                    }

                }
            )

            val devices = deployTargetManager.getConnectedDevices()
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


        createSplitLine("Tools")

        createOption(
            name = "Set custom server URL",
            onSet = { setCustomServerUrl() }
        )

        createOption(
            name = "Check updates (current ${juggServer.version})",
            onSet = { checkUpdates() }
        )

        createOption(
            name = "Clean and reset Jugg",
            onSet = { cleanAndResetJugg() }
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
                    if (!it) {
                        JuggSettings.isEmbeddedToApk = false
                    }
                }
            }
        )

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


            createOption(
                name = "Enable use project Kotlin compiler",
                onGet = { JuggSettings.isUseProjectKotlinCompiler },
                onSet = {
                    JuggSettings.isUseProjectKotlinCompiler = it
                }
            )
        }

        if (JuggSettings.isCanUseBackupClasspath) {
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
                JuggCompileUiHandler(juggManager.project,
                    isForceGradleCompile = true, isRpcMode = false,
                    compileOptions, logger,
                    progressIndicator = taskRunnerManager.currentIndicator ?: DumbProgressIndicator.INSTANCE,
                ),
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
            val devices = deployTargetManager.getSelectedDevices()
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

    private fun checkUpdates() {
        val dialog = CheckUpdatesProgressDialog()

        taskRunnerManager.runBackgroundSafe("Check updates") {
            val hotUpdateData = juggHotUpdateDownloader.checkHotUpdate(isPositiveCheck = true)
            dialog.setHotUpdateData(hotUpdateData) {
                taskRunnerManager.runBackgroundSafe("Download updates") {
                    try {
                        juggHotUpdateDownloader.downloadAndInstallUpdate(hotUpdateData!!)
                        dialog.setResult(hotUpdateData.targetVersion, true, hotUpdateData.isNeedReinstall, null) {
                            dialog.disposeIfNeeded()
                            if (hotUpdateData.isNeedReinstall) {
                                // necessary to async, or restart will not work
                                CoroutineScope(Dispatchers.IO).launch {
                                    ApplicationManager.getApplication().restart()
                                }
                            } else {
                                JuggInitializer.reopenAllProjectsAsync()
                            }
                        }
                    } catch (e: Exception) {
                        logger.warn("Download updates failed: ", e)
                        dialog.setResult(hotUpdateData!!.targetVersion,
                            isSuccess = false,
                            isNeedReinstall = false,
                            failedReason = e.toString(),
                            onConfirmReopenProject = null
                        )
                    }
                }
            }
        }
        dialog.show()
    }

    private fun cleanAndResetJugg() {
        logger.info("[options] cleanAndResetJugg")
        val isConfirmed = CommonConfirmDialog.showAndGetResult(
            "Confirm Clean and Reset Jugg",
            "<html>This will delete all cache files and reopen project.<br>Are you sure to continue?</html>"
        )
        if (isConfirmed) {
            logger.info("cleanAndResetJugg confirmed, start delete all files")
            pathManager.juggRootDir.listFiles()?.forEach {
                try {
                    it.deleteRecursively()
                } catch (e: Exception) {
                    logger.warn("Cannot delete dir ${it.name}, skip", e)
                }
            }
            logger.info("cleanAndResetJugg delete finished, start reopen all projects")
            JuggInitializer.reopenAllProjectsAsync()
        }
    }
}
