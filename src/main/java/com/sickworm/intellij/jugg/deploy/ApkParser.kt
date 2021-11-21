package com.sickworm.intellij.jugg.deploy

import com.android.tools.deployer.ApkParser as ApkParserAdt
import com.android.tools.idea.run.ApkInfo
import com.googlecode.d2j.node.DexFileNode
import com.googlecode.d2j.reader.BaseDexFileReader
import com.googlecode.d2j.reader.DexFileReader
import com.googlecode.d2j.reader.MultiDexFileReader
import com.sickworm.intellij.jugg.compiler.DexClassNodeWrapper
import com.sickworm.intellij.jugg.compiler.ParsedApk

/** Used to parse everything I need in Apk */
class ApkParser {

    fun parse(apkInfo: ApkInfo): ParsedApk {
        val apkBytes = apkInfo.file.readBytes()
        val reader: BaseDexFileReader = MultiDexFileReader.open(apkBytes)
        val visitor = DexFileNode()
        reader.accept(visitor, DexFileReader.SKIP_CODE)

        val classes = mutableMapOf<String, DexClassNodeWrapper>()
        visitor.clzs.forEach {
            val classNode = DexClassNodeWrapper(it)
            classes[classNode.className] = classNode
        }

        val overlayFiles = mutableMapOf<String, JuggFileInfo>()
        val apk = ApkParserAdt().parsePaths(listOf(apkInfo.file.absolutePath)).first()
        for (entry in apk.apkEntries.values) {
            if (!entry.name.endsWith(".dex")) {
                overlayFiles[entry.name] = JuggFileInfo(entry.name, entry.checksum)
            }
        }

        return ParsedApk(apkInfo, classes, overlayFiles)
    }

    fun parseDex(dexByteCode: ByteArray): Map<String, DexClassNodeWrapper> {
        val reader: BaseDexFileReader = DexFileReader(dexByteCode)
        val visitor = DexFileNode()
        reader.accept(visitor, DexFileReader.SKIP_CODE)

        val classes = mutableMapOf<String, DexClassNodeWrapper>()
        visitor.clzs.forEach {
            val classNode = DexClassNodeWrapper(it)
            classes[classNode.className] = classNode
        }
        return classes
    }
}

data class JuggFileInfo(
    val name: String,
    val checksum: Long
)