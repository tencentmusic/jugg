package com.sickworm.intellij.jugg.deploy.data

import com.sickworm.intellij.jugg.apk.ApkInfo
import com.googlecode.d2j.reader.BaseDexFileReader
import com.googlecode.d2j.reader.DexFileReader
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.deploy.run.ClassDeployItem
import com.sickworm.intellij.jugg.deploy.run.DeployItem
import com.sickworm.intellij.jugg.platform.PlatformApi
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32
import java.util.zip.ZipFile

/**
 * ApkParser parses APK dex/overlay entries into class graph and reference indexes used by deploy impact analysis.
 */

@OptIn(ExperimentalCoroutinesApi::class)
class ApkParser: CoroutineScope by CoroutineScope(
    Dispatchers.IO.limitedParallelism(
        (Runtime.getRuntime().availableProcessors() / 3).coerceAtLeast(2)
    )
) {

    fun parse(diffs: List<ParsedApkDiffResult>): List<ParsedApk> {
        val result = diffs.map {
            parse(it.apkFile, it.includeEntries)
        }
        ClassStringPool.clear()
        return result
    }

    fun parse(apkFile: File, includeEntries: ApkEntries? = null): ParsedApk {
        val classes = ConcurrentHashMap<String, ClassNode>()
        val methodRefs = ConcurrentHashMap<MethodNode, MutableList<String>>()
        val fieldRefs = ConcurrentHashMap<FieldNode, MutableList<String>>()
        val subclassRefs = ConcurrentHashMap<String, MutableList<String>>()
        val defaultMethodInvokeRefs = ConcurrentHashMap<String, MutableList<String>>()
        parseDex(apkFile, classes, methodRefs, fieldRefs, subclassRefs, defaultMethodInvokeRefs, includeEntries)

        val apkOverlays = includeEntries ?: parseEntries(apkFile)
        return ParsedApk(apkFile, classes, apkOverlays.dexFiles, apkOverlays.overlayFiles, methodRefs, fieldRefs, subclassRefs)
    }

    fun parseDex(deployItems: List<DeployItem>, isSkipOfficialClass: Boolean = true): ParsedDex {
        val methodRefs = ConcurrentHashMap<MethodNode, MutableList<String>>()
        val fieldRefs = ConcurrentHashMap<FieldNode, MutableList<String>>()
        val subclassRefs = ConcurrentHashMap<String, MutableList<String>>()
        val defaultMethodInvokeRefs = ConcurrentHashMap<String, MutableList<String>>()
        val classDeployItem = deployItems.map {
            val classes = ConcurrentHashMap<String, ClassNode>()
            parseDex(
                ClassNode.JUGG_DEPLOYED_DEX_FILE_NAME,
                it.content,
                classes,
                methodRefs,
                fieldRefs,
                subclassRefs,
                defaultMethodInvokeRefs,
                false,
                isSkipOfficialClass,
            )
            ClassDeployItem(it, classes.values.toList())
        }
        ClassStringPool.clear()
        return ParsedDex(classDeployItem, methodRefs, fieldRefs, subclassRefs)
    }

    fun parseDexFiles(dexFiles: List<File>): ParsedDex {
        val deployItems = dexFiles.map {
            val content = it.readBytes()
            val crc = CRC32().run {
                reset()
                update(content)
                value
            }
            DeployItem(
                it.name,
                CompileOutput.Type.Dex,
                crc,
                content,
                DeployItem.FLAG_CLASS,
            )
        }
        return parseDex(deployItems)
    }

    private fun parseDex(apkFile: File,
                 classes: ConcurrentHashMap<String, ClassNode>,
                 methodRefs: ConcurrentHashMap<MethodNode, MutableList<String>>,
                 fieldRefs: ConcurrentHashMap<FieldNode, MutableList<String>>,
                 subclassRefs: ConcurrentHashMap<String, MutableList<String>>,
                 defaultMethodInvokeRefs: ConcurrentHashMap<String, MutableList<String>>,
                 includeEntries: ApkEntries?,
    ) {

        var includeEntriesSet: MutableSet<String>? = null
        if (includeEntries != null) {
            includeEntriesSet = mutableSetOf()
            includeEntries.dexFiles.forEach {
                includeEntriesSet.add(it.key)
            }
            includeEntries.overlayFiles.forEach {
                includeEntriesSet.add(it.key)
            }
        }

        val jobs = mutableListOf<Job>()
        ZipFile(apkFile).use { zipFile ->
            zipFile.entries().asIterator().forEach {
                val entryName = it.name
                if (includeEntriesSet != null && !includeEntriesSet.contains(entryName)) {
                    return@forEach
                }
                if (entryName.startsWith("classes") && entryName.endsWith(".dex")) {
                    val job = launch {
                        val dexBytes = zipFile.getInputStream(it).readBytes()
                        parseDex(entryName, dexBytes, classes, methodRefs, fieldRefs, subclassRefs, defaultMethodInvokeRefs)
                    }
                    jobs.add(job)
                }
            }
            runBlocking {
                jobs.joinAll()
            }
        }
    }

    private fun parseDex(dexFileName: String, bytes: ByteArray,
                         classes: ConcurrentHashMap<String, ClassNode>,
                         methodRefs: ConcurrentHashMap<MethodNode, MutableList<String>>,
                         fieldRefs: ConcurrentHashMap<FieldNode, MutableList<String>>,
                         subclassRefs: ConcurrentHashMap<String, MutableList<String>>,
                         defaultMethodInvokeRefs: ConcurrentHashMap<String, MutableList<String>>,
                         isSkipCode: Boolean = false,
                         isSkipOfficialClass: Boolean = true,
    ) {
        val reader: BaseDexFileReader = DexFileReader(bytes)
        val visitor = DexFileNodeCollector(dexFileName, classes, methodRefs, fieldRefs, subclassRefs, defaultMethodInvokeRefs, isSkipOfficialClass)
        val flag = if (isSkipCode) DexFileReader.SKIP_CODE else 0
        reader.accept(visitor, flag)
    }

    fun parseEntries(apkFile: File): ApkEntries {
        val dexFiles = mutableMapOf<String, JuggFileInfo>()
        val overlayFiles = mutableMapOf<String, JuggFileInfo>()
        parseEntries(apkFile, dexFiles, overlayFiles)
        return ApkEntries(apkFile, dexFiles, overlayFiles)
    }

    private fun parseEntries(apkFile: File,
                             dexFiles: MutableMap<String, JuggFileInfo>,
                             overlayFiles: MutableMap<String, JuggFileInfo>) {
        ZipFile(apkFile).use { zipFile ->
            zipFile.entries().asIterator().forEach { entry ->
                if (entry.name.isDexEntry) {
                    dexFiles[entry.name] = JuggFileInfo(entry.name, entry.crc)
                } else if (entry.name.isResEntry || entry.name.isAssetEntry) {
                    overlayFiles[entry.name] = JuggFileInfo(entry.name, entry.crc)
                }
            }
        }
    }
}

