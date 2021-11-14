package com.sickworm.intellij.jugg.deploy

import com.android.tools.deployer.D8DexSplitter
import com.android.tools.deployer.DexSplitter
import com.android.tools.deployer.ApkParser as ApkParserAdt
import com.android.tools.idea.run.ApkInfo
import com.googlecode.d2j.node.DexFileNode
import com.googlecode.d2j.reader.BaseDexFileReader
import com.googlecode.d2j.reader.DexFileReader
import com.googlecode.d2j.reader.MultiDexFileReader
import com.sickworm.intellij.jugg.compiler.DexClassNodeWrapper
import com.sickworm.intellij.jugg.compiler.ParsedApk

/** Used to parse everything I need in Apk */
class ApkParser(private val apkInfo: ApkInfo) {

    fun parse(): ParsedApk {
        val apkBytes = apkInfo.file.readBytes()
        val reader: BaseDexFileReader = MultiDexFileReader.open(apkBytes)
        val visitor = DexFileNode()
        reader.accept(visitor, DexFileReader.SKIP_CODE)

        val classes = mutableMapOf<String, DexClassNodeWrapper>()
        visitor.clzs.forEach {
            classes[it.className] = DexClassNodeWrapper(it)
        }

        val classFiles = mutableMapOf<String, JuggFileInfo>()
        val overlayFiles = mutableMapOf<String, JuggFileInfo>()
        val splitter: DexSplitter = D8DexSplitter()
        val apk = ApkParserAdt().parsePaths(listOf(apkInfo.file.absolutePath)).first()
        for (entry in apk.apkEntries.values) {
            if (entry.name.endsWith(".dex")) {
                val dexClasses = splitter.split(entry) { true }
                for (dexClass in dexClasses) {
                    classFiles[dexClass.name] = JuggFileInfo(dexClass.name, dexClass.checksum)
                }
            } else {
                overlayFiles[entry.name] = JuggFileInfo(entry.name, entry.checksum)
            }
        }

        return ParsedApk(apkInfo, classes, classFiles, overlayFiles)
    }
}

data class JuggFileInfo(
    val name: String,
    val checksum: Long
)