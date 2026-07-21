package com.sickworm.intellij.jugg.ide

import com.intellij.execution.configurations.RunConfigurationOptions

/**
 * Implementation of [RunConfigurationOptions], which is the bean of config content.
 */
class JuggRunConfigurationOptions: RunConfigurationOptions() {

    var compileCommand by string()
    var outputApkName by string()
    var isRemoteCompile by property(false)
    var remoteSshUser by string()
    var remoteSshPassword by string()
    var remoteSshIp by string()
    var remoteSshPort by property(0)
    var localToRemoteIftConfigName by string()
    var localToRemoteSyncPath by string()
    var remoteSyncPath by string()
    var remoteToLocalIftConfigName by string()
    var remoteToLocalSyncPath by string()
    var httpProxyIp by string()
    var httpProxyPort by property(0)
    var isSyncAllProjects by property(false)

    @Suppress("unused")
    @Deprecated("No longer use")
    var hasSetDefaultValue by property(false)

    var syncMode by string()

    var environmentVariables by string()

    /** When true, Jugg compiles both the app and androidTest APKs using Gradle fallback. */
    var enableAndroidTest by property(false)

    /** Rsync glob patterns excluded from remote source sync. */
    var remoteSyncExcludePatterns by string()

    // new options must add to the end because property persist is in order

}
