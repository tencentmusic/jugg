package com.sickworm.intellij.jugg.project.dependency

import com.google.gson.Gson
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.ide.bean.ConfirmResult
import com.sickworm.intellij.jugg.platform.PlatformApi
import com.sickworm.intellij.jugg.project.ChangedFile
import com.sickworm.intellij.jugg.project.ProjectInfoSerializer
import com.sickworm.intellij.jugg.project.data.JuggProjectInfo
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Manage dependency change by IDE sync & Gradle fetch
 */
@Deprecated("this plan is hard to control, you know")
class DependencyChangeManagerBySync(private val logger: Logger) : IDependencyChangeManager {

    override val changeStatus get() = compareInfo.changeStatus

    override val isNeedCompilation: Boolean get() {
        if (changeStatus == IDependencyChangeManager.ChangeStatus.INCREMENTAL_COMPILE) {
            if (isAlreadyBuildAfterConfirm) {
                return false
            }
            if (getNewLibraryFiles().isNotEmpty() || getRemovedLibraryFiles().isNotEmpty()) {
                return true
            }
        }
        return false
    }

    private var hasInit: Boolean = false

    private lateinit var tempModule: ModuleInfo

    private lateinit var fullBuildProjectInfoSerializer: ProjectInfoSerializer
    private lateinit var lastBuildProjectInfoSerializer: ProjectInfoSerializer

    private var currentBuildDependencies: JuggProjectInfo? = null
    private var fullBuildDependencies: JuggProjectInfo?
        get() {
            return fullBuildProjectInfoSerializer.load()
        }
        set(value) {
            lastBuildDependencies = null
            fullBuildProjectInfoSerializer.save(value)
        }
    private var lastBuildDependencies: JuggProjectInfo?
        get() {
            return lastBuildProjectInfoSerializer.load() ?: fullBuildDependencies
        }
        set(value) {
            lastBuildProjectInfoSerializer.save(value)
        }
    private var diffResult = DependencyDiffResult.createEmpty()
    private var diffResultWithFull = DependencyDiffResult.createEmpty()

    private var compareInfo = object {
        val VERSION = 1

        var version = 1
        var changeStatus: IDependencyChangeManager.ChangeStatus = IDependencyChangeManager.ChangeStatus.NO_CHANGE
            set(value) { field = value ; autoSaveIfEnabled() }
        var startSyncingTime = 0L
            set(value) { field = value ; autoSaveIfEnabled() }
        var endSyncingTime = 0L
            set(value) { field = value ; autoSaveIfEnabled() }
        var startBuildingTime = 0L
            set(value) { field = value ; autoSaveIfEnabled() }
        var endBuildingTime = 0L
            set(value) { field = value ; autoSaveIfEnabled() }
        var lastBuildChangedTime = 0L
            set(value) { field = value ; autoSaveIfEnabled() }

        var isLastSyncUpdate = false
            set(value) { field = value ; autoSaveIfEnabled() }

        private var enableAutoSave: Boolean = true
        private fun autoSaveIfEnabled() {
            if (!enableAutoSave) {
                return
            }
            writeToFile()
        }

        var compareInfoCacheFile: File? = null
        private fun writeToFile() {
            compareInfoCacheFile?.parentFile?.mkdirs()
            compareInfoCacheFile?.writeText(Gson().toJson(this))
        }

        fun transaction(block: () -> Unit) {
            enableAutoSave = false
            block()
            enableAutoSave = true
            writeToFile()
        }
    }

    /**
     * true if Gradle called [onStartSyncing] (higher priority)
     * false if IDE [onStartSyncing], and no gradle syncing is running
     */
    private var isCurrentSyncFromGradle = false
    private var nextStartSyncingTime = 0L
    private var nextStartBuildingTime = 0L

    private val isBuilding get() = nextStartBuildingTime != 0L
    private val isSyncing get() = nextStartSyncingTime != 0L

    /** use to detect no file changes */
    private var isAlreadyBuildAfterConfirm = false

    @Synchronized
    override fun init(cacheDirectory: File, compileContext: ICompileContext) {
        logger.debug("init dependency change manager hasInit: $hasInit")
        if (hasInit) {
            return
        }

        this.tempModule = compileContext.tempModule

        cacheDirectory.mkdirs()
        currentBuildDependencies = JuggProjectInfo(compileContext.modules)
        lastBuildProjectInfoSerializer = ProjectInfoSerializer(File(cacheDirectory, "last_build_project_infos.json"), logger)
        fullBuildProjectInfoSerializer = ProjectInfoSerializer(File(cacheDirectory, "full_build_project_infos.json"), logger)

        val compareInfoCacheFile = File(cacheDirectory, "compare_info.json")
        if (compareInfoCacheFile.exists() && lastBuildDependencies != null && fullBuildDependencies != null) {
            logger.debug("load compare info cache")
            try {
                val cacheCompareInfo = Gson().fromJson(compareInfoCacheFile.readText(), compareInfo::class.java)
                if (cacheCompareInfo.version != compareInfo.VERSION) {
                    throw IllegalArgumentException("compare info cache version not match: " +
                            "${cacheCompareInfo.version} != ${compareInfo.version}")
                }
                compareInfo = cacheCompareInfo
            } catch (e: Exception) {
                logger.debug("incorrect compare info cache: $e")
            }
        } else {
            logger.debug("no compare info cache")
        }
        compareInfo.compareInfoCacheFile = compareInfoCacheFile

        updateDiffDependency(isOnInit = true)

        hasInit = true
    }

