package com.sickworm.intellij.jugg.cmdline.base

import com.intellij.openapi.progress.DumbProgressIndicator
import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.apk.ApkInfoReader
import com.sickworm.intellij.jugg.cmdline.logger.CmdLineLogger
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.deploy.DeployHistoryManager
import com.sickworm.intellij.jugg.deploy.data.DeployDataDatabase
import com.sickworm.intellij.jugg.deploy.data.SourceFileManager
import com.sickworm.intellij.jugg.gradle.compile.GradleScriptWriter
import com.sickworm.intellij.jugg.gradle.compile.LocalGradleCompileClient
import com.sickworm.intellij.jugg.gradle.compile.RsyncCompatibleHelper
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.ide.bean.SyncMode
import com.sickworm.intellij.jugg.logger.getInstance
import com.sickworm.intellij.jugg.project.*
import com.sickworm.intellij.jugg.project.data.JuggProjectInfo
import kotlinx.coroutines.*
import java.io.File
import kotlin.system.measureTimeMillis

class BuildGradleBaseCommand(private val params: Params) {

    init {
        clearBuildDir()
    }

    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private val pathManager = JuggPathManager(params.baseBuildProjectDir)
    private val logger = CmdLineLogger.init("BuildGradleBaseCommand", pathManager.logDir, params.logLevel)
    private val compileClient = LocalGradleCompileClient(
        pathManager.projectDir,
        pathManager.localClasspathStoragePathManager.classpathDir,
        null,
        logger,
    )

    fun run(): Boolean {
        try {
            prepare()
            val apkInfos = gradleCompile()
            initAfterGradleCompile(apkInfos)
            logger.info("Build gradle base success.")
            return true
        } catch (e: BaseBuildException) {
            logger.warn("Build gradle base failed", e)
            logger.warn("Build gradle base failed, reason: ${e.message}")
            return false
        } catch (e: Throwable) {
            logger.warn("Build gradle base failed unexpected", e)
            logger.warn("Build gradle base got unexpected error: ${e.message}")
            return false
        } finally {
            coroutineScope.cancel()
            CmdLineLogger.release("BuildGradleBaseCommand")
        }
    }

    private fun clearBuildDir() {
        JuggPathManager(params.baseBuildProjectDir).juggRootDir.deleteRecursively()
    }

    private fun prepare() {
        GradleScriptWriter(pathManager, logger).writeInitGradleFile()
    }

    private fun gradleCompile(): List<ApkInfo> {
        val javaHome = System.getenv("JAVA_HOME")
        if (javaHome == null || javaHome.isEmpty()) {
            throw BaseBuildException("JAVA_HOME not found.")
        }
        logger.info("JAVA_HOME: $javaHome")
        val androidHome = System.getenv("ANDROID_HOME")
        if (androidHome == null || androidHome.isEmpty()) {
            throw BaseBuildException("ANDROID_HOME not found.")
        }
        logger.info("ANDROID_HOME: $androidHome")

        val compileCommand = "./gradlew ${params.gradleCompileTask}"

        val compileOptions = JuggGradleCompileOptions(
            projectRootPath = pathManager.projectDir.absolutePath,
            localClasspathStoragePath = pathManager.localClasspathStoragePathManager,
            initGradleFileRelativePath = pathManager.initGradleFileRelativePath,
            compileCommand,
            params.gradleOutputApkPath,
            isRemoteCompile = false,
            isSyncAllProjects = false,
            remoteSshUser = "",
            remoteSshPassword = "",
            remoteSshIp = "",
            remoteSshPort = 0,
            localToRemoteIftConfigName = "",
            localToRemoteSyncPath = "",
            remoteSyncPath = "",
            remoteToLocalIftConfigName = "",
            remoteToLocalSyncPath = "",
            httpProxyIp = "",
            httpProxyPort = 0,
            syncMode = SyncMode.RSYNC_SIMPLE,
        )
        JuggSettings.isEnableBackupClasspath = false

        compileClient.login(compileOptions)
        val result = compileClient.compileAndFetchResult()
        if (!result.isSuccess) {
            throw BaseBuildException("Gradle compile failed.")
        }

        // get apk infos
        val apkInfos = ApkInfoReader(logger).createApkInfo(result.compileOutputFile)
        if (apkInfos.isEmpty()) {
            throw BaseBuildException("No apk found.")
        }
        return apkInfos
    }

