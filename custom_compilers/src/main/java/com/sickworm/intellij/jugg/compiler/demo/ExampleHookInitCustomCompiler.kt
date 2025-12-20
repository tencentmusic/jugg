package com.sickworm.intellij.jugg.compiler.demo

import com.google.auto.service.AutoService
import com.intellij.openapi.Disposable
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.CompileOrder
import com.sickworm.intellij.jugg.compiler.CompileResult
import com.sickworm.intellij.jugg.compiler.CompileTask
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.compiler.ICompiler
import com.sickworm.intellij.jugg.compiler.custom.ICompilerCreator
import com.sickworm.intellij.jugg.org.objectweb.asm.ClassReader
import com.sickworm.intellij.jugg.org.objectweb.asm.ClassVisitor
import com.sickworm.intellij.jugg.org.objectweb.asm.ClassWriter
import com.sickworm.intellij.jugg.org.objectweb.asm.MethodVisitor
import com.sickworm.intellij.jugg.org.objectweb.asm.Opcodes
import java.io.InputStream

class ExampleHookInitCustomCompiler(private val context: ICompileContext) : ICompiler {

    @AutoService(ICompilerCreator::class)
    class Creator : ICompilerCreator {
        override fun create(context: ICompileContext, parent: Disposable): ICompiler {
            return ExampleHookInitCustomCompiler(context)
        }
    }

    override val supportedTypes: List<CompileFile.Type> = CompileFile.Type.entries

    override val order: Int = CompileOrder.afterSource.first

    override fun compile(task: CompileTask): CompileResult {
        task.files.forEach { file ->
            if (file.type != CompileFile.Type.Class) {
                return@forEach
            }
            instrument(file.file.absolutePath)
        }
        return CompileResult(task, emptyList(), emptyList())
    }

    override fun dispose() {
        context.logger.debug("[ExampleHookInitCustomCompiler] I'm disposed!")
    }

    fun instrument(classPath: String) {
        var fileInputStream: java.io.FileInputStream? = null
        var fileOutputStream: java.io.FileOutputStream? = null
        try {
            val file = java.io.File(classPath)
            fileInputStream = java.io.FileInputStream(file)
            val data = instrument(java.io.FileInputStream(file))
            fileInputStream.close()
            fileOutputStream = java.io.FileOutputStream(file)
            fileOutputStream.write(data, 0, data.size)
            fileOutputStream.close()
            println("compile ok :$classPath")
        } finally {
            fileInputStream?.close()
            fileOutputStream?.close()
        }
    }

    private fun instrument(input: InputStream): ByteArray {
        val classReader = ClassReader(input)
        val classWriter = ClassWriter(classReader, ClassWriter.COMPUTE_MAXS)
        val classVisitor = InitInstrumentClassVisitor(classWriter)

        classReader.accept(classVisitor, ClassReader.EXPAND_FRAMES)
        return classWriter.toByteArray()
    }

    private class InitInstrumentClassVisitor(
        classWriter: ClassWriter
    ) : ClassVisitor(Opcodes.ASM9, classWriter) {

        private var className: String? = null

        override fun visit(
            version: Int,
            access: Int,
            name: String,
            signature: String?,
            superName: String?,
            interfaces: Array<out String>?
        ) {
            className = name.replace('/', '.')
            super.visit(version, access, name, signature, superName, interfaces)
        }

        override fun visitMethod(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            exceptions: Array<out String>?
        ): MethodVisitor? {
            val methodVisitor = super.visitMethod(access, name, descriptor, signature, exceptions)

            // Only instrument the <init> method
            if ("<init>" == name) {
                return InitMethodVisitor(methodVisitor, className ?: "")
            }

            return methodVisitor
        }
    }

    private class InitMethodVisitor(
        methodVisitor: MethodVisitor,
        private val className: String
    ) : MethodVisitor(Opcodes.ASM9, methodVisitor) {

        override fun visitInsn(opcode: Int) {
            // Insert System.out.println call before each RETURN instruction in <init>
            if (opcode == Opcodes.RETURN) {
                injectPrintlnCall()
            }
            super.visitInsn(opcode)
        }

        private fun injectPrintlnCall() {
            // Call WhatShouldIToast().message() once during instrumentation
            val whatShouldIToast = Class.forName("com.sickworm.intellij.jugg.compiler.demo.WhatShouldIToast").newInstance() as Any
            val messageMethod = whatShouldIToast.javaClass.getMethod("message")
            val message = messageMethod.invoke(whatShouldIToast) as String

            // Inject the resolved message with class name
            val fullMessage = "$message [from class: $className]"

            mv.visitFieldInsn(
                Opcodes.GETSTATIC,
                "java/lang/System",
                "out",
                "Ljava/io/PrintStream;"
            )
            mv.visitLdcInsn(fullMessage)
            mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/io/PrintStream",
                "println",
                "(Ljava/lang/String;)V",
                false
            )
        }
    }
}
