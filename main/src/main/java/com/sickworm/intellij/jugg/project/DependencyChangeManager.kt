package com.sickworm.intellij.jugg.project

import com.google.gson.Gson
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.ide.CommonConfirmDialog
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Used to manage the change of dependencies for library incremental compilation & deployment.
 */
interface IDependencyChangeManager: IDependencyChangeManagerEventCallback {

    val changeStatus: ChangeStatus

    fun init(cacheDirectory: File, compileContext: ICompileContext)

    fun tryShowChangConfirmDialog()

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

    fun onStartSyncing()

    fun onEndSyncing(isSuccess: Boolean, newContext: ICompileContext)

    fun onStartBuilding()

    fun onEndBuilding(isSuccess: Boolean)

    fun onConfirmIncrementalCompile(isConfirmed: Boolean)
}

fun IDependencyChangeManager.Companion.create(logger: Logger): IDependencyChangeManager {
    return DependencyChangeManager(logger)
}

private class DependencyChangeManager(private val logger: Logger): IDependencyChangeManager {

    override val changeStatus get() = compareInfo.changeStatus

    private var hasInit: Boolean = false

    private lateinit var tempModule: ModuleInfo

    private var currentBuildDependencies: JuggProjectInfo? = null

    private lateinit var projectInfoSerializer: ProjectInfoSerializer
    private var lastBuildDependencies: JuggProjectInfo? = null
        get() {
            field = projectInfoSerializer.load()
            return field
        }
        set(value) {
            field = value
            projectInfoSerializer.save(value!!)
        }
    private var diffResult = DependencyDiffResult.createEmpty()

    private var compareInfo = object {
        val VERSION = 1

        var version = 1
        var changeStatus: IDependencyChangeManager.ChangeStatus = IDependencyChangeManager.ChangeStatus.NO_CHANGE
            set(value) { field = value ; writeToFile() }
        var startSyncingTime = 0L
            set(value) { field = value ; writeToFile() }
        var endSyncingTime = 0L
            set(value) { field = value ; writeToFile() }
        var startBuildingTime = 0L
            set(value) { field = value ; writeToFile() }
        var endBuildingTime = 0L
            set(value) { field = value ; writeToFile() }
        var lastBuildChangedTime = 0L
            set(value) { field = value ; writeToFile() }

        var isLastSyncUpdate = false
            set(value) { field = value ; writeToFile() }

        var compareInfoCacheFile: File? = null
        private fun writeToFile() {
            compareInfoCacheFile?.parentFile?.mkdirs()
            compareInfoCacheFile?.writeText(Gson().toJson(this))
        }
    }

    private var nextStartSyncingTime = 0L
    private var nextStartBuildingTime = 0L

    private val isBuilding get() = nextStartBuildingTime != 0L
    private val isSyncing get() = nextStartSyncingTime != 0L

