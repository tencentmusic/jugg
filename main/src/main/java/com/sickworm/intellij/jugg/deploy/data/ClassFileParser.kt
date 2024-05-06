package com.sickworm.intellij.jugg.deploy.data

import com.sickworm.intellij.jugg.deploy.classSigName
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import com.sickworm.intellij.jugg.org.objectweb.asm.ClassReader
import com.sickworm.intellij.jugg.org.objectweb.asm.ClassVisitor
import com.sickworm.intellij.jugg.org.objectweb.asm.MethodVisitor
import com.sickworm.intellij.jugg.org.objectweb.asm.Opcodes
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * Collect class and interface references and static invocation references from class files.
 * Won't collect references from classes in same class/jar.
 */
class ClassFileParser(
    private val classFiles: List<File>,
) {

    val classes: MutableSet<String> = mutableSetOf()
    val interfaces: MutableSet<String> = mutableSetOf()
    val staticInvocationRefs: MutableSet<String> = mutableSetOf()

    fun parse() {
        for (classFile in classFiles) {
            if (classFile.extension == "jar") {
                val jarFile = ZipFile(classFile)
                val entries = jarFile.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.name.endsWith(".class")) {
                        val classReader = ClassReader(jarFile.getInputStream(entry))
                        val classVisitor = StaticInvocationCollector()
                        classReader.accept(classVisitor, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
                    }
                }
            } else {
                val classReader = ClassReader(classFile.inputStream())
                val classVisitor = StaticInvocationCollector()
                classReader.accept(classVisitor, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
            }
        }

    }


    inner class StaticInvocationCollector: ClassVisitor(Opcodes.ASM9) {

        override fun visit(
            version: Int,
            access: Int,
            name: String,
            signature: String?,
            superName: String?,
            interfaces: Array<out String>?
        ) {
            classes.add(name.classSigName)
            if (this@ClassFileParser.interfaces.contains(name.classSigName)) {
                this@ClassFileParser.interfaces.remove(name.classSigName)
            }
            if (this@ClassFileParser.staticInvocationRefs.contains(name.classSigName)) {
                this@ClassFileParser.staticInvocationRefs.remove(name.classSigName)
            }

            interfaces?.forEach {
                if (!this@ClassFileParser.classes.contains(it.classSigName)) {
                    this@ClassFileParser.interfaces.add(it.classSigName)
                }
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
                        if (!this@ClassFileParser.classes.contains(owner.classSigName)) {
                            staticInvocationRefs.add(owner.classSigName)
                        }
                    }
                }
            }
        }
    }

}