    @Synchronized
    override fun tryShowChangeConfirmDialog(
        specificDependencyDiffResultSet: DependencyDiffResultSet?,
        isRunCompileLater: Boolean
    ): ConfirmResult {
        if (!hasInit) return ConfirmResult.INVALID
        logger.debug("try show change confirm dialog, isFromIde: $isRunCompileLater, isCurrentSyncFromGradle: $isCurrentSyncFromGradle")
        if (isCurrentSyncFromGradle && isRunCompileLater) {
            logger.debug("try show change confirm dialog, current sync is gradle, ignore show dialog")
            return ConfirmResult.INVALID
        }

        val isBuildChangedAfterBuild = compareInfo.lastBuildChangedTime > 0 &&
                compareInfo.startBuildingTime > 0 &&
                compareInfo.lastBuildChangedTime > compareInfo.startBuildingTime

        if (isRunCompileLater) {
            if (isBuilding || isSyncing) {
                logger.debug("skip show change confirm dialog, is building or syncing")
                return ConfirmResult.INVALID
            }
            if (!isBuildChangedAfterBuild) {
                val isContentUpdate = diffResult.updatedLibraries.isNotEmpty() && diffResult.updatedLibraries.any { it.isContentUpdate }
                if (!isContentUpdate) {
                    // avoid showing confirm dialog after project opened and synced, and no build file updated
                    // but if there is any content update libraries, we should still show it, because
                    // content update could happen without build file changed
                    logger.debug("skip show change confirm dialog, isBuildChangedAfterBuild=false and isContentUpdate=false")
                    return ConfirmResult.INVALID
                }
            }
            if (!diffResult.hasChanges && isBuildChangedAfterBuild) {
                logger.debug("no changed libraries")
                if (lastBuildDependencies == null || fullBuildDependencies == null) {
                    logger.debug("show change confirm dialog, lastBuildDependencies or fullBuildDependencies is null")
                    PlatformApi.showDialog(
                        title = "Jugg: Dependency Incremental Compile Not Available",
                        content = """<html>
                    |<p>Please <b>sync</b> project once to enable dependency incremental compile.<br>
                    |</p>
                    |</html>
                    |""".trimMargin(),
                        okButtonText = "OK, I got it!",
                        isShowCancelButton = false,
                    )
                }
                return ConfirmResult.INVALID
            }
        }

        val confirmResult = PlatformApi.showChangeConfirmDialog(diffResult, isRunCompileLater, logger)
        if (confirmResult != ConfirmResult.CANCEL) {
            onConfirmIncrementalCompile(confirmResult.isConfirmed)
        }
        return confirmResult
    }

    @Synchronized
    override fun getNewLibraryFiles(): List<ChangedFile> {
        return DependencyDiffResultHelper(logger, tempModule, diffResult, diffResultWithFull).getNewLibraryFiles()
    }

    override fun getRemovedLibraryFiles(): List<ChangedFile> {
        return DependencyDiffResultHelper(logger, tempModule, diffResult, diffResultWithFull).getRemovedLibraryFiles()
    }

    @Synchronized
    override fun onUpdateChangedBuildFiles(files: List<File>) {
        if (!hasInit) return
        logger.debug("on update change build files: $files")

        if (files.isEmpty() && compareInfo.changeStatus == IDependencyChangeManager.ChangeStatus.CHANGED_NOT_SYNCED) {
            logger.debug("build changed files is empty and changeStatus is CHANGED_NOT_SYNCED, " +
                    "reset lastBuildChangedTime to 0, and reset changeStatus to NO_CHANGE")
            compareInfo.transaction {
                compareInfo.lastBuildChangedTime = 0
                compareInfo.changeStatus = IDependencyChangeManager.ChangeStatus.NO_CHANGE
            }
            return
        }

        val buildChangedTime = files.maxOfOrNull { it.lastModified() } ?: 0
        if (compareInfo.lastBuildChangedTime != buildChangedTime) {
            compareInfo.transaction {
                compareInfo.lastBuildChangedTime = buildChangedTime
                compareInfo.changeStatus = IDependencyChangeManager.ChangeStatus.CHANGED_NOT_SYNCED
            }
            logger.debug("build changed time changed: ${compareInfo.lastBuildChangedTime.timeStampToTime()} " +
                    "-> ${buildChangedTime.timeStampToTime()}, changeStatus: $changeStatus")
        }
    }

