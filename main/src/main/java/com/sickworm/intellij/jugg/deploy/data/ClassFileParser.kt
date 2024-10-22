package com.sickworm.intellij.jugg.deploy.data

import com.sickworm.intellij.jugg.deploy.classSigName
import com.sickworm.intellij.jugg.org.objectweb.asm.*
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
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
                ZipFile(classFile).use { jarFile ->
                    val entries = jarFile.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.name.endsWith(".class")) {
                            jarFile.getInputStream(entry).use { ins ->
                                val classReader = ClassReader(ins)
                                val classVisitor = StaticInvocationCollector()
                                classReader.accept(classVisitor, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
                            }
                        }
                    }
                }
            } else {
                classFile.inputStream().use { ins ->
                    val classReader = ClassReader(ins)
                    val classVisitor = StaticInvocationCollector()
                    classReader.accept(classVisitor, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
                }
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
            val classSignName = name.classSigName
            classes.add(classSignName)
            if (this@ClassFileParser.interfaces.contains(classSignName)) {
                this@ClassFileParser.interfaces.remove(classSignName)
            }
            if (this@ClassFileParser.staticInvocationRefs.contains(classSignName)) {
                this@ClassFileParser.staticInvocationRefs.remove(classSignName)
            }

            interfaces?.forEach {
                val interfaceSigName = it.classSigName
                if (!this@ClassFileParser.classes.contains(interfaceSigName)) {
                    this@ClassFileParser.interfaces.add(interfaceSigName)
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
                        val ownerSigName = owner.classSigName
                        if (!this@ClassFileParser.classes.contains(ownerSigName)) {
                            staticInvocationRefs.add(ownerSigName)
                        }
                    }
                }

                override fun visitInvokeDynamicInsn(
                    name: String?,
                    descriptor: String?,
                    bootstrapMethodHandle: Handle?,
                    vararg bootstrapMethodArguments: Any?
                ) {
                    descriptor ?: return
                    val originInterface = descriptor.substringAfter(')')
                    if (!this@ClassFileParser.classes.contains(originInterface)) {
                        interfaces.add(originInterface)
                    }
                }
            }
        }
    }

}