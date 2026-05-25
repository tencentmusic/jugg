package com.sickworm.intellij.jugg.gradle.compile

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.compiler.BuildTarget
import com.sickworm.intellij.jugg.project.data.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.logger.getInstance
import com.sickworm.intellij.jugg.platform.PlatformApi
import com.sickworm.intellij.jugg.project.JuggInternalException
import com.sickworm.intellij.jugg.project.JuggPathManager
import com.sickworm.intellij.jugg.project.ProjectInfoSerializer
import com.sickworm.intellij.jugg.project.dependency.DependencyDiffResult
import com.sickworm.intellij.jugg.project.dependency.DependencyDiffResultSet
import com.sickworm.intellij.jugg.project.dependency.convertToAbsolutePath
import java.io.File


/**
 * LocalGradleCompileClient runs configured Gradle commands on the local machine and collects APK/classpath/dependency-diff outputs.
 * Collaboration: Executes shell commands through [CmdExecutor], runs [CompileProjectCommand]/[FindOutputCommand], and serializes build-path/dependency snapshots via [ProjectInfoSerializer] and [DependencyDiffResultSet].
 * Data Contract: [login] must be called before compile operations; [compileAndFetchResult] returns failed results when Gradle command exits non-zero or expected APK outputs cannot be resolved.
 */
