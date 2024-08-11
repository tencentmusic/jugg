package com.sickworm.intellij.jugg.gradle.compile

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.project.data.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.compiler.isWindows
import com.sickworm.intellij.jugg.ide.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.platform.PlatformApi
import com.sickworm.intellij.jugg.project.JuggInternalException
import com.sickworm.intellij.jugg.project.JuggPathManager
import com.sickworm.intellij.jugg.project.ProjectInfoSerializer
import com.sickworm.intellij.jugg.project.dependency.DependencyDiffResult
import com.sickworm.intellij.jugg.project.dependency.DependencyDiffResultSet
import com.sickworm.intellij.jugg.project.dependency.convertToAbsolutePath
import java.io.File


class LocalGradleCompileClient(
    private val project: Project,
    private val localClasspathStorageDir: File,
    private val logger: Logger = JuggLogger.getInstance(project, "LocalGradleCompileClient"),
) : IGradleCompileClient {

    private var juggGradleCompileOptions: JuggGradleCompileOptions? = null

    override var terminalOutputListener = IGradleCompileClient.TerminalOutputListener.DEFAULT

    private var gradleJdkPath: String? = null
    private var androidHomePath: String? = null

    private val cmdExecutor = CmdExecutor(logger, terminalOutputListener)

    override fun login(juggGradleCompileOptions: JuggGradleCompileOptions) {
        // no need to login
        this.juggGradleCompileOptions = juggGradleCompileOptions

        gradleJdkPath = PlatformApi.getGradleJdkPath(project, logger)

        androidHomePath = PlatformApi.getAndroidHomePath(logger)
    }

    override fun compileAndFetchResult(isOnlyFetchResult: Boolean): GradleCompileResult {
        isCanceled = false
        val juggGradleCompileOptions = juggGradleCompileOptions ?: throw JuggInternalException.notLoginYet()

        if (!isOnlyFetchResult) {
            val compileProjectCommand = CompileProjectCommand(
                juggGradleCompileOptions.compileCommand,
                project.basePath!!,
                juggGradleCompileOptions.initGradleFileRelativePath,
            )
            val compileProjectResult = invoke(compileProjectCommand)
            if (compileProjectResult != 0) {
                printToStreamErrorIfCanceled("Compile project failed, please check the error message.")
                return GradleCompileResult.failed(isCanceled, "Compile project failed $compileProjectResult")
            }
        }

        val outputApkNameOrPath = juggGradleCompileOptions.outputApkName
        val findOutputCommand = FindOutputCommand(project.basePath!!, outputApkNameOrPath)

        var apkFile: File? = null
        if (findOutputCommand.findPath.isEmpty()) {
            // old logic, find apk by name

            // try sub dir first
            // currently only supports module located at root dir
            val subDirName = juggGradleCompileOptions.compileCommand.split(":").getOrNull(1) ?: "app"
            // run gradle command will put apk in subDir1
            val subDir1 = File(project.basePath!!, "$subDirName/build/outputs")
            // run directly in android studio will only put apk in subDir2. this dir used for test mock event
            val subDir2 = File(project.basePath!!, "$subDirName/intermediates/apk")
            if (subDir1.exists()) {
                apkFile = subDir1.findFilesRecursively(juggGradleCompileOptions.outputApkName)
            } else if (subDir2.exists()) {
                apkFile = subDir2.findFilesRecursively(juggGradleCompileOptions.outputApkName)
            }

            if (apkFile == null) {
                // find in root dir
                val rootDir = File(project.basePath!!)
                apkFile = rootDir.findFilesRecursively(juggGradleCompileOptions.outputApkName)
            }
        } else {
            // new logic, find apk by path, faster
            val subDir = File(project.basePath!!, findOutputCommand.findPath)
            if (subDir.exists()) {
                apkFile = subDir.findFilesRecursively(findOutputCommand.findName)
            }
        }

        if (apkFile == null) {
            printToStreamError("Can't find apk \"${juggGradleCompileOptions.outputApkName}\" " +
                    "in ${project.basePath}, please make sure your run configuration is right.")
            return GradleCompileResult.failed(isCanceled, "Can't find apk")
        } else {
            logger.debug("Find apk: ${apkFile.absolutePath}")
        }
        return GradleCompileResult.success(apkFile)
    }

    override fun fetchClasspathResult(buildDirs: List<ModuleBuildPathInfo>): File {
        isCanceled = false

        val projectRootPath = File(project.basePath!!)
        if (isWindows) {
            logger.info("fetchClasspathResult not support windows yet")
            return projectRootPath
        }

        try {
            // calculate root dir of project
            var rootDir = projectRootPath
            buildDirs.forEach { moduleBuildPathInfo ->
                val moduleDir = moduleBuildPathInfo.moduleRootDir
                if (!moduleDir.isChild(rootDir)) {
                    while (true) {
                        val parentFile = rootDir.parentFile ?: run {
                            logger.warn("fetchClasspathResult failed, can't find parentFile of $moduleDir")
                            return@forEach
                        }
                        rootDir = parentFile
                        if (moduleDir.isChild(parentFile)) {
                            return@forEach
                        }
                    }
                }
            }
            val destPath = localClasspathStorageDir.also {
                it.mkdirs()
            }
            val command = SyncLocalClasspathCommand(
                sourcePath = rootDir,
                destPath = destPath,
                modules = buildDirs,
            )
            val result = invoke(command)
            if (result == 0) {
                val projectRelativePath = projectRootPath.toRelativeString(rootDir.parentFile)
                return File(destPath, projectRelativePath)
            } else {
                logger.warn("fetchClasspathResult failed")
            }
        } catch (e: Exception) {
            logger.warn("fetchClasspathResult failed", e)
        }

        logger.warn("use project base path instead")
        return projectRootPath
    }

    override fun fetchLibraryChanges(incDeployTimes: Int): DependencyDiffResultSet? {
        isCanceled = false
        val juggGradleCompileOptions = juggGradleCompileOptions ?: throw JuggInternalException.notLoginYet()

        // 1. clear directory (don't delete tmpGradleProjectInfo)
        val juggPathManager = JuggPathManager(File(juggGradleCompileOptions.projectRootPath))
        juggPathManager.remoteDiffLibraryDir.deleteRecursively()
        juggPathManager.remoteDiffResultFile.delete()

        // 1. run library diff
        val diffLibraryChangesCommand = DiffLibraryChangesCommand(
            juggGradleCompileOptions.projectRootPath,
            juggGradleCompileOptions.initGradleFileRelativePath,
            incDeployTimes,
        )
        val compileProjectResult = invoke(diffLibraryChangesCommand)
        if (compileProjectResult != 0) {
            printToStreamErrorIfCanceled("Diff library changes failed, please check the error message.")
            return null
        }
        return parseDiffSet(juggPathManager, logger)
    }

    @Volatile
    private var isCanceled = false

    override fun cancelAction(isByUser: Boolean) {
        if (isByUser) {
            printToStreamInfo("[Jugg] user cancel")
        }
        cmdExecutor.release()
        isCanceled = true
    }

    private fun invoke(command: ISshCommand): Int {
        printToStreamInfo("[Jugg] ${command::class.simpleName} exec start")

        val envArray: MutableList<String> = System.getenv().entries
            .filter {
                it.key != "JAVA_HOME" || it.key != "ANDROID_HOME"
            }
            .map {
                "${it.key}=${it.value}"
            }
            .toMutableList()
        if (gradleJdkPath != null) {
            envArray.add("JAVA_HOME=$gradleJdkPath")
        }
        if (androidHomePath != null) {
            envArray.add("ANDROID_HOME=$androidHomePath")
        }
        logger.debug("input env: $envArray")

        cmdExecutor.terminalOutputListener = terminalOutputListener
        val result = cmdExecutor.invoke(command, envArray)

        printToStreamInfo("[Jugg] ${command::class.simpleName} exec finished with result: $result")
        return result
    }

    private fun printToStreamInfo(line: String) {
        terminalOutputListener.onOutput(line, isNeedPrint = false)
        logger.info(line)
    }

    private fun printToStreamError(line: String, e: Exception? = null) {
        terminalOutputListener.onOutput(line, isNeedPrint = false)
        logger.warn(line, e)
    }

    private fun printToStreamErrorIfCanceled(line: String, e: Exception? = null) {
        if (isCanceled) {
            return
        }
        terminalOutputListener.onOutput(line, isNeedPrint = false)
        return printToStreamError(line, e)
    }

    override fun dispose() {
        cmdExecutor.release()
    }

    companion object {

        fun parseDiffSet(juggPathManager: JuggPathManager, logger: Logger): DependencyDiffResultSet? {
            val lastDiffResult = parseDiffFile(juggPathManager.remoteDiffResultFile, juggPathManager.remoteDiffLibraryDir, logger)
            val fullDiffResult = parseDiffFile(juggPathManager.remoteDiffResultWithFullFile, juggPathManager.remoteDiffLibraryDir, logger)
            if (lastDiffResult == null) {
                logger.warn("parse last diff file failed, please check the error message.")
                return null
            }
            if (fullDiffResult == null) {
                logger.warn("parse full diff file failed, please check the error message.")
                return null
            }

            logger.info("[Jugg] found changed libraries: ${lastDiffResult.changedLibraries.size}")
            logger.debug("found changed libraries since full build: ${fullDiffResult.changedLibraries.size}")
            return DependencyDiffResultSet(lastDiffResult, fullDiffResult)
        }

        private fun parseDiffFile(diffFile: File, baseDir: File, logger: Logger): DependencyDiffResult? {
            if (!diffFile.exists()) {
                logger.warn("Diff file not found, please check the error message.")
                return null
            }
            val dependencyDiffResult = try {
                val diffResult = ProjectInfoSerializer.gson.fromJson(diffFile.readText(), DependencyDiffResult::class.java)
                // replace diffResult path to local absolute path
                diffResult.convertToAbsolutePath(baseDir)
            } catch (e: Exception) {
                logger.warn("Parse diff result failed, please check the error message.")
                return null
            }
            return dependencyDiffResult
        }
    }
}