    @Synchronized
    override fun init(cacheDirectory: File, compileContext: ICompileContext) {
        logger.debug("init dependency change manager hasInit: $hasInit")
        if (hasInit) {
            return
        }

        this.tempModule = compileContext.tempModule

        cacheDirectory.mkdirs()
        currentBuildDependencies = JuggProjectInfo(compileContext.modules)
        val fullBuildCacheFile = File(cacheDirectory, "full_build_project_infos.dat")
        projectInfoSerializer = ProjectInfoSerializer(fullBuildCacheFile, logger)

        val compareInfoCacheFile = File(cacheDirectory, "compare_info.json")
        if (compareInfoCacheFile.exists() && lastBuildDependencies != null) {
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

        updateFullBuildDependency(isOnInit = true)
        diffDependency()

        hasInit = true
    }

    @Synchronized
    override fun tryShowChangConfirmDialog() {
        if (!hasInit) return
        logger.debug("show change confirm dialog")
        if (isBuilding || isSyncing) {
            logger.debug("skip show change confirm dialog, is building or syncing")
            return
        }

        ApplicationManager.getApplication().invokeLater {
            val isBuildChangedAfterBuild = compareInfo.lastBuildChangedTime > 0 &&
                    compareInfo.startBuildingTime > 0 &&
                    compareInfo.lastBuildChangedTime > compareInfo.startBuildingTime

            if (diffResult.newLibraryDependencies.isNotEmpty() || diffResult.removedLibraryDependencies.isNotEmpty()) {
                logger.debug("show change confirm dialog, newLibraries: ${diffResult.newLibraryDependencies}, " +
                        "removedLibraries: ${diffResult.removedLibraryDependencies}")
                val isConfirmed = CommonConfirmDialog.showAndGetResult(
                    title = "Jugg: Hey! Found Some Libraries Changed",
                    content = """<html>
                        |<p>Do you want to <b>incremental compile</b> these changed libraries?
                        |<ul>
                        |${diffResult.toHtmlChangeList().joinToString("\n") { "<li>${it}</li>" }}
                        |</ul>
                        |</p>
                        |<p><b>Caution: This may cause unexpected build result, Please check changes carefully<br>
                        |before you make a decision.</b></p>
                        |</html>
                        |""".trimMargin(),
                    okButtonText = "Yes, Incremental Compile!",
                    cancelButtonText = "No, Fallback to Gradle Later",
                )
                onConfirmIncrementalCompile(isConfirmed)
            } else if (isBuildChangedAfterBuild) {
                if (lastBuildDependencies == null) {
                    logger.debug("show change confirm dialog, lastBuildDependencies is null")
                    CommonConfirmDialog.showAndGetResult(
                        title = "Jugg: Dependency Incremental Compile Not Available",
                        content = """<html>
                        |<p>Please fallback to gradle once to <b>enable dependency incremental compile.</b><br>
                        |This should not happened. Please report issues.</p>
                        |</p>
                        |</html>
                        |""".trimMargin(),
                        okButtonText = "OK, I got it!",
                        isShowCancelButton = false,
                    )
                } else if (compareInfo.changeStatus == IDependencyChangeManager.ChangeStatus.CHANGED_NOT_SYNCED) {
                    logger.debug("show no change confirm dialog")
                    val isConfirmed = CommonConfirmDialog.showAndGetResult(
                        title = "Jugg: Oops, No Library Changes Found",
                        content = """<html>
                        |<p>Do you want to <b>ignore</b> build files changed?<br>
                        |<b>Caution: This may cause unexpected build result!</b></p>
                        |</html>
                        |""".trimMargin(),
                        okButtonText = "Yes, Ignore Build File Changes!",
                        cancelButtonText = "No, Fallback to Gradle Later",
                    )
                    onConfirmIncrementalCompile(isConfirmed)
                }
            }
        }
    }

    @Synchronized
    override fun getNewLibraryFiles(): List<ChangedFile> {
        logger.debug("get new libraries: ${diffResult.newLibraryDependencies}")

        val relativeOldManifest: Map<String, File> = diffResult.updatedLibraries.mapNotNull {
            val newManifest = it.dependency?.libraries?.find(LibraryDependency::isAndroidManifest)
            val oldManifest = it.oldDependency?.libraries?.find(LibraryDependency::isAndroidManifest)
            if (newManifest != null && oldManifest != null) {
                newManifest.file.absolutePath  to oldManifest.file
            } else {
                null
            }
        }.toMap()

        val changedFiles = diffResult.newLibraryDependencies.mapNotNull {
            if (it.isAndroidManifest) {
                ChangedFile(
                    type = CompileFile.Type.AndroidManifest,
                    file = it.file,
                    baseDir = it.file,
                    module = tempModule,
                ).withDependencyName(it.nameWithoutPrefix)
                    .withOldManifest(relativeOldManifest[it.file.absolutePath])
                null
            } else if (it.isRes) {
                ChangedFile(
                    type = CompileFile.Type.Resource,
                    file = it.file,
                    baseDir = it.file,
                    module = tempModule,
                ).withDependencyName(it.nameWithoutPrefix)
            } else {
                ChangedFile(
                    type = CompileFile.Type.Class,
                    file = it.file,
                    baseDir = it.file.parentFile!!,
                    module = tempModule,
                ).withDependencyName(it.nameWithoutPrefix)
            }
        }.toMutableList()

        // Guess assets dir. Jugg may not support aar that only contains assets. (need to be confirmed)
        val guessAssetsDirs: List<File> = diffResult.newLibraryDependencies.mapNotNull {
            val parentFile = it.file.parentFile ?: return@mapNotNull null
            val assetDir = File(parentFile, "assets")
            if (assetDir.exists() && assetDir.isDirectory && assetDir.listFiles()?.isNotEmpty() == true) {
                return@mapNotNull assetDir
            }
            return@mapNotNull null
        }
        guessAssetsDirs.toSet().forEach {
            changedFiles.add(
                ChangedFile(
                    type = CompileFile.Type.Asset,
                    file = it,
                    baseDir = it,
                    module = tempModule,
                )
            )
        }

        logger.debug("changed files: $changedFiles")
        return changedFiles
    }

    override fun getRemovedLibraryFiles(): List<ChangedFile> {
        logger.debug("get removed libraries: ${diffResult.removedLibraryDependencies}")

        val changedFiles = diffResult.removedLibraryDependencies.mapNotNull {
            if (it.isAndroidManifest) {
                // no need
                null
            } else if (it.isRes) {
                // no need
                null
            } else {
                ChangedFile(
                    type = CompileFile.Type.Class,
                    file = it.file,
                    baseDir = it.file.parentFile!!,
                    module = tempModule,
                ).withDependencyName(it.nameWithoutPrefix)
            }
        }

        logger.debug("removed changed files: $changedFiles")
        return changedFiles
    }

    @Synchronized
    override fun onUpdateChangedBuildFiles(files: List<File>) {
        if (!hasInit) return
        logger.debug("on build file changed: $files")
        val buildChangedTime = files.maxOf { it.lastModified() }
        if (compareInfo.lastBuildChangedTime != buildChangedTime) {
            compareInfo.lastBuildChangedTime = buildChangedTime
            compareInfo.changeStatus = IDependencyChangeManager.ChangeStatus.CHANGED_NOT_SYNCED
            logger.debug("build changed time changed: ${compareInfo.lastBuildChangedTime.timeStampToTime()} " +
                    "-> ${buildChangedTime.timeStampToTime()}, changeStatus: $changeStatus")
        }
    }

    override fun onStartSyncing() {
        if (!hasInit) return
        logger.debug("on sync start")
        nextStartSyncingTime = System.currentTimeMillis()
    }

    @Synchronized
    override fun onEndSyncing(isSuccess: Boolean, newContext: ICompileContext) {
        if (!hasInit) return
        logger.debug("on sync finished, isSuccess $isSuccess")
        if (!isSuccess) {
            nextStartSyncingTime = 0L
            return
        }

        tempModule = newContext.tempModule
        compareInfo.startSyncingTime = nextStartSyncingTime
        compareInfo.endSyncingTime = System.currentTimeMillis()
        nextStartSyncingTime = 0L

        currentBuildDependencies = JuggProjectInfo(newContext.modules)
        updateFullBuildDependency(isEndSyncing = true)
        diffDependency()

        logger.debug("on sync finished, changeStatus: $changeStatus, diffResult: $diffResult")
    }

    private fun updateFullBuildDependency(isOnInit: Boolean = false, isEndSyncing: Boolean = false, isEndBuilding: Boolean = false) {
        if (isFullBuildDependency(isOnInit, isEndSyncing, isEndBuilding)) {
            lastBuildDependencies = currentBuildDependencies
            compareInfo.changeStatus = IDependencyChangeManager.ChangeStatus.NO_CHANGE
            logger.debug("update full build dependency, changeStatus: $changeStatus")
        }
    }

    private fun isFullBuildDependency(isOnInit: Boolean, isEndSyncing: Boolean, isEndBuilding: Boolean): Boolean = compareInfo.run {
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

        // situation 1: build changed -> sync finished -> build finished
        if (isEndBuilding) {
            if (isBuildLaterThanSync) {
                logger.debug("isFullBuildDependency true, hit situation 1: build changed -> sync finished -> build finished")
                return true
            }
        }

        // situation 2: build changed -> build finished -> first time sync finished
        if (isEndSyncing) {
            if (isSyncLaterThenBuild) {
                if (!isLastSyncUpdate) {
                    logger.debug("isFullBuildDependency true, hit situation 2: build changed -> build finished -> first time sync finished")
                    isLastSyncUpdate = true
                    return true
                }
            }
        }

        // situation 3: first init, no full dependency
        if (isOnInit) {
            @Suppress("KotlinConstantConditions")
            if (!hasBuildTime && !hasSyncTime && !hasBuildChangedTime) {
                logger.debug("isFullBuildDependency true, hit situation 3: first init, no full dependency")
                return true
            }
        }

        logger.debug("isFullBuildDependency situation none")
        return false
    }

    private fun diffDependency() {
        val lastBuildDependencies = lastBuildDependencies ?: run {
            logger.debug("lastBuildDependencies is null, exit diffDependency")
            return
        }
        val currentBuildDependencies = currentBuildDependencies ?: run {
            logger.debug("currentBuildDependencies is null, exit diffDependency")
            return
        }

        diffResult = DependencyDiffResult.create(currentBuildDependencies, lastBuildDependencies)
        logger.debug("diffDependency result $diffResult")
    }

    @Synchronized
    override fun onStartBuilding() {
        if (!hasInit) return
        logger.debug("on start rebuilding")
        nextStartBuildingTime = System.currentTimeMillis()
    }

    @Synchronized
    override fun onEndBuilding(isSuccess: Boolean) {
        if (!hasInit) return
        logger.debug("on end building, isSuccess: $isSuccess")
        if (!isSuccess) {
            nextStartBuildingTime = 0L
            return
        }

        compareInfo.startBuildingTime = nextStartBuildingTime
        compareInfo.endBuildingTime = System.currentTimeMillis()
        nextStartBuildingTime = 0L
        updateFullBuildDependency(isEndBuilding = true)
    }

    @Synchronized
    override fun onConfirmIncrementalCompile(isConfirmed: Boolean) {
        if (!hasInit) return
        if (isConfirmed) {
            compareInfo.changeStatus = IDependencyChangeManager.ChangeStatus.INCREMENTAL_COMPILE
        } else {
            compareInfo.changeStatus = IDependencyChangeManager.ChangeStatus.REBUILD
        }
        logger.debug("on mark as incremental compile, changeStatus: $changeStatus")
    }

    private fun Long.timeStampToTime(): String {
        return SimpleDateFormat("HH:mm:ss.SSS").format(Date(this))
    }
}
