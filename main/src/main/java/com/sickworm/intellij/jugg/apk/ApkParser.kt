package com.sickworm.intellij.jugg.apk

import com.android.tools.deployer.ApkParser as ApkParserAdt
import com.android.tools.idea.run.ApkInfo
import com.googlecode.d2j.node.DexFileNode
import com.googlecode.d2j.reader.BaseDexFileReader
import com.googlecode.d2j.reader.DexFileReader
import com.googlecode.d2j.reader.MultiDexFileReader
import com.sickworm.intellij.jugg.compiler.ClassNode
import com.sickworm.intellij.jugg.compiler.ParsedApk
import java.io.File

/** Used to parse everything I need in Apk */
class ApkParser {

    fun parse(apkInfo: ApkInfo, isSkipCode: Boolean): ParsedApk {
        val apkBytes = apkInfo.files.first().apkFile.readBytes()
        val classes = parseCode(apkBytes, isSkipCode)
        val overlays = parseOverlays(apkInfo.files.first().apkFile)
        return ParsedApk(apkInfo, classes, overlays)
    }

    private fun parseCode(bytes: ByteArray, isSkipCode: Boolean): Map<String, ClassNode> {
        val reader: BaseDexFileReader = MultiDexFileReader.open(bytes)
        val visitor = DexFileNode()
        val flag = if (isSkipCode) DexFileReader.SKIP_CODE else 0
        reader.accept(visitor, flag)

        val classes = mutableMapOf<String, ClassNode>()
        visitor.clzs.forEach {
            val classNode = ClassNode(it)
            classes[classNode.className] = classNode
        }

        return classes
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