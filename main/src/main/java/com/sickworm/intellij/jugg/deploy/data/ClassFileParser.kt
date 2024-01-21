package com.sickworm.intellij.jugg.deploy.data

import com.sickworm.intellij.jugg.deploy.classSigName
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.util.concurrent.ConcurrentHashMap

class ClassFileParser(
    private val classFiles: List<File>,
) {

    val interfaces: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val staticInvocationRefs: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun parse() {
        for (classFile in classFiles) {
            val classReader = ClassReader(classFile.readBytes())
            val classVisitor = StaticInvocationCollector()
            classReader.accept(classVisitor, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        }

    }


    inner class StaticInvocationCollector: ClassVisitor(Opcodes.ASM9) {

        override fun visit(
            version: Int,
            access: Int,
            name: String?,
            signature: String?,
            superName: String?,
            interfaces: Array<out String>?
        ) {
            interfaces?.forEach {
                this@ClassFileParser.interfaces.add(it.classSigName)
            }
            super.visit(version, access, name, signature, superName, interfaces)
        }

        override fun visitMethod(
            access: Int,
            name: String?,
            desc: String?,
            signature: String?,
            exceptions: Array<out String>?
        ): MethodVisitor {
            return object : MethodVisitor(Opcodes.ASM9) {
                override fun visitMethodInsn(
                    opcode: Int,
                    owner: String?,
                    name: String?,
                    descriptor: String?,
                    isInterface: Boolean
                ) {
                    if (opcode == Opcodes.INVOKESTATIC && owner != null) {
                        staticInvocationRefs.add(owner.classSigName)
                    }
                }
            }
        }
    }

}