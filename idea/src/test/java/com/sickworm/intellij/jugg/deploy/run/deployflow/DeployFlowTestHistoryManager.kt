package com.sickworm.intellij.jugg.deploy.run.deployflow

import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.compiler.BuildTarget
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.deploy.CompileContextInfo
import com.sickworm.intellij.jugg.deploy.DeployContextRecoverInfo
import com.sickworm.intellij.jugg.deploy.DeployHistoryData
import com.sickworm.intellij.jugg.deploy.FullBuildInfo
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.project.ChangedFile
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import java.io.File

/**
 * Minimal in-memory [IDeployHistoryManager] for deploy-flow tests; overlay ids are set via real property API.
 */
class DeployFlowTestHistoryManager : IDeployHistoryManager {

    override val isRecoverFeatureAvailable: Boolean = true

    override val hasBeenFullCompiled: Boolean = true

    override var isLastFullCompileFailed: Boolean = false

    override var lastDeployOverlayIds: Map<String, String> = emptyMap()

    override var isCleanAndReinstall: Boolean = false

    override val historyProjectDir: File? = null

    override fun deleteDeployHistory() = Unit

    override fun tryGetContextRecoverInfoFromDb(isOnInit: Boolean): DeployContextRecoverInfo? = null

    override fun getChangedFilesSinceLastFullCompiled(): List<File>? = emptyList()

    override fun beforeFullCompiled(changedFiles: List<ChangedFile>) = Unit

    override fun reInitAfterFullCompiled(
        fullBuildInfo: FullBuildInfo,
        apkInfos: List<ApkInfo>,
        modules: Map<String, ModuleInfo>,
        startCompileTime: Long,
    ): CompileContextInfo = CompileContextInfo(apkInfos, emptyMap())

    override fun getFullBuildInfo(): FullBuildInfo? = null

    override fun updateApkInfos(apkInfos: List<ApkInfo>) = Unit

    override fun isBuildTargetChanged(options: JuggGradleCompileOptions): Boolean = false

    override fun beforeIncrementalCompile(sourceFiles: List<ChangedFile>) = Unit

    override fun updateHistoryOnAfterDeployed(deployedFiles: List<CompileOutput>) = Unit

    override fun filterUnchangedFiles(files: List<File>): List<File> = files

    override fun getLastBuildFiles(files: List<ChangedFile>): List<Pair<ChangedFile, File?>> =
        files.map { it to null }

    override fun getDeployHistoryData(): DeployHistoryData? = null

    override fun checkProjectDirChanged() = Unit

    override fun updateDontFilterIgnoredFileRules(rules: List<String>) = Unit
}
