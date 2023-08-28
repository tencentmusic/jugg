package com.sickworm.intellij.jugg.deploy.data

import com.android.tools.idea.run.ApkInfo
import com.sickworm.intellij.jugg.compiler.ClassNode
import com.sickworm.intellij.jugg.compiler.FieldNode
import com.sickworm.intellij.jugg.compiler.MethodNode

class ParsedApk(
    val apkInfo: ApkInfo,
    val classes: Map<String, ClassNode>,
    val dexFiles: Map<String, JuggFileInfo>,
    val overlayFiles: Map<String, JuggFileInfo>,
    val methodRefs: Map<MethodNode, List<String>>,
    val fieldRefs: Map<FieldNode, List<String>>,
) {

    fun containsClass(className: String): Boolean {
        val classSigName = className.convertClassToSigFormat()
        return getClass(classSigName) != null
    }

    fun getClass(className: String): ClassNode? {
        val classSigName = className.convertClassToSigFormat()
        return classes[classSigName]
    }


    fun getClassSize(): Int {
        return classes.size
    }

    private fun String.convertClassToSigFormat(): String {
        return "L" + this.replace('.', '/') + ";"
    }
}