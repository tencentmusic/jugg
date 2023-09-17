package com.sickworm.intellij.jugg.deploy.data

import com.android.tools.idea.run.ApkInfo
import com.sickworm.intellij.jugg.compiler.ClassNode
import com.sickworm.intellij.jugg.compiler.FieldNode
import com.sickworm.intellij.jugg.compiler.MethodNode

/**
 * Apk parsed result.
 * Notice: class name in here is in the form of "Lcom/example/MainActivity;".
 */
class ParsedApk(
    val apkInfo: ApkInfo,
    val classes: Map<String, ClassNode>,
    val dexFiles: Map<String, JuggFileInfo>,
    val overlayFiles: Map<String, JuggFileInfo>,
    val methodRefs: Map<MethodNode, List<String>>,
    val fieldRefs: Map<FieldNode, List<String>>,
    val subclassRefs: Map<String, List<String>>,
)
