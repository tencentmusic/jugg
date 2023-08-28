package com.sickworm.intellij.jugg.deploy.data

import com.android.tools.deployer.ApkParser as ApkParserAdt
import com.android.tools.idea.run.ApkInfo
import com.googlecode.d2j.node.DexFileNode
import com.googlecode.d2j.reader.BaseDexFileReader
import com.googlecode.d2j.reader.DexFileReader
import com.sickworm.intellij.jugg.compiler.*
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

    fun parse(apkInfo: ApkInfo): ParsedApk {
        val apkFile = apkInfo.files.first().apkFile
        val classes = ConcurrentHashMap<String, ClassNode>()
        val methodRefs = ConcurrentHashMap<MethodNode, MutableList<String>>()
        val fieldRefs = ConcurrentHashMap<FieldNode, MutableList<String>>()
        val jobs = mutableListOf<Job>()

        ZipFile(apkFile).use { zipFile ->
            zipFile.entries().asIterator().forEach {
                val entryName = it.name
                if (entryName.startsWith("classes") && entryName.endsWith(".dex")) {
                    val job = launch {
                        val dexBytes = zipFile.getInputStream(it).readBytes()
                        parseCode(entryName, dexBytes, classes, methodRefs, fieldRefs, false)
                    }
                    jobs.add(job)
                }
            }
            runBlocking {
                jobs.joinAll()
            }
        }
        ClassStringPool.clear()
        val (dexFiles, overlays) = parseOverlays(apkInfo.files.first().apkFile)

        val finalMethodRefs = methodRefs.filter {
            // The class of the method is not exists in the apk. Maybe in the android.jar. Filter it.
            classes.containsKey(it.key.owner)
        }
        val finalFieldRefs = fieldRefs.filter {
            // The class of the field is not exists in the apk. Maybe in the android.jar. Filter it.
            classes.containsKey(it.key.owner)
        }
        return ParsedApk(apkInfo, classes, dexFiles, overlays, finalMethodRefs, finalFieldRefs)
    }

    private fun parseCode(dexFileName: String, bytes: ByteArray,
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

    private fun parseOverlays(apkFile: File): Pair<Map<String, JuggFileInfo>, Map<String, JuggFileInfo>> {
        val dexFiles = mutableMapOf<String, JuggFileInfo>()
        val overlayFiles = mutableMapOf<String, JuggFileInfo>()
        val apk = ApkParserAdt().parsePaths(listOf(apkFile.absolutePath)).first()
        for (entry in apk.apkEntries.values) {
            if (entry.name.endsWith(".dex")) {
                dexFiles[entry.name] = JuggFileInfo(entry.name, entry.checksum)
            } else {
                overlayFiles[entry.name] = JuggFileInfo(entry.name, entry.checksum)
            }
        }

        return dexFiles to overlayFiles
    }

    fun parseDex(dexByteCode: ByteArray): Map<String, ClassNode> {
        val reader: BaseDexFileReader = DexFileReader(dexByteCode)
        val visitor = DexFileNode()
        reader.accept(visitor, DexFileReader.SKIP_CODE)

        val classes = mutableMapOf<String, ClassNode>()
        visitor.clzs.forEach {
            val classNode = ClassNode(ClassNode.JUGG_DEPLOYED_DEX_FILE_NAME, it)
            classes[classNode.className] = classNode
        }
        return classes
    }
}

data class JuggFileInfo(
    val name: String,
    val checksum: Long
)