class LocalGradleCompileClient(
    private val projectDir: File,
    private val localClasspathStorageDir: File,
    private val envArray: List<String>?,
    loggerArg: Logger,
) : IGradleCompileClient {

    private val logger: Logger = loggerArg.getInstance("LocalGradleCompileClient")

    private var juggGradleCompileOptions: JuggGradleCompileOptions? = null

    override var terminalOutputListener = IGradleCompileClient.TerminalOutputListener.DEFAULT

    private val cmdExecutor = CmdExecutor(logger, terminalOutputListener)

    override fun login(juggGradleCompileOptions: JuggGradleCompileOptions) {
        // no need to login
        this.juggGradleCompileOptions = juggGradleCompileOptions
    }

    override fun compileAndFetchResult(isOnlyFetchResult: Boolean): GradleCompileResult {
        isCanceled = false
        val juggGradleCompileOptions = juggGradleCompileOptions ?: throw JuggInternalException.notLoginYet()

        if (!isOnlyFetchResult) {
            val compileProjectCommand = CompileProjectCommand(
                juggGradleCompileOptions.compileCommand,
                projectDir.path,
                juggGradleCompileOptions.initGradleFilePath,
                logger = logger,
                buildTarget = juggGradleCompileOptions.buildTarget,
                libraryTestApkGradleTasks = juggGradleCompileOptions.libraryTestApkGradleTasks,
            )
            val compileProjectResult = invoke(compileProjectCommand)
            if (compileProjectResult != 0) {
                printToStreamErrorIfCanceled("Compile project failed, please check the error message.")
                return GradleCompileResult.failed(isCanceled, "Compile project failed $compileProjectResult")
            }
        }

        val lookupPlan = ApkLookupPlanner.build(juggGradleCompileOptions)
        val lookingApkPaths = lookupPlan.requiredPatterns
        val findApks = mutableListOf<FoundApk>()
        val failedApkPaths = mutableListOf<String>()
        val shouldIndexAppApk = lookingApkPaths.size > 1 || juggGradleCompileOptions.buildTarget == BuildTarget.ANDROID_TEST
        lookingApkPaths.forEachIndexed { index, it ->
            val apkFile = findApk(it, juggGradleCompileOptions, if (shouldIndexAppApk) index else null)
            if (apkFile != null) {
                findApks.add(apkFile)
            } else {
                failedApkPaths.add(it)
            }
        }
        findAndroidTestApks(findApks, juggGradleCompileOptions).let { testApks ->
            findApks.addAll(testApks)
            if (juggGradleCompileOptions.buildTarget == BuildTarget.ANDROID_TEST && testApks.isEmpty()) {
                failedApkPaths.add("androidTest APK for ${juggGradleCompileOptions.outputApkName}")
            }
        }
        findApks.addAll(findOptionalLibraryTestApks(findApks.size, lookupPlan.optionalLibraryTestPatterns, juggGradleCompileOptions))

        if (failedApkPaths.isNotEmpty() || findApks.isEmpty()) {
            printToStreamError("Can't find apks in $failedApkPaths in $projectDir " +
                    "by argument: \"${juggGradleCompileOptions.outputApkName}\"" +
                    ", please make sure your run configuration is right.")
            return GradleCompileResult.failed(isCanceled, "Can't find apk in $failedApkPaths")
        } else {
            logger.debug("Find apk: ${findApks.map { it.outputFile }}")
        }

        return GradleCompileResult.success(findApks.map { it.outputFile })
    }

    private fun findApk(
        outputApkNameOrPath: String,
        juggGradleCompileOptions: JuggGradleCompileOptions,
        index: Int?,
        isRequired: Boolean = true,
    ): FoundApk? {
        val findOutputCommand = FindOutputCommand(projectDir.path, outputApkNameOrPath)

        var apkFiles: List<File>? = null
        if (findOutputCommand.findPath.isEmpty()) {
            // old logic, find apk by name

            // try sub dir first
            // currently only supports module located at root dir
            val subDirName = juggGradleCompileOptions.compileCommand.split(":").getOrNull(1) ?: "app"
            // run gradle command will put apk in subDir1
            val subDir1 = File(projectDir, "$subDirName/build/outputs")
            // run directly in android studio will only put apk in subDir2. this dir used for test mock event
            val subDir2 = File(projectDir, "$subDirName/intermediates/apk")
            if (subDir1.exists()) {
                apkFiles = subDir1.findFilesRecursively(juggGradleCompileOptions.outputApkName)
            } else if (subDir2.exists()) {
                apkFiles = subDir2.findFilesRecursively(juggGradleCompileOptions.outputApkName)
            }

            if (apkFiles == null) {
                // find in root dir
                val rootDir = File(projectDir.path)
                apkFiles = rootDir.findFilesRecursively(juggGradleCompileOptions.outputApkName)
            }
        } else {
            // new logic, find apk by path, faster
            val subDir = File(projectDir, findOutputCommand.findPath)
            if (subDir.exists()) {
                apkFiles = subDir.findFilesRecursively(findOutputCommand.findName)
            }
        }

        if (apkFiles.isNullOrEmpty()) {
            val message = "Can't find apk \"$outputApkNameOrPath\" " +
                    "in $projectDir, please make sure your run configuration is right."
            if (isRequired) {
                printToStreamError(message)
            } else {
                logger.warn("Optional library test APK not found: $outputApkNameOrPath")
            }
            return null
        }

        // find arm64-v8a -> find universal -> get first
        val apkFile = apkFiles.find { it.name.contains("-arm64-v8a-") }
            ?: apkFiles.find { it.name.contains("-universal-") }
                    ?: apkFiles[0]
        logger.debug("Find apks ${apkFiles.size}: ${apkFiles.map { it.absolutePath }}, result: $apkFile")

        // copy out for avoiding deleted by gradle
        val juggPathManager = JuggPathManager(File(juggGradleCompileOptions.projectRootPath))
        val outputApkFile = if (index != null) {
            File(juggPathManager.localClasspathStoragePathManager.apkDir, "${index}_${apkFile.name}")
        } else {
            File(juggPathManager.localClasspathStoragePathManager.apkDir, apkFile.name)
        }
        apkFile.copyTo(outputApkFile, overwrite = true)

        if (apkFile.length() != outputApkFile.length()) {
            logger.warn("Copy apk failed, length not match: ${apkFile.length()} != ${outputApkFile.length()}")
            return FoundApk(apkFile, apkFile)
        }
        return FoundApk(apkFile, outputApkFile)
    }

    private fun findOptionalLibraryTestApks(
        startIndex: Int,
        patterns: List<String>,
        options: JuggGradleCompileOptions,
    ): List<FoundApk> {
        return patterns.mapIndexedNotNull { index, pattern ->
            findApk(pattern, options, startIndex + index, isRequired = false)
        }
    }


    private fun findAndroidTestApks(appApks: List<FoundApk>, options: JuggGradleCompileOptions): List<FoundApk> {
        if (options.buildTarget != BuildTarget.ANDROID_TEST) {
            return emptyList()
        }
        val testApks = mutableListOf<FoundApk>()
        appApks.forEachIndexed { index, appApk ->
            val testApk = findAndroidTestApk(appApk.sourceFile, options, appApks.size + index)
            if (testApk != null) {
                testApks.add(testApk)
            } else {
                logger.warn("AndroidTest mode: cannot find test APK for ${appApk.sourceFile.absolutePath}")
            }
        }
        return testApks
    }

    private fun findAndroidTestApk(appApk: File, options: JuggGradleCompileOptions, index: Int): FoundApk? {
        val projectRoot = File(options.projectRootPath)
        val relativeAppApk = appApk.relativeToOrNull(projectRoot) ?: appApk
        val testApkPattern = deriveAndroidTestApkPattern(relativeAppApk.path) ?: return null
        return findApk(testApkPattern, options, index)
    }

    private data class FoundApk(val sourceFile: File, val outputFile: File)


    override fun fetchClasspathResult(buildDirs: List<ModuleBuildPathInfo>): File? {
        isCanceled = false

        val projectRootPath = File(projectDir.path)

        RsyncCompatibleHelper.init(logger)
        JuggSettings.isCanUseBackupClasspath = RsyncCompatibleHelper.isCompatible
        if (!JuggSettings.isCanUseBackupClasspath) {
            logger.info("isCanUseBackupClasspath is false, skip fetchClasspathResult")
            return null
        }
        if (!JuggSettings.isEnableBackupClasspath) {
            logger.info("isSupportsBackupClasspath is false, skip fetchClasspathResult")
            return null
        }

        try {
            // calculate root dir of project
            var rootDir = projectRootPath
            buildDirs.forEach { moduleBuildPathInfo ->
                val moduleDir = moduleBuildPathInfo.moduleRootDir
                if (!moduleDir.isChild(rootDir) && moduleDir != rootDir) {
                    while (true) {
                        val parentFile = rootDir.parentFile ?: run {
                            logger.warn("fetchClasspathResult failed, can't find parentFile of $moduleDir")
                            return@forEach
                        }
                        rootDir = parentFile
                        if (moduleDir.isChild(rootDir) || moduleDir == rootDir) {
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
                isEnableLog = false,
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
            juggGradleCompileOptions.initGradleFilePath,
            incDeployTimes,
            buildTarget = juggGradleCompileOptions.buildTarget,
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
        fun deriveAndroidTestApkPattern(appApkPath: String): String? {
            val normalized = appApkPath.replace('\\', '/')
            val marker = "/build/outputs/apk/"
            val markerIndex = normalized.indexOf(marker)
            if (markerIndex < 0 || normalized.contains("/androidTest/")) {
                return null
            }
            val prefix = normalized.substring(0, markerIndex + marker.length)
            val outputParts = normalized.substring(prefix.length).split("/").filter { it.isNotEmpty() }
            if (outputParts.size < 2) {
                return null
            }
            val variantDirs = outputParts.dropLast(1)
            return "${prefix}androidTest/${variantDirs.joinToString("/")}/*.apk"
        }


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

        fun buildCompileEnv(project: Project, logger: Logger): List<String> {
            val gradleJdkPath = PlatformApi.getGradleJdkPath(project, logger)
            val androidHomePath = PlatformApi.getAndroidHomePath(logger)
            val envArray: MutableList<String> = System.getenv().entries
                .filter {
                    it.key != "JAVA_HOME" && it.key != "ANDROID_HOME"
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
            return envArray
        }

    }
}
