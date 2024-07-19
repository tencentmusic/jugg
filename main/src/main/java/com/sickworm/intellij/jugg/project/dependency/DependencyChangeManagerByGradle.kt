package com.sickworm.intellij.jugg.project.dependency

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.ide.ConfirmResult
import com.sickworm.intellij.jugg.platform.PlatformApi
import com.sickworm.intellij.jugg.project.ChangedFile
import com.sickworm.intellij.jugg.project.dependency.IDependencyChangeManager.ChangeStatus
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import java.io.File

/**
 * Manage dependency change by Gradle project info diff
 */
class DependencyChangeManagerByGradle(private val logger: Logger) : IDependencyChangeManager {

    override var changeStatus = ChangeStatus.NO_CHANGE

    private var diffResult: DependencyDiffResult = DependencyDiffResult.createEmpty()

    private var isBuildChanged = false

    private var tempModule: ModuleInfo? = null

    override val isNeedCompilation: Boolean get() {
        return changeStatus == ChangeStatus.INCREMENTAL_COMPILE && diffResult.hasChanges
    }

    override fun init(cacheDirectory: File, compileContext: ICompileContext) {
        logger.debug("init")
        tempModule = compileContext.tempModule
    }

    override fun tryShowChangeConfirmDialog(
        specificDependencyDiffResult: DependencyDiffResult?,
        isRunCompileLater: Boolean
    ): ConfirmResult {
        logger.debug("tryShowChangeConfirmDialog hasChanges: ${specificDependencyDiffResult?.hasChanges} isRunCompileLater: $isRunCompileLater")
        if (isRunCompileLater) {
            // only handles action that run immediately
            return ConfirmResult.INVALID
        }
        diffResult = specificDependencyDiffResult ?: DependencyDiffResult.createEmpty()
        val confirmResult = PlatformApi.showChangeConfirmDialog(specificDependencyDiffResult, false, logger)
        if (confirmResult != ConfirmResult.CANCEL) {
            onConfirmIncrementalCompile(confirmResult.isConfirmed)
        }
        return confirmResult
    }

    override fun getNewLibraryFiles(): List<ChangedFile> {
        logger.debug("getNewLibraryFiles")
        val tempModule = tempModule ?: return emptyList()
        return DependencyDiffResultHelper(logger, tempModule, diffResult).getNewLibraryFiles()
    }

    override fun getRemovedLibraryFiles(): List<ChangedFile> {
        logger.debug("getRemovedLibraryFiles")
        val tempModule = tempModule ?: return emptyList()
        return DependencyDiffResultHelper(logger, tempModule, diffResult).getRemovedLibraryFiles()
    }

    override fun onUpdateChangedBuildFiles(files: List<File>) {
        isBuildChanged = files.isNotEmpty()
        changeStatus = if (isBuildChanged) {
            ChangeStatus.CHANGED_NOT_SYNCED
        } else {
            ChangeStatus.NO_CHANGE
        }
        logger.debug("onUpdateChangedBuildFiles isBuildChanged: $isBuildChanged")
    }

    override fun onConfirmIncrementalCompile(isConfirmed: Boolean) {
        logger.debug("onConfirmIncrementalCompile isConfirmed: $isConfirmed")
        changeStatus = when {
            isConfirmed -> ChangeStatus.INCREMENTAL_COMPILE
            isBuildChanged -> ChangeStatus.REBUILD
            else -> ChangeStatus.NO_CHANGE
        }
        logger.debug("onConfirmIncrementalCompile changeStatus: $changeStatus")
    }

    override fun onEndBuilding(isSuccess: Boolean, isCancelled: Boolean) {
        logger.debug("onEndBuilding isSuccess: $isSuccess, isCancelled: $isCancelled, isBuildChanged: $isBuildChanged")
        if (isSuccess) {
            changeStatus = ChangeStatus.NO_CHANGE
            diffResult = DependencyDiffResult.createEmpty()
            isBuildChanged = false
        } else {
            changeStatus = if (isCancelled) {
                if (isBuildChanged) {
                    ChangeStatus.CHANGED_NOT_SYNCED
                } else {
                    ChangeStatus.NO_CHANGE
                }
            } else {
                if (isBuildChanged) {
                    ChangeStatus.REBUILD
                } else {
                    ChangeStatus.NO_CHANGE
                }
            }
        }
    }
}