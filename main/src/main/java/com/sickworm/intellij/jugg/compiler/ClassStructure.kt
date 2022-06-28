package com.sickworm.intellij.jugg.compiler

import com.googlecode.d2j.node.DexClassNode
import com.googlecode.d2j.node.DexFieldNode
import com.googlecode.d2j.node.DexMethodNode
import org.objectweb.asm.*

/**
 * A dex class structure parsed from .dex file.
 */
class ClassNode(private val node: DexClassNode) {

    val className get() = node.className.convertSigFormatToPackage()

    val methods: List<MethodNode> get() = node.methods?.map { MethodNode(it) }?: emptyList()

    val fields: List<FieldNode> get() = node.fields?.map { FieldNode(it) }?: emptyList()

    val interfaceNames: Array<String> get() = node.interfaceNames?: emptyArray()

    val superClass: String get() = node.superClass

    /**
     * dump class structure by ASM, without private methods fields and actual code
     */
    fun dumpClassStub(): ByteArray {
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
class MethodNode(private val node: DexMethodNode) {

    val signature get() = node.method.toString()

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
class FieldNode(private val node: DexFieldNode) {

    val signature get() = "${node.access} ${node.field.owner} ${node.field.name} ${node.field.type}"

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