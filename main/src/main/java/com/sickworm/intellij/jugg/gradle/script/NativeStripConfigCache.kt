package com.sickworm.intellij.jugg.gradle.script

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Strip configuration of one APK owner variant: keep patterns plus the per-ABI strip executable. */
class NativeStripConfig(
    val keepDebugSymbols: List<String>,
    /** An ABI without an entry has no strip tool and is packaged as is, following the AGP contract. */
    val stripExecutables: Map<String, File>
)

/** One APK owner variant as read from its `strip<Variant>DebugSymbols` task. */
class NativeStripConfigEntry(
    val moduleRootDir: File,
    val variant: String,
    val keepDebugSymbols: List<String>,
    val stripExecutables: Map<String, File>
)

/**
 * Local, self-contained cache of the APK owner strip configuration.
 *
 * A Jugg external invocation may run with Gradle configuration on demand, where the APK owner is
 * never configured, so its `strip<Variant>DebugSymbols` task cannot be read. A normal Gradle build
 * publishes the configuration here instead, together with a copy of every strip executable, so a
 * baseline copied to another CI worker with a different NDK path still resolves its strip tools.
 */
class NativeStripConfigCache(private val rootDir: File) {

    private val configFileName = "config.json"
    private val toolsDirName = "tools"

    /**
     * Publishes [entries] as the current configuration. Every tool copy is written before the
     * configuration is replaced atomically, so a published entry never references a missing tool.
     * The previous configuration is fully replaced: an owner whose strip configuration could not be
     * read this round must not keep a tool cached from an older AGP or NDK.
     */
    fun write(entries: List<NativeStripConfigEntry>) {
        val toolsDir = File(rootDir, toolsDirName)
        val referencedTools = mutableSetOf<String>()
        val publishedEntries = entries.map { entry ->
            val executables = entry.stripExecutables.mapValues { (_, tool) ->
                val backupPath = backupTool(toolsDir, tool)
                if (backupPath != null) {
                    referencedTools.add(backupPath)
                }
                mutableMapOf<String, Any>("sourcePath" to tool.absolutePath).apply {
                    if (backupPath != null) {
                        put("backupPath", backupPath)
                    }
                }
            }
            mapOf<String, Any>(
                "moduleRootDir" to entry.moduleRootDir.absoluteFile.normalize().path,
                "variant" to entry.variant,
                "keepDebugSymbols" to entry.keepDebugSymbols,
                "stripExecutables" to executables
            )
        }
        val configFile = File(rootDir, configFileName)
        configFile.parentFile.mkdirs()
        val tempFile = File(rootDir, configFileName + ".tmp")
        tempFile.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(mapOf("entries" to publishedEntries))))
        publishAtomically(tempFile, configFile)
        deleteUnreferencedTools(toolsDir, referencedTools)
    }

    /**
     * Returns the cached configuration of exactly one `moduleRootDir` + `variant`, or null when the
     * cache is absent, malformed or describes a tool that is no longer usable. Callers degrade with a
     * single live read, so an unusable tool is never reported as "no strip tool for this ABI".
     */
    fun read(moduleRootDir: File, variant: String): NativeStripConfig? {
        val configFile = File(rootDir, configFileName)
        if (!configFile.isFile) {
            println("Jugg: native strip cache is absent: $configFile")
            return null
        }
        val root = try {
            JsonSlurper().parse(configFile) as? Map<*, *>
        } catch (e: Throwable) {
            println("Jugg: native strip cache $configFile can not be parsed: $e")
            return null
        }
        val entries = root?.get("entries") as? List<*> ?: run {
            println("Jugg: native strip cache $configFile has no entry list")
            return null
        }
        val expectedRoot = moduleRootDir.absoluteFile.normalize().path
        val matched = entries.filter { entry ->
            val map = entry as? Map<*, *> ?: return@filter false
            map["moduleRootDir"] == expectedRoot && map["variant"] == variant
        }
        if (matched.size != 1) {
            // A missing entry is a plain cache miss; more than one is a corrupt cache, and neither may
            // be resolved by picking one of them.
            println("Jugg: native strip cache matched ${matched.size} entries for $expectedRoot:$variant")
            return null
        }
        return readEntry(matched.single() as Map<*, *>, expectedRoot, variant)
    }

    @Suppress("UNCHECKED_CAST")
    private fun readEntry(entry: Map<*, *>, moduleRootDir: String, variant: String): NativeStripConfig? {
        val keepDebugSymbols = (entry["keepDebugSymbols"] as? List<*>)?.map {
            it as? String ?: return invalidEntry(moduleRootDir, variant, "keepDebugSymbols")
        } ?: return invalidEntry(moduleRootDir, variant, "keepDebugSymbols")
        val tools = entry["stripExecutables"] as? Map<*, *>
            ?: return invalidEntry(moduleRootDir, variant, "stripExecutables")
        val stripExecutables = mutableMapOf<String, File>()
        for ((abi, value) in tools) {
            val abiName = abi as? String ?: return invalidEntry(moduleRootDir, variant, "stripExecutables")
            val tool = value as? Map<*, *> ?: return invalidEntry(moduleRootDir, variant, abiName)
            stripExecutables[abiName] = resolveTool(tool)
                ?: return invalidEntry(moduleRootDir, variant, abiName)
        }
        return NativeStripConfig(keepDebugSymbols, stripExecutables)
    }

    private fun invalidEntry(moduleRootDir: String, variant: String, field: String): NativeStripConfig? {
        println("Jugg: native strip cache entry $moduleRootDir:$variant has an invalid $field")
        return null
    }

    /**
     * Resolves one recorded tool. The backup is preferred because it travels with the baseline; the
     * original build machine path is only a local fallback and is useless on another CI worker.
     */
    private fun resolveTool(tool: Map<*, *>): File? {
        val backupPath = tool["backupPath"] as? String
        if (backupPath != null) {
            val backup = resolveBackupPath(backupPath)
            if (backup != null && isUsableTool(backup)) {
                return backup
            }
        }
        val sourcePath = tool["sourcePath"] as? String
        if (sourcePath != null && isUsableTool(File(sourcePath))) {
            if (backupPath != null) {
                println("Jugg: native strip cache backup $backupPath is unusable, using $sourcePath")
            }
            return File(sourcePath)
        }
        println("Jugg: native strip cache tool is unusable, backup: $backupPath, source: $sourcePath")
        return null
    }

    /** Resolves a recorded relative backup path, rejecting absolute paths and directory traversal. */
    private fun resolveBackupPath(backupPath: String): File? {
        if (backupPath.isEmpty() || File(backupPath).isAbsolute) {
            println("Jugg: native strip cache backup path $backupPath is not a safe relative path")
            return null
        }
        return try {
            val root = rootDir.canonicalFile
            val file = File(rootDir, backupPath).canonicalFile
            if (file.path.startsWith(root.path + File.separator)) file else {
                println("Jugg: native strip cache backup path $backupPath escapes $root")
                null
            }
        } catch (e: Throwable) {
            println("Jugg: native strip cache backup path $backupPath can not be resolved: $e")
            null
        }
    }

    private fun isUsableTool(file: File): Boolean = file.isFile && file.canRead() && file.canExecute()

    /** Copies one strip executable into the cache and returns its cache relative path. */
    private fun backupTool(toolsDir: File, tool: File): String? {
        val relativePath = "${toolsDirName}/${toolFingerprint(tool)}/${tool.name}"
        val target = File(rootDir, relativePath)
        try {
            target.parentFile.mkdirs()
            Files.copy(
                tool.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.COPY_ATTRIBUTES
            )
            // The tool must stay executable for the worker that consumes the copied baseline.
            if (File.separatorChar != '\\') {
                target.setExecutable(true, false)
            }
        } catch (e: Throwable) {
            println("Jugg: native strip tool $tool can not be backed up: $e")
            return null
        }
        if (!isUsableTool(target)) {
            println("Jugg: native strip tool backup $target is not usable")
            return null
        }
        return relativePath
    }

    /** Stable directory name of one strip executable, so shared tools are backed up only once. */
    private fun toolFingerprint(tool: File): String {
        val path = try {
            tool.canonicalPath
        } catch (e: Throwable) {
            tool.absolutePath
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(path.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    /** Best-effort removal of tool copies the published configuration no longer references. */
    private fun deleteUnreferencedTools(toolsDir: File, referencedTools: Set<String>) {
        toolsDir.listFiles().orEmpty().forEach { child ->
            val prefix = "${toolsDirName}/${child.name}/"
            if (referencedTools.none { it.startsWith(prefix) } && !child.deleteRecursively()) {
                println("Jugg: stale native strip tool ${child.path} can not be removed")
            }
        }
    }

    private fun publishAtomically(tempFile: File, outputFile: File) {
        try {
            Files.move(
                tempFile.toPath(),
                outputFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tempFile.toPath(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
