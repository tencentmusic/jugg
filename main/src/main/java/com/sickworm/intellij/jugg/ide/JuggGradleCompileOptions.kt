package com.sickworm.intellij.jugg.ide

import com.sickworm.intellij.jugg.gradle.compile.isChild
import com.sickworm.intellij.jugg.project.JuggException
import com.sickworm.intellij.jugg.project.JuggPathManager
import com.sickworm.intellij.jugg.project.LocalClasspathStoragePathManager
import java.io.File

/**
 * Wrapper of [JuggRunConfigurationOptions], which is used for compilation.
 */
data class JuggGradleCompileOptions(
    /**
     * Local project root path.
     */
    val projectRootPath: String,
    /**
     * Location where stores apk and build files.
     * e.g. build/jugg/classpath
     */
    val localClasspathStoragePath: LocalClasspathStoragePathManager,
    /**
     * Gradle initial script, use to read project info.
     * e.g. build/jugg/config/readProjectInfo.gradle.kts
     */
    val initGradleFileRelativePath: String,
    /**
     * e.g. ./gradlew :app:assembleDebug
     */
    val compileCommand: String,
    /**
     * e.g. app/build/outputs/apk/debug/\*.apk
     */
    val outputApkName: String,
    /**
     * whether to use remote server to compile.
     */
    val isRemoteCompile: Boolean,
    /**
     * Whether to sync all files in [localToRemoteSyncPath].
     */
    val isSyncAllProjects: Boolean,
    val remoteSshUser: String,
    val remoteSshPassword: String,
    val remoteSshIp: String,
    val remoteSshPort: Int,
    /**
     * IFT config name for syncing files from local to remote.
     * No meaning if syncMode != IFT.
     */
    val localToRemoteIftConfigName: String,
    /**
     * Root directory to specify syncing files from local to remote.
     * The path must be the parent of [projectRootPath].
     * when syncMode:
     *  IFT -> The path match config of [localToRemoteIftConfigName]. Works with [isSyncAllProjects].
     *  RSYNC -> Works with [isSyncAllProjects].
     *  RSYNC_SIMPLE -> No meaning. RSYNC_SIMPLE will only sync [projectRootPath] and this field must be the parent directory of it.
     */
    val localToRemoteSyncPath: String,
    /**
     * Remote root directory to receive synced files from local.
     * Optional value. If empty, will use default value: $HOME/remote
     * e.g. /root/remote
     */
    val remoteSyncPath: String,
    /**
     * IFT config name for syncing files from remote to local.
     * No meaning if syncMode != IFT.
     */
    val remoteToLocalIftConfigName: String,
    /**
     * Root directory to specify syncing files from remote to local.
     * when syncMode:
     *  IFT -> The path match config of [remoteToLocalIftConfigName].
     *  RSYNC -> Any directory is fine.
     *  RSYNC_SIMPLE -> No meaning. Will store files directly to [localClasspathStoragePath]
     */
    val remoteToLocalSyncPath: String,
    val httpProxyIp: String,
    val httpProxyPort: Int,
    /**
     * Sync mode for remote compile.
     * when syncMode:
     *  IFT -> A specific sync tool.
     *  RSYNC -> Built-in sync tool of Linux & macOS.
     *  RSYNC_SIMPLE -> Built-in sync tool of Linux & macOS, but has simple configuration which will only sync [projectRootPath].
     */
    val syncMode: SyncMode,
) {


    private val projectSyncRelativePath get() = if (syncMode.isRsyncSimple) {
        File(projectRootPath).name
    } else {
        File(projectRootPath)
            .relativeTo(File(localToRemoteSyncPath)).path
            .replace(" ", "\\ ")
    }

    private val projectSyncRootRelativePath: String get() = projectSyncRelativePath.substringBefore(File.separatorChar)

    /** remote home directory */
    private val remoteHomePath = if (remoteSshUser == "root") "/root" else "/data/home/$remoteSshUser"

    /** project storage directory */
    val finalRemoteSyncPath = run {
        var finalPath = remoteSyncPath.ifEmpty { "$remoteHomePath/remote" }
        if (!finalPath.startsWith("/")) {
            // relative path
            finalPath = "$remoteHomePath/$finalPath"
        }
        if (finalPath.endsWith("/")) {
            finalPath = finalPath.substring(0, finalPath.length - 1) // must remove last '/' to standardize sync path
        }
        finalPath
    }

    /** local iFt path, used for syncing files to remote by iFt */
    val localSyncIftPath get() = if (isSyncAllProjects) {
        localToRemoteIftConfigName
    } else {
        "$localToRemoteIftConfigName/$projectSyncRootRelativePath"
    }
    val localSyncRsyncPath get() = if (syncMode.isRsyncSimple) {
        "$projectRootPath/"
    } else if (isSyncAllProjects) {
        "$localToRemoteSyncPath/"
    } else {
        "$localToRemoteSyncPath/$projectSyncRootRelativePath/"
    }

    /** remote project sync path, used for syncing files to remote by iFt, and fetching classpath */
    val remoteSyncRootPath get() = if (isSyncAllProjects) {
        finalRemoteSyncPath
    } else {
        "$finalRemoteSyncPath/$projectSyncRootRelativePath"
    }
    val remoteSyncRootRsyncPath get() = if (syncMode.isRsyncSimple) {
        "$remoteSshUser@$remoteSshIp:$finalRemoteSyncPath/$projectSyncRootRelativePath"
    } else {
        "$remoteSshUser@$remoteSshIp:$remoteSyncRootPath"
    }

    /** remote project root path, used for compilation */
    val remoteProjectPath get() = "$finalRemoteSyncPath/$projectSyncRelativePath"
    val remoteProjectRsyncPath get() = "$remoteSshUser@$remoteSshIp:$remoteProjectPath/" // rsync_simple use the same path

    /** remote iFt path, used for fetching apk output to local */
    val remoteToLocalProjectIftPath get() = "$remoteToLocalIftConfigName/$projectSyncRelativePath"
    val remoteToLocalProjectRsyncPath get() = if (syncMode.isRsyncSimple) {
        "${localClasspathStoragePath.apkDir.absolutePath}/"
    } else {
        "$remoteToLocalSyncPath/$projectSyncRelativePath/"
    }

    /** remote iFt path, used for fetching classpath output to local */
    val remoteToLocalRootIftPath get() = if (isSyncAllProjects) {
        "$remoteToLocalIftConfigName/jugg_all_classpath"
    } else {
        "$remoteToLocalIftConfigName/$projectSyncRootRelativePath"
    }
    val remoteToLocalRootRsyncPath get() = if (syncMode.isRsyncSimple) {
        "${localClasspathStoragePath.classpathDir.absolutePath}/"
    } else if (isSyncAllProjects) {
        "$remoteToLocalSyncPath/jugg_all_classpath/"
    } else {
        "$remoteToLocalSyncPath/$projectSyncRootRelativePath/"
    }

    /** local apk path, used for get apk output */
    val remoteToLocalProjectSyncPath: String get() = if (syncMode.isRsyncSimple) {
        localClasspathStoragePath.apkDir.absolutePath
    } else {
        "$remoteToLocalSyncPath/$projectSyncRelativePath"
    }

    /** local classpath path, used for get classpath output */
    val remoteToLocalSyncClasspathPath: String get() = if (syncMode.isRsyncSimple) {
        "${localClasspathStoragePath.classpathDir.absolutePath}/$projectSyncRelativePath"
    } else if (isSyncAllProjects) {
        "$remoteToLocalSyncPath/jugg_all_classpath/${File(finalRemoteSyncPath).name}/$projectSyncRelativePath"
    } else {
        "$remoteToLocalSyncPath/$projectSyncRootRelativePath/$projectSyncRelativePath"
    }

    fun checkConfig() {
        var errorDetails = ""

        if (compileCommand.isEmpty()) {
            errorDetails += "Run configuration argument [Compile command] is empty\n"
        }
        if (outputApkName.isEmpty()) {
            errorDetails += "Run configuration argument [Output apk name] is empty\n"
        }
        if (isRemoteCompile) {
            if (remoteSshUser.isEmpty()) {
                errorDetails += "Run configuration argument [SSH user] is empty\n"
            }
            if (remoteSshIp.isEmpty()) {
                errorDetails += "Run configuration argument [SSH host] is empty\n"
            }
            if (remoteSshPort <= 0) {
                errorDetails += "Run configuration argument [SSH port] is invalid\n"
            }

            if (!syncMode.isRsync) {
                if (localToRemoteIftConfigName.isEmpty()) {
                    errorDetails += "Run configuration argument [Local to remote IFT config] name is empty\n"
                }
                if (remoteToLocalIftConfigName.isEmpty()) {
                    errorDetails += "Run configuration argument [Remote to local IFT config] name is empty\n"
                }
            }

            if (!syncMode.isRsyncSimple) {
                if (localToRemoteSyncPath.isEmpty()) {
                    errorDetails += "Run configuration argument [Local to remote sync path] is empty\n"
                }
                if (remoteToLocalSyncPath.isEmpty()) {
                    errorDetails += "Run configuration argument [Remote to local sync path] is empty\n"
                }
                if (!File(projectRootPath).isChild(File(localToRemoteSyncPath)) &&
                    (projectRootPath != localToRemoteSyncPath)) {
                    errorDetails += "Run configuration argument [Local to remote IFT sync path]($localToRemoteSyncPath) " +
                            "must be the parent of project path($projectRootPath)\n"
                }
            }
        }

        if (errorDetails.isNotEmpty()) {
            throw JuggException.runConfigInvalid(errorDetails)
        }
    }

    fun toSafeString(): String {
        val string = toString()
        val replacePasswordDesc = if (remoteSshPassword.isNotEmpty()) "(has_password)" else "(no_password)"
        return string.replace("remoteSshPassword=$remoteSshPassword", "remoteSshPassword=$replacePasswordDesc")
    }
}