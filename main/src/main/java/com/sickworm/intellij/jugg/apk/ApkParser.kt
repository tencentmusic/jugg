package com.sickworm.intellij.jugg.apk

import com.android.tools.deployer.ApkParser as ApkParserAdt
import com.android.tools.idea.run.ApkInfo
import com.googlecode.d2j.node.DexFileNode
import com.googlecode.d2j.reader.BaseDexFileReader
import com.googlecode.d2j.reader.DexFileReader
import com.sickworm.intellij.jugg.compiler.ClassNode
import com.sickworm.intellij.jugg.compiler.ParsedApk
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.zip.ZipFile

/** Used to parse everything I need in Apk */

@OptIn(ExperimentalCoroutinesApi::class)
class ApkParser: CoroutineScope by CoroutineScope(
    Dispatchers.IO.limitedParallelism(
        Runtime.getRuntime().availableProcessors() / 2
    )
) {

    fun parse(apkInfo: ApkInfo, isSkipCode: Boolean): ParsedApk {
        val apkFile = apkInfo.files.first().apkFile
        val classes = ConcurrentHashMap<String, ClassNode>()
        val jobs = mutableListOf<Job>()

        ZipFile(apkFile).use { zipFile ->
            zipFile.entries().asIterator().forEach {
                val entryName = it.name
                if (entryName.startsWith("classes") && entryName.endsWith(".dex")) {
                    val job = launch {
                        val dexBytes = zipFile.getInputStream(it).readBytes()
                        parseCode(dexBytes, isSkipCode, classes)
                    }
                    jobs.add(job)
                }
            }
            runBlocking {
                jobs.joinAll()
            }
        }
        val overlays = parseOverlays(apkInfo.files.first().apkFile)
        return ParsedApk(apkInfo, classes, overlays)
    }

    private fun parseCode(bytes: ByteArray, isSkipCode: Boolean, map: MutableMap<String, ClassNode> = mutableMapOf()): Map<String, ClassNode> {
        val reader: BaseDexFileReader = DexFileReader(bytes)
        val visitor = DexFileNodeCollector(map)
        val flag = if (isSkipCode) DexFileReader.SKIP_CODE else 0
        reader.accept(visitor, flag)
        return visitor.getClasses()
    }

    private fun parseOverlays(apkFile: File): Map<String, JuggFileInfo> {
        val overlayFiles = mutableMapOf<String, JuggFileInfo>()
        val apk = ApkParserAdt().parsePaths(listOf(apkFile.absolutePath)).first()
        for (entry in apk.apkEntries.values) {
            if (!entry.name.endsWith(".dex")) {
                overlayFiles[entry.name] = JuggFileInfo(entry.name, entry.checksum)
            }
        }

        return overlayFiles
    }

    fun parseDex(dexByteCode: ByteArray): Map<String, ClassNode> {
        val reader: BaseDexFileReader = DexFileReader(dexByteCode)
        val visitor = DexFileNode()
        reader.accept(visitor, DexFileReader.SKIP_CODE)

        val classes = mutableMapOf<String, ClassNode>()
        visitor.clzs.forEach {
            val classNode = ClassNode(it)
            classes[classNode.className] = classNode
        }
        return classes
    }
}

data class JuggFileInfo(
    val name: String,
    val checksum: Long
)