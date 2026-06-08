package com.sickworm.intellij.jugg.deploy.instrument

import com.google.gson.GsonBuilder
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.git.GitManager
import com.sickworm.intellij.jugg.git.IGitManager
import com.sickworm.intellij.jugg.project.JuggPathManager
import com.sickworm.intellij.jugg.project.ModulePathMergePolicy
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import java.io.File
import java.security.MessageDigest

/**
 * Persists successful library Test APK backfill builds for later AndroidTest Gradle replay.
 */
class LibraryTestApkBuildHistory(
    private val projectDir: File,
    private val recordDir: File = JuggPathManager(projectDir).libraryTestBuildRecordDir,
    private val logger: Logger = Logger.getInstance(LibraryTestApkBuildHistory::class.java),
    private val gitInfoProvider: () -> GitProjectInfo = { resolveGitProjectInfo(projectDir) },
) {
    fun recordFile(): File {
        val info = gitInfoProvider()
        val projectName = sanitizeFileName(info.projectName.ifBlank { projectDir.name })
        val projectKeyHash = sha256(info.projectKey.trim()).take(8)
        return File(recordDir, "${projectName}_hash$projectKeyHash.json")
    }

    fun load(): LibraryTestApkBuildHistoryData {
        val file = recordFile()
        val currentProjectKey = gitInfoProvider().projectKey.trim()
        if (!file.exists()) {
            return LibraryTestApkBuildHistoryData(projectKey = currentProjectKey)
        }
        return try {
            val dto = gson.fromJson(file.readText(Charsets.UTF_8), LibraryTestApkBuildHistoryDataDto::class.java)
            dto?.normalize(currentProjectKey) ?: LibraryTestApkBuildHistoryData(projectKey = currentProjectKey)
        } catch (e: Exception) {
            logger.warn("Failed to load library Test APK build history from ${file.absolutePath}", e)
            LibraryTestApkBuildHistoryData(projectKey = currentProjectKey)
        }
    }

    fun record(record: LibraryTestApkBuildRecord) {
        val oldData = load()
        val records = oldData.records
            .filterNot { it.moduleName == record.moduleName && it.buildVariant == record.buildVariant }
            .plus(record)
            .sortedByDescending { it.compiledAt }
        save(oldData.copy(updatedAt = System.currentTimeMillis(), records = records))
    }

    fun selectRecentForAndroidTest(
        modules: Map<String, ModuleInfo>,
        buildVariant: String,
        nowMillis: Long = System.currentTimeMillis(),
        requestedTasks: Set<String> = emptySet(),
    ): List<LibraryTestApkBuildReplayRecord> {
        val minTime = nowMillis - THIRTY_DAYS_MS
        return load().records
            .asSequence()
            .filter { it.compiledAt >= minTime }
            .filter { it.buildVariant == buildVariant }
            .filter { it.isModuleAvailableIn(modules) }
            .sortedByDescending { it.compiledAt }
            .mapNotNull { record ->
                val task = record.gradleTask() ?: return@mapNotNull null
                if (task in requestedTasks) return@mapNotNull null
                LibraryTestApkBuildReplayRecord(task, record.outputApkPattern, record)
            }
            .distinctBy { it.gradleTask }
            .take(MAX_REPLAY_RECORDS)
            .toList()
    }

    private fun save(data: LibraryTestApkBuildHistoryData) {
        val file = recordFile()
        file.parentFile?.mkdirs()
        file.writeText(gson.toJson(data), Charsets.UTF_8)
    }

    private fun LibraryTestApkBuildRecord.gradleTask(): String? {
        return compileCommand.split(Regex("\\s+"))
            .firstOrNull { token ->
                token.startsWith(":") && GRADLE_ANDROID_TEST_ASSEMBLE_TASK.matches(token.substringAfterLast(":"))
            }
    }

    private fun LibraryTestApkBuildRecord.isModuleAvailableIn(modules: Map<String, ModuleInfo>): Boolean {
        if (modules.containsKey(moduleName)) {
            return true
        }
        val ownerModuleName = ModulePathMergePolicy.androidTestOwnerModuleName(moduleName) ?: return false
        return modules.containsKey(ownerModuleName)
    }

    companion object {
        private const val MAX_REPLAY_RECORDS = 3
        private const val THIRTY_DAYS_MS = 30L * 24 * 60 * 60 * 1000
        private val GRADLE_ANDROID_TEST_ASSEMBLE_TASK = Regex("assemble[A-Za-z0-9_]*AndroidTest")
        private val gson = GsonBuilder().setPrettyPrinting().create()

        fun resolveGitProjectInfo(projectDir: File): GitProjectInfo {
            return resolveGitProjectInfo(projectDir, GitManager.createGitManagerAndTrySearchParent(projectDir))
        }

        fun resolveGitProjectInfo(projectDir: File, gitManager: IGitManager): GitProjectInfo {
            if (!gitManager.hasInitGit) {
                return GitProjectInfo(projectDir.name, projectDir.absolutePath)
            }
            val remoteUrl = gitManager.originRemoteUrl?.takeIf { it.trim().isNotEmpty() }
                ?: gitManager.remoteUrls.firstOrNull { it.trim().isNotEmpty() }
            val projectKey = remoteUrl?.trim()?.takeIf { it.isNotEmpty() } ?: projectDir.absolutePath
            val projectName = gitManager.name?.takeIf { it.isNotBlank() } ?: projectDir.name
            return GitProjectInfo(projectName, projectKey)
        }

        private fun sha256(value: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }

        private fun sanitizeFileName(value: String): String {
            return value.replace(Regex("[^A-Za-z0-9._-]"), "_")
        }
    }
}

