package com.sickworm.intellij.jugg.project.dependency

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.ide.bean.ConfirmResult
import com.sickworm.intellij.jugg.project.ChangedFile
import java.io.File

/**
 * Used to manage the change of dependencies for library incremental compilation & deployment.
 */
interface IDependencyChangeManager: IDependencyChangeManagerEventCallback {

    val changeStatus: ChangeStatus

    val isNeedCompilation: Boolean

    fun init(cacheDirectory: File, compileContext: ICompileContext)

    fun tryShowChangeConfirmDialog(specificDependencyDiffResultSet: DependencyDiffResultSet? = null, isRunCompileLater: Boolean = false): ConfirmResult

    fun getNewLibraryFiles(): List<ChangedFile>

    fun getRemovedLibraryFiles(): List<ChangedFile>

    enum class ChangeStatus {
        NO_CHANGE,
        CHANGED_NOT_SYNCED,
        REBUILD,
        INCREMENTAL_COMPILE,
    }

    companion object
}


interface IDependencyChangeManagerEventCallback {

    fun onUpdateChangedBuildFiles(files: List<File>)

    fun onStartSyncing(isFromIde: Boolean) = Unit

    fun onEndSyncing(isFromIde: Boolean, isSuccess: Boolean, newContext: ICompileContext) = Unit

    fun onStartBuilding() = Unit

    fun onEndBuilding(isSuccess: Boolean, isCancelled: Boolean) = Unit

    fun onConfirmIncrementalCompile(isConfirmed: Boolean)
}

fun IDependencyChangeManager.Companion.create(logger: Logger): IDependencyChangeManager {
    return DependencyChangeManagerByGradle(logger)
}

