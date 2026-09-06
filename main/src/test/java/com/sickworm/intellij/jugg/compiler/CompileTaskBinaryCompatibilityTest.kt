package com.sickworm.intellij.jugg.compiler

import com.sickworm.intellij.jugg.org.objectweb.asm.ClassWriter
import com.sickworm.intellij.jugg.org.objectweb.asm.Opcodes
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import kotlin.test.assertIs

/** Verifies that consumers compiled against legacy CompileTask constructors remain executable. */
class CompileTaskBinaryCompatibilityTest {

    @Test
    fun `legacy constructor descriptors remain executable`() {
        val consumer = LegacyConsumerLoader(javaClass.classLoader).define(createLegacyConsumer())

        val statusHolderResult = invoke(consumer, "createWithStatusHolder")
        val parentResult = invoke(consumer, "createWithParent")

        assertIs<CompileTask>(statusHolderResult)
        assertIs<CompileTask>(parentResult)
    }

    private fun invoke(consumer: Class<*>, methodName: String): Any? {
        return try {
            consumer.getMethod(methodName, CompileStatusHolder::class.java).invoke(null, CompileStatusHolder.DEFAULT)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private fun createLegacyConsumer(): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        writer.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL,
            CONSUMER_CLASS,
            null,
            "java/lang/Object",
            null,
        )
        addStatusHolderConstructorCall(writer)
        addParentConstructorCall(writer)
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun addStatusHolderConstructorCall(writer: ClassWriter) {
        writer.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            "createWithStatusHolder",
            "($COMPILE_STATUS_HOLDER_DESC)Ljava/lang/Object;",
            null,
            null,
        ).apply {
            visitCode()
            visitTypeInsn(Opcodes.NEW, COMPILE_TASK_CLASS)
            visitInsn(Opcodes.DUP)
            visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Collections", "emptyList", "()Ljava/util/List;", false)
            newFile(this)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, COMPILE_TASK_CLASS, "<init>", LEGACY_STATUS_HOLDER_DESC, false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
    }

    private fun addParentConstructorCall(writer: ClassWriter) {
        writer.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            "createWithParent",
            "($COMPILE_STATUS_HOLDER_DESC)Ljava/lang/Object;",
            null,
            null,
        ).apply {
            visitCode()
            visitTypeInsn(Opcodes.NEW, COMPILE_TASK_CLASS)
            visitInsn(Opcodes.DUP)
            visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Collections", "emptyList", "()Ljava/util/List;", false)
            newFile(this)
            visitInsn(Opcodes.ACONST_NULL)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, COMPILE_TASK_CLASS, "<init>", LEGACY_PRIMARY_DESC, false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
    }

    private fun newFile(visitor: com.sickworm.intellij.jugg.org.objectweb.asm.MethodVisitor) {
        visitor.visitTypeInsn(Opcodes.NEW, "java/io/File")
        visitor.visitInsn(Opcodes.DUP)
        visitor.visitLdcInsn("")
        visitor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/io/File", "<init>", "(Ljava/lang/String;)V", false)
    }

    private class LegacyConsumerLoader(parent: ClassLoader) : ClassLoader(parent) {
        fun define(bytes: ByteArray): Class<*> = defineClass(CONSUMER_CLASS.replace('/', '.'), bytes, 0, bytes.size)
    }

    companion object {
        private const val CONSUMER_CLASS = "com/sickworm/intellij/jugg/compiler/LegacyCompileTaskConsumer"
        private const val COMPILE_TASK_CLASS = "com/sickworm/intellij/jugg/compiler/CompileTask"
        private const val COMPILE_STATUS_HOLDER_CLASS = "com/sickworm/intellij/jugg/compiler/CompileStatusHolder"
        private const val COMPILE_STATUS_HOLDER_DESC = "L$COMPILE_STATUS_HOLDER_CLASS;"
        private const val LEGACY_STATUS_HOLDER_DESC = "(Ljava/util/List;Ljava/io/File;$COMPILE_STATUS_HOLDER_DESC)V"
        private const val LEGACY_PRIMARY_DESC = "(Ljava/util/List;Ljava/io/File;L$COMPILE_TASK_CLASS;$COMPILE_STATUS_HOLDER_DESC)V"
    }
}
