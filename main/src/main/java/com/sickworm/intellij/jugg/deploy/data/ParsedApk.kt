package com.sickworm.intellij.jugg.deploy.data

import com.sickworm.intellij.jugg.compiler.ClassNode
import com.sickworm.intellij.jugg.compiler.FieldNode
import com.sickworm.intellij.jugg.compiler.MethodNode
import java.io.File

/**
 * Apk parsed result.
 * Notice: class name in here is in the form of "Lcom/example/MainActivity;".
 */
class ParsedApk(
    val apkFile: File,
    val classes: Map<String, ClassNode>,
    val dexFiles: Map<String, JuggFileInfo>,
    val overlayFiles: Map<String, JuggFileInfo>,
    val methodRefs: Map<MethodNode, List<String>>,
    val fieldRefs: Map<FieldNode, List<String>>,
    val subclassRefs: Map<String, List<String>>,
) {
    override fun toString(): String {
        return "ParsedApk(apkFile=${apkFile}, " +
                "classes=${classes.size}, " +
                "dexFiles=${dexFiles.size}, " +
                "overlayFiles=${overlayFiles.size}, " +
                "methodRefs=${methodRefs.size}, " +
                "fieldRefs=${fieldRefs.size}, " +
                "subclassRefs=${subclassRefs.size}, "
    }
}