    @Synchronized
    override fun onStartSyncing(isFromIde: Boolean) {
        logger.debug("on sync start, isSyncing: $isSyncing, isFromIde: $isFromIde, isCurrentSyncFromGradle: $isCurrentSyncFromGradle")
        if (isSyncing) {
            if (isCurrentSyncFromGradle && isFromIde) {
                logger.debug("on sync start, current sync is gradle, ignore sync start from ide")
                return
            }
        }
        isCurrentSyncFromGradle = !isFromIde
        nextStartSyncingTime = System.currentTimeMillis()
    }

    @Synchronized
    override fun onEndSyncing(isFromIde: Boolean, isSuccess: Boolean, newContext: ICompileContext) {
        if (!hasInit) return
        logger.debug("on sync finished, isFromIde: $isFromIde, isSuccess $isSuccess, isCurrentSyncFromGradle: $isCurrentSyncFromGradle")
        if (isCurrentSyncFromGradle && isFromIde) {
            logger.debug("on sync finished, current sync is gradle(maybe finished), just ignore sync finish from ide")
            return
        }

        if (!isSuccess) {
            nextStartSyncingTime = 0L
            return
        }

        tempModule = newContext.tempModule
        if (nextStartSyncingTime == 0L) {
            logger.debug("on sync finished, but nextStartSyncingTime is 0, maybe time order is wrong, " +
                    "use current time as nextStartSyncingTime")
            nextStartSyncingTime = System.currentTimeMillis()
        }

        compareInfo.transaction {
            compareInfo.startSyncingTime = nextStartSyncingTime
            compareInfo.endSyncingTime = System.currentTimeMillis()
        }
        nextStartSyncingTime = 0L

        currentBuildDependencies = JuggProjectInfo(newContext.modules)
        updateDiffDependency(isEndSyncing = true)

        logger.debug("on sync finished, changeStatus: $changeStatus, diffResult: $diffResult")
    }

    private fun updateDiffDependency(isOnInit: Boolean = false, isEndSyncing: Boolean = false, isEndBuilding: Boolean = false) {
        try {
            if (isNeedUpdateLastBuildDependency(isOnInit, isEndSyncing, isEndBuilding)) {
                compareInfo.changeStatus = IDependencyChangeManager.ChangeStatus.NO_CHANGE
                logger.debug("update full build dependency, changeStatus: $changeStatus")
                diffDependency()
            } else if (isOnInit || isEndSyncing) {
                diffDependency()
            }
        } catch (e: Exception) {
            logger.debug("update diff dependency error: $e", e)
        }
    }

