package com.sickworm.intellij.jugg.project

import com.google.gson.Gson
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.compiler.LibraryDependency
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
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

    fun tryShowChangConfirmDialog(project: Project)

    fun getChangedLibraries(): List<File>

    enum class ChangeStatus {
        NO_CHANGE,
        CHANGED_NOT_SYNCED,
        CHANGED_AND_REBUILD,
        CHANGED_AND_INCREMENTAL_COMPILE,
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
    private var changedLibraries: List<File> = emptyList()

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

        val isBuilding get() = startBuildingTime > endBuildingTime
        val isSyncing get() = startSyncingTime > endSyncingTime

        var compareInfoCacheFile: File? = null
        private fun writeToFile() {
            compareInfoCacheFile?.parentFile?.mkdirs()
            compareInfoCacheFile?.writeText(Gson().toJson(this))
        }
    }


    @Synchronized
    override fun init(cacheDirectory: File, compileContext: ICompileContext) {
        logger.debug("init dependency change manager hasInit: $hasInit")
        if (hasInit) {
            return
        }

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
    override fun tryShowChangConfirmDialog(project: Project) {
        if (!hasInit) return
        logger.debug("show change confirm dialog")
        if (compareInfo.isBuilding || compareInfo.isSyncing) {
            logger.debug("skip show change confirm dialog, is building or syncing")
            return
        }

        ApplicationManager.getApplication().invokeLater {
            if (changedLibraries.isNotEmpty()) {
                val isConfirmed =DependencyConfirmDialog(project, changedLibraries).showAndGet()
                onConfirmIncrementalCompile(isConfirmed)
            } else {
                val isBuildChangedAfterBuild = compareInfo.lastBuildChangedTime > compareInfo.startBuildingTime
                if (isBuildChangedAfterBuild) {
                    NoDependencyConfirmDialog(project).showAndGet()
                }
            }
        }
    }

    @Synchronized
    override fun getChangedLibraries(): List<File> {
        logger.debug("get changed libraries: $changedLibraries")
        return changedLibraries
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
        compareInfo.startSyncingTime = System.currentTimeMillis()
    }

    @Synchronized
    override fun onEndSyncing(isSuccess: Boolean, newContext: ICompileContext) {
        if (!hasInit) return
        logger.debug("on sync finished, isSuccess $isSuccess")
        compareInfo.endSyncingTime = System.currentTimeMillis()
        if (!isSuccess) {
            return
        }

        currentBuildDependencies = JuggProjectInfo(newContext.modules)
        updateFullBuildDependency(isEndSyncing = true)
        diffDependency()

        compareInfo.changeStatus = if (changedLibraries.isEmpty()) {
            // mark as no change if diffDependency() found no library changed
            IDependencyChangeManager.ChangeStatus.NO_CHANGE
        } else {
            IDependencyChangeManager.ChangeStatus.CHANGED_AND_REBUILD
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
        val hasBuildTime = startBuildingTime > 0
        val hasBuildChanged = lastBuildChangedTime > 0
        val hasSyncTime = startSyncingTime > 0
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

        val changedLibraries = mutableListOf<File>()
        currentDependMap.forEach { (path, libraryDependency) ->
            val fullCompileDepend = fullCompileDependMap[path]
            if (fullCompileDepend == null) {
                logger.debug("found new library: $libraryDependency")
                changedLibraries.add(libraryDependency.file)
            } else if (libraryDependency.crc32 != fullCompileDepend.crc32) {
                logger.debug("found changed library: $libraryDependency")
                changedLibraries.add(libraryDependency.file)
            }
        }
        this.changedLibraries = changedLibraries
    }

    @Synchronized
    override fun onStartBuilding() {
        if (!hasInit) return
        logger.debug("on start rebuilding")
        compareInfo.startBuildingTime = System.currentTimeMillis()
    }

    @Synchronized
    override fun onEndBuilding(isSuccess: Boolean) {
        if (!hasInit) return
        logger.debug("on end building, isSuccess: $isSuccess")
        compareInfo.endBuildingTime = System.currentTimeMillis()
        updateFullBuildDependency(isEndBuilding = true)
    }

    @Synchronized
    override fun onConfirmIncrementalCompile(isConfirmed: Boolean) {
        if (!hasInit) return
        logger.debug("on mark as incremental compile, changeStatus: $changeStatus")
        if (isConfirmed && changeStatus == IDependencyChangeManager.ChangeStatus.CHANGED_AND_REBUILD) {
            compareInfo.changeStatus = IDependencyChangeManager.ChangeStatus.CHANGED_AND_INCREMENTAL_COMPILE
        }
    }

    private fun Long.timeStampToTime(): String {
        return SimpleDateFormat("HH:mm:ss.SSS").format(Date(this))
    }
}

private class DependencyConfirmDialog(project: Project, private val changedLibraries: List<File>): DialogWrapper(project) {

    init {
        title = "Hey! Jugg Found Some Libraries Changed"
        init()
    }

    override fun createCenterPanel(): JComponent {
        return createDialog()
    }

    override fun createActions(): Array<Action> {
        setCancelButtonText("No, Fallback to Gradle Build")
        setOKButtonText("Yes, Incremental Compile These Changes")
        return super.createActions()
    }

    private fun createDialog(): DialogPanel {
        val changedList = changedLibraries.map {
            val path = it.absolutePath
            if (path.contains("${File.separator}transformed${File.separator}")) {
                path.substringAfter("${File.separator}transformed${File.separator}")
                    .substringBefore(File.separator)
            } else if (path.contains("${File.separator}files-2.1${File.separator}")) {
                it.nameWithoutExtension
            } else {
                path
            }
        }.toSet()
        return panel {
            row {
                label("Jugg found some dependencies changed.")
            }
            row {
                label("Do you want to incremental compile the changed libraries?")
            }
            row {
                label("Caution: This may cause unexpected build result, if there are more changes about gradle build.").bold()
            }
            row {
                label("Please check the gradle change carefully before you make a decision.").bold()
            }
            row {
                label("Changed libraries:")
            }
            changedList.forEach {
                row {
                    label(it).bold()
                }
            }
        }
    }
}

private class NoDependencyConfirmDialog(project: Project): DialogWrapper(project) {

    init {
        title = "Jugg: Oops, No Library Changes Found"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(GridBagLayout())
        val content = """<html>
            |<p>Jugg found build file is changed, but no dependencies change is found.</p>
            |<p>Do you want to ignore build files changed?</p>
            |<p><b>Caution: This may cause unexpected build result!</b></p>
            |</html>""".trimMargin()

        val constraints = GridBagConstraints()
        constraints.gridx = 0
        constraints.gridy = 0
        constraints.fill = GridBagConstraints.HORIZONTAL

        constraints.insets = JBUI.insetsBottom(12)
        constraints.gridwidth = 1
        mainPanel.add(JBLabel(content), constraints)
        constraints.gridy++

        return mainPanel
    }

    override fun createActions(): Array<Action> {
        setCancelButtonText("No, Fallback to Gradle Build")
        setOKButtonText("Yes, Ignore This Build Changes")
        return super.createActions()
    }

}

