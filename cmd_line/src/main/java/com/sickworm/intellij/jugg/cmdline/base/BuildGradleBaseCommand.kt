package com.sickworm.intellij.jugg.cmdline.base

import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.apk.ApkInfoReader
import com.sickworm.intellij.jugg.cmdline.logger.CmdLineLogger
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.deploy.DeployHistoryManager
import com.sickworm.intellij.jugg.deploy.data.DeployDataDatabase
import com.sickworm.intellij.jugg.deploy.data.SourceFileManager
import com.sickworm.intellij.jugg.gradle.compile.GradleScriptWriter
import com.sickworm.intellij.jugg.gradle.compile.LocalGradleCompileClient
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.ide.bean.SyncMode
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.logger.getInstance
import com.sickworm.intellij.jugg.project.ChangedFile
import com.sickworm.intellij.jugg.project.IFileChangesHandler
import com.sickworm.intellij.jugg.project.JuggPathManager
import com.sickworm.intellij.jugg.project.ProjectInfoSerializer
import com.sickworm.intellij.jugg.project.data.JuggProjectInfo
import com.sickworm.intellij.jugg.project.data.LibraryDependency
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.measureTimeMillis

class BuildGradleBaseCommand(private val params: Params) {

    private val pathManager = JuggPathManager(params.baseBuildProjectDir)
    private val logger = run {
        CmdLineLogger.init(pathManager.logDir, params.logLevel)
        CmdLineLogger.logger
    }

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
        }
    }

    private fun prepare() {
        pathManager.juggRootDir.deleteRecursively()
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

        val compileClient = LocalGradleCompileClient(
            pathManager.projectDir,
            pathManager.localClasspathStoragePathManager.classpathDir,
            null,
            logger,
        )
        val compileCommand = "./gradlew ${params.gradleCompileTask}"

        val compileOptions = JuggGradleCompileOptions(
            projectRootPath = pathManager.projectDir.absolutePath,
            localClasspathStoragePath = pathManager.localClasspathStoragePathManager,
            initGradleFileRelativePath = pathManager.initGradleFileRelativePath,
            compileCommand,
            params.outputApkPath,
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
        val backupJob = CoroutineScope(Dispatchers.IO).launch {
            logger.info("Backup library dependencies start.")
            val costTime = measureTimeMillis {
                try {
                    val backupGradleProjectInfo = LibrariesBackupHelper(pathManager, gradleProjectInfo, logger).backup()
                    ProjectInfoSerializer(pathManager.gradleProjectInfoFile, logger).save(backupGradleProjectInfo)
                } catch (e: Exception) {
                    logger.warn("Backup library dependencies failed", e)
                    pathManager.localClasspathStoragePathManager.librariesBackupDir.deleteRecursively()
                }
            }
            logger.info("Backup library dependencies finish. cost: ${costTime}ms")
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
        val sourceFileManager = SourceFileManager(logger.getInstance("SourceFileManager"), databaseDir)
        val sourceDirs = gradleProjectInfo.modules.flatMap {
            it.value.sourceDirs
        }
        sourceFileManager.init(sourceDirs)

        runBlocking {
            backupJob.join()
            if (!pathManager.localClasspathStoragePathManager.librariesBackupDir.exists()) {
                throw BaseBuildException("Backup library dependencies failed.")
            }
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