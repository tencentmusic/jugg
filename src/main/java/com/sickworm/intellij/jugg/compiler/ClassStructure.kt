package com.sickworm.intellij.jugg.compiler

import com.googlecode.d2j.node.DexClassNode
import com.googlecode.d2j.node.DexFieldNode
import com.googlecode.d2j.node.DexMethodNode

/** for null safe */
class ClassNode(private val node: DexClassNode) {

    val className get() = convertSigFormatToNormal()

    val methods: List<MethodNode> get() = node.methods?.map { MethodNode(it) }?: emptyList()

    val fields: List<FieldNode> get() = node.fields?.map { FieldNode(it) }?: emptyList()

    val interfaceNames: Array<String> get() = node.interfaceNames?: emptyArray()

    val superClass: String? get() = node.superClass

    // e.g. Landroid/support/v4/os/ResultReceiver$1;
    // ->
    // android.support.v4.os.ResultReceiver$1
    private fun convertSigFormatToNormal(): String {
        return node.className
            .substring(1, node.className.length - 1)
            .replace('/', '.')
    }
}

/** for null safe */
class MethodNode(private val node: DexMethodNode) {

    val signature get() = node.method.toString()

    fun isSignatureEquals(other: MethodNode): Boolean {
        return node.method.equals(other.node.method)
    }
}


/** for null safe */
class FieldNode(private val node: DexFieldNode) {

    fun isSignatureEquals(other: FieldNode): Boolean {
        // TODO read agent source code, whether need check access and annotation
        return node.access == other.node.access &&
                node.field.owner == other.node.field.owner &&
                node.field.name == other.node.field.name &&
                node.field.type == other.node.field.type
    }
}