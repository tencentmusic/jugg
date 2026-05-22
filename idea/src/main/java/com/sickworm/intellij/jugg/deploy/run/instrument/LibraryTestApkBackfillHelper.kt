package com.sickworm.intellij.jugg.deploy.run.instrument

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.apk.ApkInfoReader
import com.sickworm.intellij.jugg.compiler.BuildTarget
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.compiler.JuggGradleCompileTask
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.deploy.instrument.AndroidTestRunSpec
import com.sickworm.intellij.jugg.deploy.instrument.AndroidTestTargetResolveException
import com.sickworm.intellij.jugg.deploy.instrument.AndroidTestTargetResolver
import com.sickworm.intellij.jugg.deploy.instrument.LibraryTestApkBuildHistory
import com.sickworm.intellij.jugg.deploy.instrument.LibraryTestApkBuildRecord
import com.sickworm.intellij.jugg.deploy.instrument.LibraryTestApkBackfillPlan
import com.sickworm.intellij.jugg.deploy.instrument.LibraryTestApkBackfillPlanner
import com.sickworm.intellij.jugg.deploy.run.JuggDeployData
import com.sickworm.intellij.jugg.gradle.compile.IGradleCompileClient
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.ide.bean.SyncMode
import com.sickworm.intellij.jugg.project.CompileContextManager
import com.sickworm.intellij.jugg.project.JuggPathManager
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import java.io.File

/**
 * Builds and registers one missing self-targeting library Test APK for source-anchored instrumentation runs.
 */
