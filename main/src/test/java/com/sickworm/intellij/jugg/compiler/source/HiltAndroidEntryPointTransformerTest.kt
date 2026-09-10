package com.sickworm.intellij.jugg.compiler.source

import com.sickworm.intellij.jugg.deploy.data.ClassFileParser
import com.sickworm.intellij.jugg.org.objectweb.asm.ClassReader
import com.sickworm.intellij.jugg.org.objectweb.asm.ClassVisitor
import com.sickworm.intellij.jugg.org.objectweb.asm.ClassWriter
import com.sickworm.intellij.jugg.org.objectweb.asm.MethodVisitor
import com.sickworm.intellij.jugg.org.objectweb.asm.Opcodes
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HiltAndroidEntryPointTransformerTest {
    private val transformer = HiltAndroidEntryPointTransformer()

    @Test
    fun `transforms superclass signature and only real super calls`() {
        val input = entryPointClass(
            name = "test/Outer\$Entry",
            superName = "test/Base",
            signature = "Ltest/Base<Ljava/lang/String;>;",
        )
        val generatedBase = simpleClass("test/Hilt_Outer_Entry", "test/Base")

        val result = transformer.transform(
            input,
            ClassFileParser.analyze(input),
            generatedBase,
        )
        val inspected = inspect(result.bytes)

        assertEquals("test/Hilt_Outer_Entry", inspected.superName)
        assertEquals("Ltest/Hilt_Outer_Entry<Ljava/lang/String;>;", inspected.signature)
        assertContentEquals(
            listOf(
                Invocation("<init>", "test/Hilt_Outer_Entry", "<init>"),
                Invocation("<init>", "test/Base", "<init>"),
                Invocation("value", "test/Hilt_Outer_Entry", "value"),
                Invocation("create", "test/Base", "<init>"),
            ),
            inspected.invocations,
        )
    }

    @Test
    fun `receiver marker inserts one generated super call and repeated transform is stable`() {
        val input = receiverClass("test/TestReceiver", "test/BaseReceiver")
        val generatedBase = simpleClass(
            name = "test/Hilt_TestReceiver",
            superName = "test/BaseReceiver",
            annotations = listOf(ON_RECEIVE_MARKER),
        )
        val first = transformer.transform(
            input,
            ClassFileParser.analyze(input),
            generatedBase,
        )
        val second = transformer.transform(
            first.bytes,
            first.analysis,
            generatedBase,
        )

        assertEquals(
            1,
            inspect(second.bytes).invocations.count {
                it.method == "onReceive" && it.owner == "test/Hilt_TestReceiver"
            },
        )
        assertContentEquals(first.bytes, second.bytes)
    }

    @Test
    fun `legacy receiver marker field inserts generated super call`() {
        val input = receiverClass("test/TestReceiver", "test/BaseReceiver")
        val generatedBase = legacyReceiverBaseClass("test/Hilt_TestReceiver", "test/BaseReceiver")

        val result = transformer.transform(
            input,
            ClassFileParser.analyze(input),
            generatedBase,
        )

        assertEquals(
            1,
            inspect(result.bytes).invocations.count {
                it.method == "onReceive" && it.owner == "test/Hilt_TestReceiver"
            },
        )
    }

    @Test
    fun `receiver without marker keeps onReceive body unchanged`() {
        val input = receiverClass("test/TestReceiver", "test/BaseReceiver")
        val generatedBase = simpleClass("test/Hilt_TestReceiver", "test/BaseReceiver")

        val result = transformer.transform(
            input,
            ClassFileParser.analyze(input),
            generatedBase,
        )

        assertFalse(inspect(result.bytes).invocations.any { it.method == "onReceive" })
    }

    @Test
    fun `recognizes only Hilt entry point annotations`() {
        val entryPoint = ClassFileParser.analyze(entryPointClass("test/Entry", "test/Base"))
        val hiltApplication = ClassFileParser.analyze(
            simpleClass("test/Application", "java/lang/Object", listOf(HILT_ANDROID_APP)),
        )
        val plain = ClassFileParser.analyze(simpleClass("test/Plain", "test/Base"))

        assertTrue(transformer.isEntryPoint(entryPoint))
        assertTrue(transformer.isEntryPoint(hiltApplication))
        assertFalse(transformer.isEntryPoint(plain))
    }

    private fun entryPointClass(
        name: String,
        superName: String,
        signature: String? = null,
    ): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, signature, superName, null)
        writer.visitAnnotation(ANDROID_ENTRY_POINT, false).visitEnd()
        writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false)
            visitTypeInsn(Opcodes.NEW, superName)
            visitInsn(Opcodes.DUP)
            visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false)
            visitInsn(Opcodes.POP)
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitMethod(Opcodes.ACC_PUBLIC, "value", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "value", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitMethod(Opcodes.ACC_PUBLIC, "create", "()Ltest/Base;", null, null).apply {
            visitCode()
            visitTypeInsn(Opcodes.NEW, superName)
            visitInsn(Opcodes.DUP)
            visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun receiverClass(name: String, superName: String): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, superName, null)
        writer.visitAnnotation(ANDROID_ENTRY_POINT, false).visitEnd()
        writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitMethod(Opcodes.ACC_PUBLIC, "onReceive", ON_RECEIVE_DESCRIPTOR, null, null).apply {
            visitCode()
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun simpleClass(
        name: String,
        superName: String,
        annotations: List<String> = emptyList(),
    ): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, superName, null)
        annotations.forEach { writer.visitAnnotation(it, false).visitEnd() }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun legacyReceiverBaseClass(name: String, superName: String): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, superName, null)
        writer.visitField(
            Opcodes.ACC_PRIVATE or Opcodes.ACC_FINAL,
            LEGACY_ON_RECEIVE_MARKER,
            "Z",
            null,
            false,
        ).visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun inspect(bytes: ByteArray): InspectedClass {
        var superName = ""
        var signature: String? = null
        val invocations = mutableListOf<Invocation>()
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visit(
                version: Int,
                access: Int,
                name: String,
                classSignature: String?,
                classSuperName: String?,
                interfaces: Array<out String>?,
            ) {
                superName = classSuperName.orEmpty()
                signature = classSignature
            }

            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?,
            ): MethodVisitor {
                return object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitMethodInsn(
                        opcode: Int,
                        owner: String,
                        invokedName: String,
                        descriptor: String,
                        isInterface: Boolean,
                    ) {
                        invocations += Invocation(name, owner, invokedName)
                    }
                }
            }
        }, 0)
        return InspectedClass(superName, signature, invocations)
    }

    private data class InspectedClass(
        val superName: String,
        val signature: String?,
        val invocations: List<Invocation>,
    )

    private data class Invocation(
        val method: String,
        val owner: String,
        val invokedName: String,
    )

    companion object {
        private const val ANDROID_ENTRY_POINT = "Ldagger/hilt/android/AndroidEntryPoint;"
        private const val HILT_ANDROID_APP = "Ldagger/hilt/android/HiltAndroidApp;"
        private const val ON_RECEIVE_MARKER =
            "Ldagger/hilt/android/internal/OnReceiveBytecodeInjectionMarker;"
        private const val LEGACY_ON_RECEIVE_MARKER = "onReceiveBytecodeInjectionMarker"
        private const val ON_RECEIVE_DESCRIPTOR = "(Landroid/content/Context;Landroid/content/Intent;)V"
    }
}
