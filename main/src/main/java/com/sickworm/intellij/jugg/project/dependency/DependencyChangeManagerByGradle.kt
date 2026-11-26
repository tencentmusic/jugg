package com.sickworm.intellij.jugg.project.dependency

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.project.change.ChangedFile
import com.sickworm.intellij.jugg.project.dependency.IDependencyChangeManager.ChangeStatus
import com.sickworm.intellij.jugg.project.info.ModuleInfo
import java.io.File

/**
 * Manage dependency change by Gradle project info diff
 */
class DependencyChangeManagerByGradle(private val logger: Logger) : IDependencyChangeManager {

    override var changeStatus = ChangeStatus.NO_CHANGE

    private var diffResultSet: DependencyDiffResultSet = DependencyDiffResultSet.createEmpty()

    private var isBuildChanged = false

    private var tempModule: ModuleInfo? = null

    override val isNeedCompilation: Boolean get() {
        return changeStatus == ChangeStatus.INCREMENTAL_COMPILE && diffResultSet.hasChanges
    }

    override fun init(cacheDirectory: File, compileContext: ICompileContext) {
        logger.debug("init")
        tempModule = compileContext.tempModule
    }

    override fun applyDependencyChangeDecision(diffResultSet: DependencyDiffResultSet?, isConfirmed: Boolean) {
        logger.debug("applyDependencyChangeDecision hasChanges: ${diffResultSet?.hasChanges}, isConfirmed: $isConfirmed")
        this.diffResultSet = diffResultSet ?: DependencyDiffResultSet.createEmpty()
        onConfirmIncrementalCompile(isConfirmed)
    }

    override fun getNewLibraryFiles(): List<ChangedFile> {
        logger.debug("getNewLibraryFiles")
        val tempModule = tempModule ?: return emptyList()
        return DependencyDiffResultHelper(logger, tempModule, diffResultSet.diffResult, diffResultSet.diffResultWithFull).getNewLibraryFiles()
    }

    override fun getRemovedLibraryFiles(): List<ChangedFile> {
        logger.debug("getRemovedLibraryFiles")
        val tempModule = tempModule ?: return emptyList()
        return DependencyDiffResultHelper(logger, tempModule, diffResultSet.diffResult, diffResultSet.diffResultWithFull).getRemovedLibraryFiles()
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
            diffResultSet = DependencyDiffResultSet.createEmpty()
            isBuildChanged = false
        } else {
            changeStatus = if (isCancelled) {
                if (isBuildChanged) {
                    ChangeStatus.CHANGED_NOT_SYNCED
                } else {
                    ChangeStatus.NO_CHANGE
                }
            } else {
                changeStatus // build failed, keep previous status
            }
        }
    }
}