class LibraryTestApkBackfillHelper(
    private val project: Project,
    private val pathManager: JuggPathManager,
    private val deployHistoryManager: IDeployHistoryManager,
    private val compileContextManager: CompileContextManager,
    private val compileClientFactory: () -> IGradleCompileClient,
    private val logger: Logger,
    private val apkInfoReader: (List<File>) -> List<ApkInfo> = { ApkInfoReader(logger).createApkInfo(it) },
    private val onApksBackfilled: (List<ApkInfo>) -> Unit,
    private val recordBuildHistory: (
        module: ModuleInfo,
        plan: LibraryTestApkBackfillPlan,
        compileCommand: String,
        apks: List<ApkInfo>,
    ) -> Unit = { module, plan, compileCommand, apks ->
        LibraryTestApkBuildHistory(pathManager.projectDir, logger = logger).record(
            LibraryTestApkBuildRecord(
                moduleName = module.name,
                buildVariant = module.buildVariant,
                compileCommand = compileCommand,
                compiledAt = System.currentTimeMillis(),
                apkPath = apks.firstOrNull()?.files?.firstOrNull()?.apkFile?.absolutePath.orEmpty(),
                outputApkPattern = plan.outputApkPattern,
            )
        )
    },
) {

    fun backfillIfNeeded(
        spec: AndroidTestRunSpec?,
        data: JuggDeployData,
        uiHandler: CompileUiHandler,
        installBackfilledApks: (List<ApkInfo>) -> Unit = {},
    ): JuggDeployData {
        val sourcePath = spec?.sourcePath?.takeIf { it.isNotBlank() } ?: return data
        val projectInfo = compileContextManager.getProjectInfo()
        val projectDir = projectInfo.modules.values.firstOrNull()?.projectRootDir
            ?: File(sourcePath).parentFile
            ?: File(".")
        val module = AndroidTestTargetResolver.resolveModule(sourcePath, projectDir, projectInfo.modules.values)
        if (isResolved(sourcePath, projectDir, projectInfo.modules.values, data.apks)) {
            return data
        }
        if (module.applicationId != module.instrumentationTargetPackage) {
            throw unresolvedTestApk(module)
        }

        val plan = LibraryTestApkBackfillPlanner.plan(module)
        val backfillOptions = createBackfillOptions(plan)
        logger.info("Library Test APK missing, building ${plan.gradleTask}")
        uiHandler.notifyByBalloon("Library Test APK missing. Run Gradle compile once to build the test APK.")
        val result = JuggGradleCompileTask(
            project = project,
            compileClient = compileClientFactory(),
            juggGradleCompileOptions = backfillOptions,
            uiHandler = uiHandler,
            isOnlyFetchResult = false,
            logger = logger,
        ).run()
        if (!result.isSuccess) {
            throw AndroidTestTargetResolveException(
                buildString {
                    appendLine("unable to build missing Library Test APK.")
                    appendLine("task: ${plan.gradleTask}")
                    appendLine("reason: ${result.failedReason}")
                }.trimEnd()
            )
        }

        val newApks = apkInfoReader(result.compileOutputFile)
        val mergedApks = mergeApks(data.apks, newApks)
        AndroidTestTargetResolver.resolve(sourcePath, projectDir, projectInfo.modules.values, mergedApks)
        // The Gradle-built test APK already contains the latest test artifacts, so install it as
        // a complete APK instead of feeding it this run's incremental deploy items.
        installBackfilledApks(newApks)
        onApksBackfilled(mergedApks)
        compileContextManager.updateApkInfos(mergedApks)
        runCatching {
            recordBuildHistory(module, plan, backfillOptions.compileCommand, newApks)
        }.onFailure {
            logger.warn("Failed to record library Test APK build history", it)
        }
        return data.copy(apks = mergedApks)
    }

    private fun isResolved(
        sourcePath: String,
        projectDir: File,
        modules: Collection<ModuleInfo>,
        apks: List<ApkInfo>,
    ): Boolean {
        val error = runCatching {
            AndroidTestTargetResolver.resolve(sourcePath, projectDir, modules, apks)
        }.exceptionOrNull()
        if (error == null) {
            return true
        }
        if (error is AndroidTestTargetResolveException &&
            error.message.orEmpty().contains("unable to resolve test APK")) {
            return false
        }
        throw error
    }

    private fun unresolvedTestApk(module: ModuleInfo): AndroidTestTargetResolveException {
        return AndroidTestTargetResolveException(
            buildString {
                appendLine("unable to resolve test APK for androidTest module.")
                appendLine("module: ${module.name}")
                appendLine("applicationId: ${module.applicationId.orEmpty()}")
            }.trimEnd()
        )
    }

    private fun createBackfillOptions(plan: LibraryTestApkBackfillPlan): JuggGradleCompileOptions {
        val fullBuildInfo = deployHistoryManager.getFullBuildInfo()
        val baseCommand = fullBuildInfo?.compileCommand?.substringBefore(" :")?.trim().takeUnless { it.isNullOrBlank() }
            ?: "./gradlew"
        return JuggGradleCompileOptions(
            projectRootPath = pathManager.projectDir.absolutePath,
            localClasspathStoragePath = pathManager.localClasspathStoragePathManager,
            initGradleFilePath = pathManager.initGradleFilePath.path,
            compileCommand = "$baseCommand ${plan.gradleTask}",
            outputApkName = plan.outputApkPattern,
            isRemoteCompile = false,
            isSyncAllProjects = false,
            remoteSshUser = "",
            remoteSshPassword = "",
            remoteSshIp = "",
            remoteSshPort = 22,
            localToRemoteIftConfigName = "",
            localToRemoteSyncPath = pathManager.projectDir.parentFile?.absolutePath ?: pathManager.projectDir.absolutePath,
            remoteSyncPath = "",
            remoteToLocalIftConfigName = "",
            remoteToLocalSyncPath = "",
            httpProxyIp = "",
            httpProxyPort = 0,
            syncMode = SyncMode.RSYNC_SIMPLE,
            environmentVariables = "",
            buildTarget = BuildTarget.APP,
        )
    }

    private fun mergeApks(oldApks: List<ApkInfo>, newApks: List<ApkInfo>): List<ApkInfo> {
        val newApplicationIds = newApks.map { it.applicationId }.toSet()
        return oldApks.filter { it.applicationId !in newApplicationIds } + newApks
    }
}
