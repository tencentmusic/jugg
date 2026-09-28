package com.sickworm.intellij.jugg.logger

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.logging.Formatter
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import java.util.logging.SimpleFormatter

/**
 * Logger logs to jugg/log.
 */
class FileLogger(
    val dir: File,
    private val limitBytes: Int = DEFAULT_LIMIT_BYTES,
    private val fileCount: Int = DEFAULT_FILE_COUNT,
    private var patternName: String = createPatternName(),
    val logger: Logger = createLogger(dir, patternName, limitBytes, fileCount),
) {

    private var legacyDir: File? = null

    internal fun linkLegacyLogDir(legacyDir: File) {
        this.legacyDir = legacyDir
        ensureLegacyLogDirLink()
    }

    fun recreateIfDeleted() {
        ensureLegacyLogDirLink()
        if (!dir.exists()) {
            dir.mkdirs()
            resetLatestCompileLog()
            return
        }
        if (!currentMainLogFile().exists()) {
            resetLatestCompileLog()
        }
    }

    fun resetLatestCompileLog() {
        val previousMainLogFile = currentMainLogFile()
        closeHandlers()
        updateLastLatestLogFile(dir, previousMainLogFile)

        patternName = createPatternName()
        logger.addHandler(createFileHandler(dir, patternName, limitBytes, fileCount))
        removeOldLogFiles(dir)
    }

    fun dispose() {
        closeHandlers()
    }

    private fun currentMainLogFile(): File {
        return File(dir, patternName.replace("%g", "0"))
    }

    private fun closeHandlers() {
        logger.handlers.clone().forEach {
            logger.removeHandler(it)
            it.close()
        }
    }

    private fun ensureLegacyLogDirLink() {
        val legacyDir = legacyDir ?: return
        try {
            val legacyPath = legacyDir.toPath()
            restorePendingLegacyLogDir(legacyPath)
            if (Files.isSymbolicLink(legacyPath)) {
                replaceLegacyLogDirLink(legacyDir)
                return
            }
            if (Files.isDirectory(legacyPath, LinkOption.NOFOLLOW_LINKS)) {
                migrateLegacyLogDir(legacyDir)
                return
            }
            if (Files.exists(legacyPath, LinkOption.NOFOLLOW_LINKS)) {
                return
            }
            createLegacyLogDirLink(legacyDir)
        } catch (e: Exception) {
            logger.log(Level.FINE, "Prepare legacy log directory link failed: $legacyDir", e)
        }
    }

    private fun replaceLegacyLogDirLink(legacyDir: File) {
        val legacyPath = legacyDir.toPath()
        if (runCatching { legacyPath.toRealPath() == dir.toPath().toRealPath() }.getOrDefault(false)) {
            return
        }
        val originalTarget = Files.readSymbolicLink(legacyPath)
        Files.delete(legacyPath)
        try {
            createLegacyLogDirLink(legacyDir)
        } catch (e: Exception) {
            runCatching { Files.createSymbolicLink(legacyPath, originalTarget) }
                .exceptionOrNull()?.let(e::addSuppressed)
            throw e
        }
    }

    private fun migrateLegacyLogDir(legacyDir: File) {
        val legacyPath = legacyDir.toPath()
        val backupPath = legacyPath.resolveSibling("${legacyDir.name}.migrating-${UUID.randomUUID()}")
        val migratedPath = legacyPath.resolveSibling("${legacyDir.name}.migrated-${UUID.randomUUID()}")
        val copiedPaths = mutableListOf<Path>()
        try {
            Files.list(legacyPath).use { children ->
                children.filter { !isLatestLogLink(it) }.sorted().forEach { source ->
                    val target = availableMigrationTarget(source.fileName.toString())
                    copyLegacyLogPath(source, target, copiedPaths)
                }
            }
            Files.move(legacyPath, backupPath)
            createLegacyLogDirLink(legacyDir)
            Files.move(backupPath, migratedPath)
            val isCleaned = runCatching { migratedPath.toFile().deleteRecursively() }.getOrDefault(false)
            if (!isCleaned) {
                logger.log(Level.FINE, "Clean migrated legacy log directory failed: $migratedPath")
            }
        } catch (e: Exception) {
            rollbackLegacyLogDirMigration(legacyPath, backupPath, copiedPaths, e)
            throw e
        }
    }

    private fun rollbackLegacyLogDirMigration(
        legacyPath: Path,
        backupPath: Path,
        copiedPaths: List<Path>,
        failure: Exception,
    ) {
        var rollbackFailure: Throwable? = null
        fun rollback(action: () -> Unit) {
            runCatching(action).exceptionOrNull()?.let {
                rollbackFailure?.addSuppressed(it) ?: run { rollbackFailure = it }
            }
        }
        rollback {
            if (Files.isSymbolicLink(legacyPath)) {
                Files.delete(legacyPath)
            }
        }
        rollback {
            if (!Files.exists(legacyPath, LinkOption.NOFOLLOW_LINKS) && Files.exists(backupPath)) {
                Files.move(backupPath, legacyPath)
            }
        }
        copiedPaths.asReversed().forEach { path -> rollback { Files.deleteIfExists(path) } }
        rollbackFailure?.let(failure::addSuppressed)
    }

    private fun copyLegacyLogPath(source: Path, target: Path, copiedPaths: MutableList<Path>) {
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            copiedPaths.add(target)
            Files.copy(source, target, LinkOption.NOFOLLOW_LINKS)
            return
        }
        Files.createDirectory(target)
        copiedPaths.add(target)
        Files.list(source).use { children ->
            children.sorted().forEach { child ->
                copyLegacyLogPath(child, target.resolve(child.fileName), copiedPaths)
            }
        }
    }

    private fun restorePendingLegacyLogDir(legacyPath: Path) {
        if (Files.exists(legacyPath, LinkOption.NOFOLLOW_LINKS)) {
            return
        }
        if (!Files.exists(legacyPath.parent)) {
            return
        }
        val prefix = "${legacyPath.fileName}.migrating-"
        val backups = Files.list(legacyPath.parent).use { paths ->
            paths.filter {
                it.fileName.toString().startsWith(prefix) && Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS)
            }.toList()
        }
        if (backups.size > 1) {
            throw IllegalStateException("Multiple legacy log directory backups found: $backups")
        }
        backups.singleOrNull()?.let { Files.move(it, legacyPath) }
    }

    private fun availableMigrationTarget(fileName: String): Path {
        var target = dir.toPath().resolve(fileName)
        var index = 1
        while (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            target = dir.toPath().resolve("$fileName.legacy-${index++}")
        }
        return target
    }

    private fun isLatestLogLink(path: Path): Boolean {
        return path.fileName.toString() == LATEST_LOG_NAME || path.fileName.toString() == LAST_LATEST_LOG_NAME
    }

    private fun createLegacyLogDirLink(legacyDir: File) {
        legacyDir.parentFile?.let { Files.createDirectories(it.toPath()) }
        Files.createDirectories(dir.toPath())
        Files.createSymbolicLink(legacyDir.toPath(), dir.toPath())
    }

    companion object {

        private const val LATEST_LOG_NAME = "compile_latest.log"
        private const val LAST_LATEST_LOG_NAME = "compile_latest-1.log"
        private const val DEFAULT_LIMIT_BYTES = 50 * 1024 * 1024
        private const val DEFAULT_FILE_COUNT = 2
        private const val MAX_LOG_FILE_AMOUNT = 10

        @JvmField
        var isCreateLastLogLinkFile: Boolean = true

        private fun createPatternName(): String {
            return "compile_" + SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date()) + ".%g.log"
        }

        private fun createLogger(dir: File, patternName: String, limitBytes: Int, fileCount: Int): Logger {
            dir.mkdirs()
            if (isCreateLastLogLinkFile) {
                findLatestMainLogFile(dir)?.let { updateLastLatestLogFile(dir, it) }
            }
            return Logger.getLogger(dir.absolutePath).also {
                it.useParentHandlers = false
                it.level = Level.ALL
                if (it.handlers.isNotEmpty()) {
                    it.handlers.clone().forEach { handler ->
                        it.removeHandler(handler)
                        handler.close()
                    }
                }
                it.addHandler(createFileHandler(dir, patternName, limitBytes, fileCount))
                removeOldLogFiles(dir)
            }
        }

        private fun createFileHandler(
            dir: File,
            patternName: String,
            limitBytes: Int,
            fileCount: Int,
        ): NoLockRotatingFileHandler {
            val mainLogFile = File(dir, patternName.replace("%g", "0"))
            val loggerHandler = NoLockRotatingFileHandler(
                pattern = File(dir, patternName).absolutePath,
                limitBytes = limitBytes,
                fileCount = fileCount,
                onActiveFileChanged = { updateLatestLogFile(dir, it) },
            )
            loggerHandler.level = Level.ALL
            loggerHandler.formatter = createFormatter()
            updateLatestLogFile(dir, mainLogFile)
            return loggerHandler
        }

        private fun createFormatter(): Formatter {
            return object : SimpleFormatter() {

                private val format: String = "[%1\$tF %1\$tT.%2\$03d] [%3$-7s] %4\$s%n"

                override fun format(lr: LogRecord): String {
                    val string = String.format(
                        Locale.US,
                        format,
                        Date(lr.millis),
                        lr.millis % 1000,
                        lr.level.name,
                        lr.message,
                    )
                    val outputStream = ByteArrayOutputStream()
                    lr.thrown?.printStackTrace(PrintStream(outputStream))
                    return string + outputStream.toString()
                }
            }
        }

        private fun updateLatestLogFile(dir: File, targetFile: File) {
            createBestEffortLink(File(dir, LATEST_LOG_NAME), targetFile)
        }

        private fun findLatestMainLogFile(dir: File): File? {
            val latestLogFile = File(dir, LATEST_LOG_NAME).takeIf { it.exists() } ?: return null
            return dir.listFiles()?.firstOrNull { file ->
                file.isFile && file.name.startsWith("compile_") && file.name.endsWith(".log") &&
                        file.name != LATEST_LOG_NAME && file.name != LAST_LATEST_LOG_NAME &&
                        runCatching { Files.isSameFile(file.toPath(), latestLogFile.toPath()) }.getOrDefault(false)
            }
        }

        private fun updateLastLatestLogFile(dir: File, targetFile: File) {
            if (!isCreateLastLogLinkFile) {
                Files.deleteIfExists(File(dir, LAST_LATEST_LOG_NAME).toPath())
                return
            }
            if (!targetFile.exists()) {
                return
            }
            createBestEffortLink(File(dir, LAST_LATEST_LOG_NAME), targetFile)
        }

        private fun createBestEffortLink(linkFile: File, targetFile: File) {
            try {
                Files.deleteIfExists(linkFile.toPath())
            } catch (_: Exception) {
                return
            }

            if (tryCreateSymbolicLink(linkFile, targetFile)) {
                return
            }
            tryCreateHardLink(linkFile, targetFile)
        }

        private fun tryCreateSymbolicLink(linkFile: File, targetFile: File): Boolean {
            return try {
                linkFile.parentFile?.mkdirs()
                val relativePath = relativeTarget(linkFile, targetFile)
                Files.createSymbolicLink(linkFile.toPath(), Path.of("./$relativePath"))
                true
            } catch (_: Exception) {
                false
            }
        }

        private fun tryCreateHardLink(linkFile: File, targetFile: File) {
            if (!targetFile.exists()) {
                return
            }
            try {
                Files.createLink(linkFile.toPath(), targetFile.toPath())
            } catch (_: Exception) {
            }
        }

        private fun relativeTarget(linkFile: File, targetFile: File): String {
            return linkFile.toPath().parent.relativize(targetFile.toPath()).toString()
        }

        private fun removeOldLogFiles(dir: File) {
            val files = dir.listFiles()
                ?.filter { it.name.startsWith("compile_") && it.name.endsWith(".log") }
                ?.filter { it.name != LATEST_LOG_NAME && it.name != LAST_LATEST_LOG_NAME }
                ?.sortedBy { it.lastModified() }
                .orEmpty()

            if (files.size <= MAX_LOG_FILE_AMOUNT) {
                return
            }

            files.take(files.size - MAX_LOG_FILE_AMOUNT).forEach { it.delete() }
        }
    }
}
