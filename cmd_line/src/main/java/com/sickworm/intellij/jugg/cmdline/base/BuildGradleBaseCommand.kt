package com.sickworm.intellij.jugg.cmdline.base

import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.apk.ApkInfoReader
import com.sickworm.intellij.jugg.cmdline.logger.CmdLineLogger
import com.sickworm.intellij.jugg.deploy.data.DeployDataDatabase
import com.sickworm.intellij.jugg.deploy.data.SourceFileManager
import com.sickworm.intellij.jugg.gradle.compile.GradleScriptWriter
import com.sickworm.intellij.jugg.gradle.compile.LocalGradleCompileClient
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.ide.bean.SyncMode
import com.sickworm.intellij.jugg.logger.getInstance
import com.sickworm.intellij.jugg.project.JuggPathManager
import com.sickworm.intellij.jugg.project.ProjectInfoSerializer
import com.sickworm.intellij.jugg.project.data.JuggProjectInfo
import java.io.File

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
        val compileOptions = JuggGradleCompileOptions(
            projectRootPath = pathManager.projectDir.absolutePath,
            localClasspathStoragePath = pathManager.localClasspathStoragePathManager,
            initGradleFileRelativePath = pathManager.initGradleFileRelativePath,
            params.compileCommand,
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

        val databaseDir = pathManager.databaseDir
        val deployDataDatabase = DeployDataDatabase(File(databaseDir, "apk"), logger.getInstance("DeployDataDatabase"))
        deployDataDatabase.init(apkInfos, emptyList())


        val sourceFileManager = SourceFileManager(logger.getInstance("SourceFileManager"), databaseDir)
        val gradleProjectInfo = getProjectInfo()
        val sourceDirs = gradleProjectInfo.modules.flatMap {
            it.value.sourceDirs
        }
        sourceFileManager.init(sourceDirs)
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
            val params = ParamsParser().parse(args)
            if (params == null) {
                CmdLineLogger.stdLogger.warn("Parse params invalid, exit.")
                return false
            }
            return BuildGradleBaseCommand(params).run()
        }
    }
}