/**
 * JuggFileInfo carries name and checksum.
 */
data class JuggFileInfo(
    val name: String,
    val checksum: Long
) {
    val isDex: Boolean get() = name.isDexEntry

    val isRes: Boolean get() = name.isResEntry

    val isAsset: Boolean get() = name.isAssetEntry
}

val String.isDexEntry get() = this.startsWith("classes") && this.endsWith(".dex")
val String.isResEntry get() = this.startsWith("res/") || this == "resources.arsc" // use '/' for it's the path in zip
val String.isAssetEntry get() = this.startsWith("assets/") // use '/' for it's the path in zip

/**
 * ApkEntries carries apkFile, dexFiles, and overlayFiles.
 */
data class ApkEntries(
    val apkFile: File,
    val dexFiles: Map<String, JuggFileInfo>,
    val overlayFiles: Map<String, JuggFileInfo>,
)

/**
 * ParsedDex carries classDeployItems, methodRefs, fieldRefs, and subclassRefs.
 */
data class ParsedDex(
    val classDeployItems: List<ClassDeployItem>,
    val methodRefs: Map<MethodNode, List<String>>,
    val fieldRefs: Map<FieldNode, List<String>>,
    val subclassRefs: Map<String, List<String>>,
) {

    companion object {
        val EMPTY = ParsedDex(emptyList(), emptyMap(), emptyMap(), emptyMap())
    }
}
