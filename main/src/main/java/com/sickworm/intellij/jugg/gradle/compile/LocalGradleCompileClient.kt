package com.sickworm.intellij.jugg.gradle.compile

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ModuleRootManager
import com.sickworm.intellij.jugg.project.data.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.deploy.run.AsDeployerCompat
import com.sickworm.intellij.jugg.compiler.isWindows
import com.sickworm.intellij.jugg.ide.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.project.CompileContextManager
import com.sickworm.intellij.jugg.project.JuggInternalException
import com.sickworm.intellij.jugg.project.JuggPathManager
import com.sickworm.intellij.jugg.project.ProjectInfoSerializer
import com.sickworm.intellij.jugg.project.dependency.DependencyDiffResult
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

        val javaHome = System.getenv("JAVA_HOME")
        logger.debug("JAVA_HOME: $javaHome")

        val rootModule = AsDeployerCompat.getModuleManager(project).modules.find {
            it.name == project.name
        }
        if (rootModule != null) {
            val moduleRootManager = ModuleRootManager.getInstance(rootModule)
            val jdk: Sdk? = moduleRootManager.sdk
            if (jdk != null && jdk.sdkType == JavaSdk.getInstance() && jdk.homePath != null) {
                logger.debug("found gradleJdkPath in root module: ${rootModule.name}, path: ${jdk.homePath}")
                gradleJdkPath = jdk.homePath
            }
        }
        if (gradleJdkPath == null) {
            gradleJdkPath = AsDeployerCompat.getModuleManager(project).modules.firstNotNullOfOrNull { module ->
                val moduleRootManager = ModuleRootManager.getInstance(module)
                val jdk: Sdk = moduleRootManager.sdk ?: return@firstNotNullOfOrNull null
                if (jdk.sdkType != JavaSdk.getInstance()) {
                    return@firstNotNullOfOrNull null
                }
                if (jdk.homePath == null) {
                    return@firstNotNullOfOrNull null
                }
                logger.debug("found gradleJdkPath in module: ${module.name}, path: ${jdk.homePath}")
                return@firstNotNullOfOrNull jdk.homePath!!
            }
        }
        if (gradleJdkPath == null) {
            logger.debug("can't find gradleJdkPath in modules, use JAVA_HOME $javaHome instead")
            gradleJdkPath = javaHome
        }

        val androidHome = System.getenv("ANDROID_HOME")
        logger.debug("ANDROID_HOME: $androidHome")
        androidHomePath = CompileContextManager.getAndroidSdkRootDir(logger)?.absolutePath
        if (androidHomePath == null) {
            logger.debug("can't find androidHomePath in modules, use ANDROID_HOME $androidHome instead")
            androidHomePath = androidHome
        } else {
            logger.debug("found androidHomePath $androidHomePath")
        }
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

    override fun fetchLibraryChanges(currentBuildChecksum: String, lastBuildChecksum: String): DependencyDiffResult? {
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
            currentBuildChecksum,
            lastBuildChecksum,
        )
        val compileProjectResult = invoke(diffLibraryChangesCommand)
        if (compileProjectResult != 0) {
            printToStreamErrorIfCanceled("Diff library changes failed, please check the error message.")
            return null
        }

        val diffFile = juggPathManager.remoteDiffResultFile
        if (!diffFile.exists()) {
            printToStreamErrorIfCanceled("Diff file not found, please check the error message.")
            return null
        }
        val dependencyDiffResult = try {
            val diffResult = ProjectInfoSerializer.gson.fromJson(diffFile.readText(), DependencyDiffResult::class.java)
            // replace diffResult path to local absolute path
            diffResult.convertToAbsolutePath(juggPathManager.remoteDiffLibraryDir)
        } catch (e: Exception) {
            printToStreamErrorIfCanceled("Parse diff result failed, please check the error message.")
            return null
        }
        printToStreamInfo("[Jugg] found changed libraries: ${dependencyDiffResult.changedLibraries.size}")

        return dependencyDiffResult
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

}