private data class LibraryTestApkBuildHistoryDataDto(
    val version: Int?,
    val projectKey: String?,
    val updatedAt: Long?,
    val records: List<LibraryTestApkBuildRecordDto?>?,
) {
    fun normalize(currentProjectKey: String): LibraryTestApkBuildHistoryData {
        return LibraryTestApkBuildHistoryData(
            version = version ?: 1,
            projectKey = projectKey ?: currentProjectKey,
            updatedAt = updatedAt ?: 0L,
            records = records.orEmpty().mapNotNull { it?.normalize() },
        )
    }
}

private data class LibraryTestApkBuildRecordDto(
    val moduleName: String?,
    val buildVariant: String?,
    val compileCommand: String?,
    val compiledAt: Long?,
    val apkPath: String?,
    val outputApkPattern: String?,
) {
    fun normalize(): LibraryTestApkBuildRecord? {
        val moduleName = moduleName?.takeIf { it.isNotBlank() } ?: return null
        val buildVariant = buildVariant?.takeIf { it.isNotBlank() } ?: return null
        val compileCommand = compileCommand?.takeIf { it.isNotBlank() } ?: return null
        val outputApkPattern = outputApkPattern?.takeIf { it.isNotBlank() } ?: return null
        return LibraryTestApkBuildRecord(
            moduleName = moduleName,
            buildVariant = buildVariant,
            compileCommand = compileCommand,
            compiledAt = compiledAt ?: 0L,
            apkPath = apkPath.orEmpty(),
            outputApkPattern = outputApkPattern,
        )
    }
}

data class LibraryTestApkBuildHistoryData(
    val version: Int = 1,
    val projectKey: String,
    val updatedAt: Long = 0L,
    val records: List<LibraryTestApkBuildRecord> = emptyList(),
)

data class LibraryTestApkBuildRecord(
    val moduleName: String,
    val buildVariant: String,
    val compileCommand: String,
    val compiledAt: Long,
    val apkPath: String,
    val outputApkPattern: String,
)

data class LibraryTestApkBuildReplayRecord(
    val gradleTask: String,
    val outputApkPattern: String,
    val source: LibraryTestApkBuildRecord,
)

data class GitProjectInfo(
    val projectName: String,
    val projectKey: String,
)