    private fun isNeedUpdateLastBuildDependency(isOnInit: Boolean, isEndSyncing: Boolean, isEndBuilding: Boolean): Boolean = compareInfo.run {
        val hasBuildTime = startBuildingTime > 0 && endBuildingTime > 0
        val hasBuildChangedTime = lastBuildChangedTime > 0
        val hasSyncTime = startSyncingTime > 0 && endSyncingTime > 0
        val isSyncLaterThanBuildChanged = hasSyncTime && (startSyncingTime > lastBuildChangedTime)
        val isBuildLaterThanSync = hasSyncTime && hasBuildTime && (endBuildingTime > endSyncingTime)
        val isSyncLaterThenBuild = hasSyncTime && hasBuildTime && (endSyncingTime > endBuildingTime)
        val isBuildLaterThanBuildChanged = hasBuildTime && (endBuildingTime > lastBuildChangedTime)

        logger.debug("""condition: 
            |isOnInit=$isOnInit, isEndSyncing=$isEndSyncing, isEndBuilding=$isEndBuilding
            |isSyncLaterThanBuildChanged=$isSyncLaterThanBuildChanged, isBuildLaterThanSync=$isBuildLaterThanSync, isSyncLaterThenBuild=$isSyncLaterThenBuild
            |hasBuildTime=$hasBuildTime, hasBuildChangedTime=$hasBuildChangedTime, hasSyncTime=$hasSyncTime
        """.trimMargin())
        logger.debug("""state: 
            |startSyncingTime=${startSyncingTime.timeStampToTime()}, endSyncingTime=${endSyncingTime.timeStampToTime()}
            |startBuildingTime=${startBuildingTime.timeStampToTime()}, endBuildingTime=${endBuildingTime.timeStampToTime()}
            |lastBuildChangedTime=${lastBuildChangedTime.timeStampToTime()}
            |isBuilding=$isBuilding, isSyncing=$isSyncing, isLastSyncUpdate=$isLastSyncUpdate
            |changeStatus=$changeStatus
        """.trimMargin())

        if (hasBuildChangedTime && !isSyncLaterThanBuildChanged) {
            logger.debug("not sync yet after build changed, skip update full build dependency")
            return false
        }
        if (hasBuildChangedTime && !isBuildLaterThanBuildChanged) {
            logger.debug("not build yet after build changed, skip update full build dependency")
            return false
        }

        // situation 1: after incremental compile
        if (isEndBuilding) {
            if (changeStatus == IDependencyChangeManager.ChangeStatus.INCREMENTAL_COMPILE) {
                logger.debug("isNeedUpdateLastBuildDependency true, hit situation 1: after incremental compile")
                lastBuildDependencies = currentBuildDependencies
                return true
            }
        }

        // situation 2: build changed -> sync finished -> build finished
        if (isEndBuilding) {
            if (isBuildLaterThanSync) {
                logger.debug("isNeedUpdateLastBuildDependency true, hit situation 2: build changed -> sync finished -> build finished")
                fullBuildDependencies = currentBuildDependencies
                return true
            }
        }

        // situation 3: build changed -> build finished -> first time sync finished
        if (isEndSyncing) {
            if (isSyncLaterThenBuild) {
                if (!isLastSyncUpdate) {
                    logger.debug("isNeedUpdateLastBuildDependency true, hit situation 3: build changed -> build finished -> first time sync finished")
                    isLastSyncUpdate = true
                    fullBuildDependencies = currentBuildDependencies
                    return true
                }
            }
        }

        // situation 4: first init, no full dependency
        if (isOnInit) {
            @Suppress("KotlinConstantConditions")
            if (!hasBuildTime && !hasSyncTime && !hasBuildChangedTime) {
                logger.debug("isNeedUpdateLastBuildDependency true, hit situation 4: first init, no full dependency")
                fullBuildDependencies = currentBuildDependencies
                return true
            }
        }

        logger.debug("isNeedUpdateLastBuildDependency situation none")
        return false
    }

    private fun diffDependency() {
        val fullBuildDependencies = fullBuildDependencies ?: run {
            logger.debug("fullBuildDependencies is null, exit diffDependency")
            return
        }
        val lastBuildDependencies = lastBuildDependencies ?: run {
            logger.debug("lastBuildDependencies is null, exit diffDependency")
            return
        }
        val currentBuildDependencies = currentBuildDependencies ?: run {
            logger.debug("currentBuildDependencies is null, exit diffDependency")
            return
        }

        diffResult = DependencyDiffResult.create(currentBuildDependencies, lastBuildDependencies)
        diffResultWithFull = DependencyDiffResult.create(currentBuildDependencies, fullBuildDependencies)
        logger.debug("diffDependency result $diffResult")
        logger.debug("diffDependency result with full $diffResultWithFull")
    }

    @Synchronized
    override fun onStartBuilding() {
        logger.debug("on start building")
        nextStartBuildingTime = System.currentTimeMillis()
    }

    @Synchronized
    override fun onEndBuilding(isSuccess: Boolean, isCancelled: Boolean) {
        if (!hasInit) return
        logger.debug("on end building, isSuccess: $isSuccess, isCancelled: $isCancelled")
        isAlreadyBuildAfterConfirm = true
        if (!isSuccess) {
            nextStartBuildingTime = 0L
            return
        }

        if (nextStartBuildingTime == 0L) {
            logger.debug("on end building, but nextStartBuildingTime is 0, maybe time order is wrong, " +
                    "use current time as nextStartBuildingTime")
            nextStartBuildingTime = System.currentTimeMillis()
        }
        compareInfo.transaction {
            compareInfo.isLastSyncUpdate = false
            compareInfo.startBuildingTime = nextStartBuildingTime
            compareInfo.endBuildingTime = System.currentTimeMillis()
        }
        nextStartBuildingTime = 0L
        updateDiffDependency(isEndBuilding = true)
    }

    @Synchronized
    override fun onConfirmIncrementalCompile(isConfirmed: Boolean) {
        if (!hasInit) return
        if (isConfirmed) {
            compareInfo.changeStatus = IDependencyChangeManager.ChangeStatus.INCREMENTAL_COMPILE
            isAlreadyBuildAfterConfirm = false
        } else {
            compareInfo.changeStatus = IDependencyChangeManager.ChangeStatus.REBUILD
        }
        logger.debug("on mark as incremental compile, changeStatus: $changeStatus")
    }

    private fun Long.timeStampToTime(): String {
        return SimpleDateFormat("MM-dd HH:mm:ss.SSS").format(Date(this))
    }
}
