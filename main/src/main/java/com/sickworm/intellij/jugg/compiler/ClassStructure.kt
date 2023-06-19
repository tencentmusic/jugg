package com.sickworm.intellij.jugg.compiler

import com.googlecode.d2j.node.DexClassNode
import com.googlecode.d2j.node.DexFieldNode
import com.googlecode.d2j.node.DexMethodNode
import org.objectweb.asm.*
import java.util.LinkedList

/**
 * A dex class structure parsed from .dex file.
 */
class ClassNode(node: DexClassNode, isKeepNode: Boolean = false) {

    val className = node.className.convertSigFormatToPackage()

    val methods: List<MethodNode> = node.methods?.map { MethodNode(it) }?: emptyList()

    val fields: List<FieldNode> = node.fields?.map { FieldNode(it) }?: emptyList()

    val interfaceNames: List<String> = node.interfaceNames?.map { ClassStringPool[it] }?: emptyList()

    val superClass: String = ClassStringPool[node.superClass]

    val node: DexClassNode? = if (isKeepNode) node else null

    /**
     * dump class structure by ASM, without private methods fields and actual code
     */
    fun dumpClassStub(): ByteArray {
        node!!

        val cw = ClassWriter(0)
        // class
        cw.visit(
            Opcodes.V1_7, // Java 7
            node.access,
            node.className.convertSigFormatToNormal(),
            null,
            superClass.convertSigFormatToNormal(),
            node.interfaceNames.map { it.convertSigFormatToNormal() }.toTypedArray()
        )
        cw.visitSource(node.source, null)

        // fields
        node.fields?.forEach {
            if (it.access and Opcodes.ACC_PRIVATE != 0) {
                return@forEach
            }
            cw.visitField(it.access, it.field.name, it.field.type, null, it.cst)
        }

        // methods
        node.methods?.forEach {
            if (it.access and Opcodes.ACC_PRIVATE != 0) {
                return@forEach
            }
            val mv = cw.visitMethod(
                it.access,
                it.method.name,
                it.method.desc,
                null,
                null
            )
            when (it.method.returnType) {
                "V" -> {
                    mv.visitInsn(Opcodes.RETURN)
                    mv.visitMaxs(0, 1)
                }
                "I", "Z" -> {
                    mv.visitInsn(Opcodes.ICONST_0)
                    mv.visitInsn(Opcodes.IRETURN)
                    mv.visitMaxs(1, 1)
                }
                "F" -> {
                    mv.visitInsn(Opcodes.FCONST_0)
                    mv.visitInsn(Opcodes.FRETURN)
                    mv.visitMaxs(1, 1)
                }
                "L" -> {
                    mv.visitInsn(Opcodes.LCONST_0)
                    mv.visitInsn(Opcodes.LRETURN)
                    mv.visitMaxs(2, 1)
                }
                "D" -> {
                    mv.visitInsn(Opcodes.DCONST_0)
                    mv.visitInsn(Opcodes.DRETURN)
                    mv.visitMaxs(2, 1)
                }
                else -> {
                    // object
                    mv.visitInsn(Opcodes.ACONST_NULL)
                    mv.visitInsn(Opcodes.ARETURN)
                    mv.visitMaxs(1, 1)
                }
            }
            mv.visitEnd()
        }

        cw.visitEnd()
        return cw.toByteArray()
    }

    // e.g. Landroid/support/v4/os/ResultReceiver$1;
    // ->
    // android.support.v4.os.ResultReceiver$1
    private fun String.convertSigFormatToPackage(): String {
        return this.convertSigFormatToNormal().replace('/', '.')
    }

    // e.g. Landroid/support/v4/os/ResultReceiver$1;
    // ->
    // android/support/v4/os/ResultReceiver$1
    private fun String.convertSigFormatToNormal(): String {
        return this.substring(1, this.length - 1)
    }
}

/**
 * A dex method structure parsed from .dex file.
 */
class MethodNode(node: DexMethodNode) {

    private val name = ClassStringPool[node.method.name]
    private val desc = ClassStringPool[node.method.desc]

    val signature get() = "${name}${desc}"

    override fun equals(other: Any?): Boolean {
        if (other !is MethodNode) {
            return false
        }
        return signature == other.signature
    }

    override fun toString(): String {
        return signature
    }

    override fun hashCode(): Int {
        return signature.hashCode()
    }
}

/**
 * A dex field structure parsed from .dex file.
 */
class FieldNode(node: DexFieldNode) {

    private val access = node.access
    private val name = ClassStringPool[node.field.name]
    private val type = ClassStringPool[node.field.type]

    val signature get() = "$access $name $type"

    override fun equals(other: Any?): Boolean {
        if (other !is FieldNode) {
            return false
        }
        return signature == other.signature
    }

    override fun toString(): String {
        return signature
    }

    override fun hashCode(): Int {
        return signature.hashCode()
    }
}

/** For save memory for same string but different instance */
object ClassStringPool {

    private val stringPool = mutableMapOf<String, String>()

    operator fun get(string: String): String {
        val cacheString = stringPool[string]
        if (cacheString != null) {
            return cacheString
        }
        stringPool[string] = string
        return string
    }
}