package com.sickworm.intellij.jugg.project.dependency

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.project.change.ChangedFile
import java.io.File

/**
 * IDependencyChangeManager coordinates dependency-diff detection and follow-up compile/deploy decisions.
 */
interface IDependencyChangeManager: IDependencyChangeManagerEventCallback {

    val changeStatus: ChangeStatus

    val isNeedCompilation: Boolean

    fun init(cacheDirectory: File, compileContext: ICompileContext)

    fun applyDependencyChangeDecision(diffResultSet: DependencyDiffResultSet?, isConfirmed: Boolean)

    fun getNewLibraryFiles(): List<ChangedFile>

    fun getRemovedLibraryFiles(): List<ChangedFile>

    /**
     * ChangeStatus represents dependency-change handling stages from clean to rebuild/incremental.
     */
    enum class ChangeStatus {
        NO_CHANGE,
        CHANGED_NOT_SYNCED,
        REBUILD,
        INCREMENTAL_COMPILE,
    }

    companion object
}


/**
 * IDependencyChangeManagerEventCallback reports sync/build lifecycle events from dependency change handling.
 */
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
