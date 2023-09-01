package com.sickworm.intellij.jugg.deploy.data

import com.android.tools.deployer.ApkParser as ApkParserAdt
import com.android.tools.idea.run.ApkInfo
import com.googlecode.d2j.reader.BaseDexFileReader
import com.googlecode.d2j.reader.DexFileReader
import com.jetbrains.rd.util.first
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.deploy.run.ClassDeployItem
import com.sickworm.intellij.jugg.deploy.run.DeployItem
import com.sickworm.intellij.jugg.project.JuggInternalException
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/** Used to parse everything I need in Apk */

@OptIn(ExperimentalCoroutinesApi::class)
class ApkParser: CoroutineScope by CoroutineScope(
    Dispatchers.IO.limitedParallelism(
        Runtime.getRuntime().availableProcessors() / 2
    )
) {

    fun parse(apkInfo: ApkInfo, includeEntries: ApkEntries? = null): ParsedApk {
        val parsedApks = apkInfo.files.map {
            val apkFile = it.apkFile
            parse(apkInfo, apkFile, includeEntries)
        }
        if (parsedApks.isEmpty()) {
            return ParsedApk(apkInfo, emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap())
        }
        if (parsedApks.size == 1) {
            return parsedApks[0]
        }
        val dexFiles = mutableMapOf<String, JuggFileInfo>()
        val overlayFiles = mutableMapOf<String, JuggFileInfo>()
        val classes = mutableMapOf<String, ClassNode>()
        val methodRefs = mutableMapOf<MethodNode, List<String>>()
        val fieldRefs = mutableMapOf<FieldNode, List<String>>()
        parsedApks.forEach {
            classes.putAll(it.classes)
            dexFiles.putAll(it.dexFiles)
            overlayFiles.putAll(it.overlayFiles)
            methodRefs.putAll(it.methodRefs)
            fieldRefs.putAll(it.fieldRefs)
        }
        return ParsedApk(apkInfo, classes, dexFiles, overlayFiles, methodRefs, fieldRefs)

    }

    private fun parse(apkInfo: ApkInfo, apkFile: File, includeEntries: ApkEntries?): ParsedApk {
        val classes = ConcurrentHashMap<String, ClassNode>()
        val methodRefs = ConcurrentHashMap<MethodNode, MutableList<String>>()
        val fieldRefs = ConcurrentHashMap<FieldNode, MutableList<String>>()
        parseDex(apkFile, classes, methodRefs, fieldRefs, includeEntries)

        val apkOverlays = includeEntries ?: parseEntries(apkInfo)
        return ParsedApk(apkInfo, classes, apkOverlays.dexFiles, apkOverlays.overlayFiles, methodRefs, fieldRefs)
    }

    fun parseDex(deployItems: List<DeployItem>): ParsedDex {
        val methodRefs = ConcurrentHashMap<MethodNode, MutableList<String>>()
        val fieldRefs = ConcurrentHashMap<FieldNode, MutableList<String>>()
        val classDeployItem = deployItems.map {
            val classes = ConcurrentHashMap<String, ClassNode>()
            parseDex(
                ClassNode.JUGG_DEPLOYED_DEX_FILE_NAME,
                it.content,
                classes,
                methodRefs,
                fieldRefs,
                false,
            )
            if (classes.size != 1) {
                // it must be only one class in one dex
                throw JuggInternalException.dexFileNotContainsOnlyOneClass(classes.size)
            }
            ClassDeployItem(it, classes.first().value)
        }
        return ParsedDex(classDeployItem, methodRefs, fieldRefs)
    }

    private fun parseDex(apkFile: File,
                 classes: ConcurrentHashMap<String, ClassNode>,
                 methodRefs: ConcurrentHashMap<MethodNode, MutableList<String>>,
                 fieldRefs: ConcurrentHashMap<FieldNode, MutableList<String>>,
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
                        parseDex(entryName, dexBytes, classes, methodRefs, fieldRefs, false)
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
                         @Suppress("SameParameterValue") isSkipCode: Boolean): Map<String, ClassNode> {
        val reader: BaseDexFileReader = DexFileReader(bytes)
        val visitor = DexFileNodeCollector(dexFileName, classes, methodRefs, fieldRefs)
        val flag = if (isSkipCode) DexFileReader.SKIP_CODE else 0
        reader.accept(visitor, flag)
        return visitor.getClasses()
    }

    fun parseEntries(apkInfo: ApkInfo): ApkEntries {
        val dexFiles = mutableMapOf<String, JuggFileInfo>()
        val overlayFiles = mutableMapOf<String, JuggFileInfo>()
        apkInfo.files.forEach {
            parseEntries(it.apkFile, dexFiles, overlayFiles)
        }
        return ApkEntries(apkInfo, dexFiles, overlayFiles)
    }

    private fun parseEntries(apkFile: File,
                             dexFiles: MutableMap<String, JuggFileInfo>,
                             overlayFiles: MutableMap<String, JuggFileInfo>) {
        val apk = ApkParserAdt().parsePaths(listOf(apkFile.absolutePath)).first()
        for (entry in apk.apkEntries.values) {
            if (entry.name.startsWith("classes") && entry.name.endsWith(".dex")) {
                dexFiles[entry.name] = JuggFileInfo(entry.name, entry.checksum)
            } else {
                overlayFiles[entry.name] = JuggFileInfo(entry.name, entry.checksum)
            }
        }
    }
}

data class JuggFileInfo(
    val name: String,
    val checksum: Long
)

data class ApkEntries(
    val apkInfo: ApkInfo,
    val dexFiles: Map<String, JuggFileInfo>,
    val overlayFiles: Map<String, JuggFileInfo>,
)

data class ParsedDex(
    val classDeployItems: List<ClassDeployItem>,
    val methodRefs: Map<MethodNode, MutableList<String>>,
    val fieldRefs: Map<FieldNode, MutableList<String>>,
) {
    companion object {
        val EMPTY = ParsedDex(emptyList(), emptyMap(), emptyMap())
    }
}