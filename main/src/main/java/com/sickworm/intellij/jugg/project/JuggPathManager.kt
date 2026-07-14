package com.sickworm.intellij.jugg.project

import java.io.File

/**
 * Declaration of path usage for Jugg.
 */
class JuggPathManager(
    val projectDir: File,
    val juggRootDir: File = File("$projectDir/build/jugg"),
    globalJuggRootDir: File = File(System.getProperty("user.home"), ".jugg"),
) {
    val stableGradleDir: File = File(projectDir, ".gradle/jugg")
    val constRefDir: File = File(globalJuggRootDir, "const_ref")
    val constRefSharedDbFile: File = File(constRefDir, "const_ref_shared.db")
    val repoFingerprintDbFile: File = File(constRefDir, "repo_fingerprint.db")
    val libraryTestBuildRecordDir: File = File(globalJuggRootDir, "library_test_build_records")

    val compileRootDir = File(juggRootDir, "build")
    val stagingDir = File(compileRootDir, "staging")
    val databaseDir = File(juggRootDir, "database")
    val logDir = File(juggRootDir, "log")
    val mcpFetchDir = File(juggRootDir, "mcp_fetch")
    val runtimeLockFile = File(juggRootDir, "runtime.lock")
    val runtimeLockOwnerFile = File(juggRootDir, "runtime.lock.owner.json")

    val deploymentCacheDir = File(juggRootDir, "deploy_cache")
    val deploymentCacheDbFile = File(deploymentCacheDir, ".deploy_cache.db")
    val deploymentCacheTempFile = File(deploymentCacheDir, ".deploy_cache.db.tmp")

    val tmpDir = File(juggRootDir, "tmp")
    val diagnosticsDir = File(tmpDir, "diagnostics")
    val remoteDiffDir = File(tmpDir, "diff")
    val remoteDiffLibraryDir = File(remoteDiffDir, "libraries")
    /** result file to show, because it's diff with last build */
    val remoteDiffResultFile = File(remoteDiffDir, "diff_result.json")
    /** result file to compile, because it's diff with last full build */
    val remoteDiffResultWithFullFile = File(remoteDiffDir, "full_diff_result.json")
    val tmpGradleProjectInfo = File(tmpDir, "project_infos")

    val configDir = File(juggRootDir, "config")

    val projectInfosDir = File(databaseDir, "project_infos.db")
    val ideProjectInfoFile = File(projectInfosDir, "project_infos.json")
    val gradleProjectInfoFile = File(projectInfosDir, "gradle_project_infos.json")
    val gradleIncludeBuildsFile = File(projectInfosDir, "gradle_include_builds.txt")
    val markProjectInfoNeedUpdateFlagFile = File(projectInfosDir, "is_dirty")
    val historyProjectDirFile = File(projectInfosDir, "history_project_dir.txt")

    val compileContextDbDir = File(databaseDir, "compile_context.db")
    val deployHistoryDbDir = File(databaseDir, "deploy_history.db")

    val localClasspathStoragePathManager = LocalClasspathStoragePathManager(File(juggRootDir, "classpath"))

    val initGradleFilePath = File(stableGradleDir, "readProjectInfo.gradle.kts")
    val runtimeJarFilePath = File(stableGradleDir, "jugg-runtime.jar")

    val customCompilerDir = File(configDir, "custom_compilers")

    companion object {
        const val RSYNC_PUSH_CONFIG_DIR_ARGUMENTS = "--include='/.gradle/' --include='/.gradle/jugg/' --include='/.gradle/jugg/**' --exclude='/.gradle/**' " +
                "--include='/build/' --include='/build/jugg/' " +
                "--include='/build/jugg/config/' --include='/build/jugg/config/**' " +
                "--include='/build/jugg/database/' --include='/build/jugg/database/project_infos.db/' --include='/build/jugg/database/project_infos.db/project_infos.json' " +
                "--exclude='/build/**'"
        const val RSYNC_FETCH_DIFF_DIR_ARGUMENTS = "--include='build/jugg/tmp/diff/**'"
    }
}

/**
 * LocalClasspathStoragePathManager defines stable directories used to store classpath/APK artifacts for local compile workflows.
 */
class LocalClasspathStoragePathManager(
    val rootDir: File,
) {
    val classpathDir: File = File(rootDir, "root")
    val apkDir: File = File(rootDir, "apk")
    val librariesBackupDir: File = File(rootDir, "libraries")
    val embeddedApkDir: File = File(rootDir, "embedded_apk")

    override fun toString(): String {
        return "LocalClasspathStoragePathManager(rootDir=$rootDir)"
    }
}