    private fun initAfterGradleCompile(apkInfos: List<ApkInfo>) {
        logger.info("Start init after gradle compile.")
        val startTime = System.currentTimeMillis()

        // backup library dependencies
        val gradleProjectInfo = getProjectInfo()

        var libraryProjectInfo: JuggProjectInfo? = null
        val backupLibraryJob = coroutineScope.async {
            logger.info("Backup library dependencies start.")
            val costTime = measureTimeMillis {
                try {
                    libraryProjectInfo = LibrariesBackupHelper(pathManager, gradleProjectInfo, logger).backup()
                } catch (e: Exception) {
                    logger.warn("Backup library dependencies failed", e)
                }
            }
            logger.info("Backup library dependencies finish. cost ${costTime / 1000}s.")
        }

        var classpathProjectInfo: JuggProjectInfo? = null
        val backupClasspathJob = coroutineScope.launch {
            logger.info("Backup classpath start.")
            RsyncCompatibleHelper.init(logger)
            JuggSettings.isEnableBackupClasspath = true
            val costTime = measureTimeMillis {
                try {
                    val currentIndicator = object : DumbProgressIndicator() {
                        override fun setText(text: String?) {
                            if (text != null) {
                                logger.debug(text)
                            }
                        }
                    }
                    classpathProjectInfo = ClasspathBackupHelper(
                        compileClient, currentIndicator, coroutineScope, logger, 5000L)
                        .fetch(gradleProjectInfo)
                    if (classpathProjectInfo == null) {
                        logger.warn("Backup classpath failed.")
                    }
                } catch (e: Exception) {
                    logger.warn("Backup classpath failed by exception", e)
                }
            }
            logger.info("Backup classpath finish. cost ${costTime / 1000}s.")
        }

        // init deploy history
        val deployHistoryManager = DeployHistoryManager(
            pathManager,
            object : IFileChangesHandler {
                override fun init(compileContext: ICompileContext) = Unit
                override fun filter(file: List<File>): List<ChangedFile> = emptyList()
                override fun updateBuildFileRules(rules: List<String>, doNotIgnoreModulePaths: List<String>) = Unit
            },
            logger.getInstance("DeployHistoryManager")
        )
        val startCompileTime = System.currentTimeMillis()
        deployHistoryManager.checkProjectDirChanged()
        deployHistoryManager.reInitAfterFullCompiled(
            apkInfos,
            gradleProjectInfo.modules,
            startCompileTime,
        )

        // init deploy data database
        val databaseDir = pathManager.databaseDir
        val deployDataDatabase = DeployDataDatabase(File(databaseDir, "apk"), logger.getInstance("DeployDataDatabase"))
        deployDataDatabase.init(apkInfos, emptyList())

        // init source file manager
        val sourceFileManager = SourceFileManager(pathManager.projectDir, databaseDir, logger.getInstance("SourceFileManager"))
        val sourceDirs = gradleProjectInfo.modules.flatMap {
            it.value.sourceDirs
        }
        sourceFileManager.init(sourceDirs)

        // copy apk to outputApkDir
        if (params.outputApkDir != null) {
            params.outputApkDir.deleteRecursively()
            params.outputApkDir.mkdirs()
            apkInfos.forEach { apkInfo ->
                apkInfo.files.forEach {
                    val targetFile = File(params.outputApkDir, it.apkFile.name)
                    it.apkFile.copyTo(targetFile, overwrite = true)
                }
            }
        }

        runBlocking {
            backupLibraryJob.join()
            backupClasspathJob.join()
            val isBackupSuccess = libraryProjectInfo != null && classpathProjectInfo != null
            if (!isBackupSuccess) {
                throw BaseBuildException("Backup classpath and libraries failed.")
            }
            val finalProjectInfo = JuggProjectInfo(
                libraryProjectInfo!!.modules.mapValues {
                    it.value.copy(
                        buildPathInfo = classpathProjectInfo!!.modules[it.key]!!.buildPathInfo
                    )
                }
            )
            ProjectInfoSerializer(pathManager.gradleProjectInfoFile, logger).save(finalProjectInfo)
        }

        val costTime = System.currentTimeMillis() - startTime
        logger.info("Init after gradle compile finish. cost: ${costTime}ms")
    }

    private fun getProjectInfo(): JuggProjectInfo {
        val gradleProjectInfoFile = pathManager.gradleProjectInfoFile
        if (!gradleProjectInfoFile.exists()) {
            throw BaseBuildException("Gradle project info file not exists: ${gradleProjectInfoFile.absolutePath}")
        }
        val gradleProjectInfo = ProjectInfoSerializer(gradleProjectInfoFile, logger).load()
            ?: throw BaseBuildException("Gradle project info file invalid: ${gradleProjectInfoFile.absolutePath}")
        if (gradleProjectInfo.modules.isEmpty()) {
            throw BaseBuildException("Gradle project info file invalid: ${gradleProjectInfoFile.absolutePath}")
        }
        return gradleProjectInfo
    }

    companion object {
        fun run(args: Array<String>): Boolean {
            try {
                val params = ParamsParser().parse(args)
                return BuildGradleBaseCommand(params).run()
            } catch (e: BaseBuildException) {
                CmdLineLogger.stdLogger.warn("Parse params invalid, reason: ${e.message}")
                CmdLineLogger.stdLogger.warn("Parse params invalid, exit.")
                return false
            } catch (e : Throwable) {
                CmdLineLogger.stdLogger.warn("Parse params got unexpected error:", e)
                return false
            }
        }
    }
}