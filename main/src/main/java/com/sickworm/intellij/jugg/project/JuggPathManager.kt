package com.sickworm.intellij.jugg.project

import java.io.File

/**
 * Declaration of path usage for Jugg.
 */
class JuggPathManager(
    val projectDir: File,
    val juggRootDir: File = File("$projectDir/build/jugg"),
) {

    val compileRootDir = File(juggRootDir, "build")
    val stagingDir = File(compileRootDir, "staging")
    val databaseDir = File(juggRootDir, "database")
    val logDir = File(juggRootDir, "log")

    val tmpDir = File(juggRootDir, "tmp")
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

    val initGradleFilePath = File(configDir, "readProjectInfo.gradle.kts")
    val runtimeJarFilePath = File(configDir, "jugg-runtime.jar")
    val initGradleFileRelativePath: String = initGradleFilePath.relativeTo(projectDir).path

    val customCompilerDir = File(configDir, "custom_compilers")

    companion object {
        const val RSYNC_PUSH_CONFIG_DIR_ARGUMENTS = "--include='/build' --include='/build/jugg' " +
                "--include='/build/jugg/config' --include='/build/jugg/config/**' " +
                "--include='/build/jugg/database/' --include='/build/jugg/database/project_infos.db/' --include='/build/jugg/database/project_infos.db/project_infos.json' " +
                "--exclude='/build/**'"
        const val RSYNC_FETCH_DIFF_DIR_ARGUMENTS = "--include='build/jugg/tmp/diff/**'"
    }
}

class LocalClasspathStoragePathManager(
    val rootDir: File,
) {
    val classpathDir: File = File(rootDir, "root")
    val apkDir: File = File(rootDir, "apk")
    val librariesBackupDir: File = File(rootDir, "libraries")

    override fun toString(): String {
        return "LocalClasspathStoragePathManager(rootDir=$rootDir)"
    }
}
