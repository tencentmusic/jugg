package com.sickworm.intellij.jugg.project

import com.google.gson.Gson
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.ide.CommonConfirmDialog
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Used to manage the change of dependencies for library incremental compilation & deployment.
 */
interface IDependencyChangeManager: IDependencyChangeManagerEventCallback {

    val changeStatus: ChangeStatus

    fun init(cacheDirectory: File, compileContext: ICompileContext)

    fun tryShowChangConfirmDialog()

    fun getChangedLibrarySources(): List<ChangedFile>

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
    private var fullBuildDependencies: JuggProjectInfo? = null
        get() {
            field = projectInfoSerializer.load()
            return field
        }
        set(value) {
            field = value
            projectInfoSerializer.save(value!!)
        }
    private var changedLibraries: List<LibraryDependency> = emptyList()

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

        var compareInfoCacheFile: File? = null
        private fun writeToFile() {
            compareInfoCacheFile?.parentFile?.mkdirs()
            compareInfoCacheFile?.writeText(Gson().toJson(this))
        }
    }

    private var nextStartSyncingTime = 0L
    private var nextStartBuildingTime = 0L

    private val isBuilding get() = nextStartSyncingTime != 0L
    private val isSyncing get() = nextStartBuildingTime != 0L

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

        diffDependency()

        val compareInfoCacheFile = File(cacheDirectory, "compare_info.json")
        if (compareInfoCacheFile.exists()) {
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
            val isBuildChangedAfterBuild = compareInfo.lastBuildChangedTime > compareInfo.startBuildingTime

            val libraryNames = changedLibraries.map { it.name }.toSet()

            if (changedLibraries.isNotEmpty()) {
                logger.debug("show change confirm dialog, changedLibraries: $changedLibraries")
                val isConfirmed = CommonConfirmDialog.showAndGetResult(
                    title = "Jugg: Hey! Found Some Libraries Changed",
                    content = """<html>
                        |<p>Do you want to <b>incremental compile</b> these changed libraries?
                        |<ul>
                        |${libraryNames.joinToString("\n") { "<li>${it}</li>" }}
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
                if (fullBuildDependencies == null) {
                    logger.debug("show change confirm dialog, fullBuildDependencies is null")
                    CommonConfirmDialog.showAndGetResult(
                        title = "Jugg: Dependency Incremental Compile Not Available",
                        content = """<html>
                        |<p>Please fallback to gradle once to <b>enable dependency incremental compile.</b><br>
                        |This happens when Jugg upgraded or cache deleted.</p>
                        |</p>
                        |</html>
                        |""".trimMargin(),
                        okButtonText = "OK, I got it!",
                        isShowCancelButton = false,
                    )
                } else {
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
    override fun getChangedLibrarySources(): List<ChangedFile> {
        logger.debug("get changed libraries: $changedLibraries")

        val changedFiles = changedLibraries.mapNotNull {
            if (it.isAndroidManifest) {
                // TODO check AndroidManifest.xml changes
                null
            } else if (it.isRes) {
                ChangedFile(
                    type = CompileFile.Type.Resource,
                    file = it.file,
                    baseDir = it.file,
                    module = tempModule,
                ).withDependencyName(it.name)
            } else {
                ChangedFile(
                    type = CompileFile.Type.Class,
                    file = it.file,
                    baseDir = it.file.parentFile!!,
                    module = tempModule,
                ).withDependencyName(it.name)
            }
        }

        logger.debug("changed files: $changedFiles")
        return changedFiles
    }


    @Synchronized
    override fun onUpdateChangedBuildFiles(files: List<File>) {
        if (!hasInit) return
        logger.debug("on build file changed: $files")
        val buildChangedTime = files.maxOf { it.lastModified() }
        if (compareInfo.lastBuildChangedTime != buildChangedTime) {
            logger.debug("build changed time changed: ${compareInfo.lastBuildChangedTime.timeStampToTime()} " +
                    "-> ${buildChangedTime.timeStampToTime()}")
            compareInfo.lastBuildChangedTime = buildChangedTime
            compareInfo.changeStatus = IDependencyChangeManager.ChangeStatus.CHANGED_NOT_SYNCED
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

        compareInfo.changeStatus = if (changedLibraries.isEmpty()) {
            // mark as no change if diffDependency() found no library changed
            IDependencyChangeManager.ChangeStatus.NO_CHANGE
        } else {
            IDependencyChangeManager.ChangeStatus.REBUILD
        }
        logger.debug("on sync finished, changeStatus: $changeStatus, changedLibraries: $changedLibraries")
    }

    private fun updateFullBuildDependency(isEndSyncing: Boolean = false, isEndBuilding: Boolean = false) {
        if (isFullBuildDependency(isEndSyncing, isEndBuilding)) {
            logger.debug("update full build dependency")
            fullBuildDependencies = currentBuildDependencies
        }
    }

    private fun isFullBuildDependency(isEndSyncing: Boolean, isEndBuilding: Boolean): Boolean = compareInfo.run {
        val hasBuildTime = startBuildingTime > 0 && endBuildingTime > 0
        val hasBuildChanged = lastBuildChangedTime > 0
        val hasSyncTime = startSyncingTime > 0 && endSyncingTime > 0
        val isSyncLaterThanBuildChanged = hasSyncTime && (startSyncingTime > lastBuildChangedTime)
        val isBuildLaterThanSync = hasSyncTime && hasBuildTime && (endBuildingTime > endSyncingTime)
        val isSyncLaterThenBuild = hasSyncTime && hasBuildTime && (endSyncingTime > endBuildingTime)
        val isBuildLaterThanBuildChanged = hasBuildTime && (endBuildingTime > lastBuildChangedTime)

        logger.debug("""condition: 
            |isEndSyncing=$isEndSyncing, isEndBuilding=$isEndBuilding
            |isSyncLaterThanBuildChanged=$isSyncLaterThanBuildChanged, isBuildLaterThanSync=$isBuildLaterThanSync, isSyncLaterThenBuild=$isSyncLaterThenBuild
            |hasBuildTime=$hasBuildTime, hasBuildChanged=$hasBuildChanged, hasSyncTime=$hasSyncTime
        """.trimMargin())
        logger.debug("""state: 
            |startSyncingTime=${startSyncingTime.timeStampToTime()}, endSyncingTime=${endSyncingTime.timeStampToTime()}
            |startBuildingTime=${startBuildingTime.timeStampToTime()}, endBuildingTime=${endBuildingTime.timeStampToTime()}
            |lastBuildChangedTime=${lastBuildChangedTime.timeStampToTime()}
            |isBuilding=$isBuilding, isSyncing=$isSyncing
        """.trimMargin())

        if (hasBuildChanged && !isSyncLaterThanBuildChanged) {
            logger.debug("not sync yet after build changed, skip update full build dependency")
            return false
        }
        if (hasBuildChanged && !isBuildLaterThanBuildChanged) {
            logger.debug("not build yet after build changed, skip update full build dependency")
            return false
        }

        // situation 1: build changed -> sync finished -> build finished
        if (isEndBuilding) {
            if (isBuildLaterThanSync) {
                logger.debug("isFullBuildDependency true, hit situation 1")
                return true
            }
        }

        // situation 2: build changed -> build finished -> sync finished
        if (isEndSyncing) {
            if (isSyncLaterThenBuild) {
                logger.debug("isFullBuildDependency true, hit situation 2")
                return true
            }
        }

        // situation 3: sync finished (no build file changes)
        if (isEndSyncing) {
            @Suppress("KotlinConstantConditions")
            if (!hasBuildTime && !hasBuildChanged) {
                logger.debug("isFullBuildDependency true, hit situation 3")
                return true
            }
        }

        logger.debug("isFullBuildDependency situation none")
        return false
    }

    private fun diffDependency() {
        val fullBuildDependencies = fullBuildDependencies ?: run {
            logger.debug("fullBuildDependencies is null, exit diffDependency")
            return
        }
        val currentBuildDependencies = currentBuildDependencies ?: run {
            logger.debug("currentBuildDependencies is null, exit diffDependency")
            return
        }

        val currentDependMap = run {
            val result = mutableMapOf<String, LibraryDependency>()
            currentBuildDependencies.modules.values.forEach { moduleInfo ->
                moduleInfo.libraryDependencies.forEach {
                    result[it.file.absolutePath] = it
                }
            }
            return@run result
        }
        val fullCompileDependMap = run {
            val result = mutableMapOf<String, LibraryDependency>()
            fullBuildDependencies.modules.values.forEach { moduleInfo ->
                moduleInfo.libraryDependencies.forEach {
                    result[it.file.absolutePath] = it
                }
            }
            return@run result
        }

        val changedLibraries = mutableListOf<LibraryDependency>()
        currentDependMap.forEach { (path, libraryDependency) ->
            val fullCompileDepend = fullCompileDependMap[path]
            if (fullCompileDepend == null) {
                logger.debug("found new library: $libraryDependency")
                changedLibraries.add(libraryDependency)
            } else if (libraryDependency.crc32 != fullCompileDepend.crc32) {
                logger.debug("found changed library: $libraryDependency")
                changedLibraries.add(libraryDependency)
            }
        }
        this.changedLibraries = changedLibraries
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
        logger.debug("on mark as incremental compile, changeStatus: $changeStatus")
        if (isConfirmed) {
            compareInfo.changeStatus = IDependencyChangeManager.ChangeStatus.INCREMENTAL_COMPILE
        } else {
            compareInfo.changeStatus = IDependencyChangeManager.ChangeStatus.REBUILD
        }
    }

    private fun Long.timeStampToTime(): String {
        return SimpleDateFormat("HH:mm:ss.SSS").format(Date(this))
    }
}

private class NoDependencyConfirmDialog(project: Project): DialogWrapper(project) {

    init {
        title = "Jugg: Oops, No Library Changes Found"
        isResizable = false
        init()
    }

    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(GridBagLayout())

        val constraints = GridBagConstraints()
        constraints.gridx = 0
        constraints.gridy = 0
        constraints.fill = GridBagConstraints.HORIZONTAL

        constraints.insets = JBUI.insetsBottom(12)
        constraints.gridwidth = 1
        val text = JBLabel(
            """<html>
            |<p>Do you want to <b>ignore</b> build files changed?<br>
            |<b>Caution: This may cause unexpected build result!</b></p>
            |</html>
            |""".trimMargin()
        )
        mainPanel.add(text, constraints)

        return mainPanel
    }

    override fun createActions(): Array<Action> {
        setCancelButtonText("No, Fallback to Gradle Build")
        setOKButtonText("Yes, Ignore Build Changes")
        return super.createActions()
    }

}


