package com.sickworm.intellij.jugg.deploy.data

import com.sickworm.intellij.jugg.deploy.classSigName
import com.sickworm.intellij.jugg.org.objectweb.asm.*
import java.io.File
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
                                val classVisitor = InvocationCollector()
                                classReader.accept(classVisitor, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
                            }
                        }
                    }
                }
            } else {
                classFile.inputStream().use { ins ->
                    val classReader = ClassReader(ins)
                    val classVisitor = InvocationCollector()
                    classReader.accept(classVisitor, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
                }
            }
        }

    }


    inner class InvocationCollector : ClassVisitor(Opcodes.ASM9) {

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
                // which means it will compile together, so no need to add it to interfaces
                this@ClassFileParser.interfaces.remove(classSignName)
            }
            if (this@ClassFileParser.staticInvocationRefs.contains(classSignName)) {
                // which means it will compile together, so no need to add it to staticInvocationRefs
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
                    owner ?: return
                    val ownerSigName = owner.classSigName

                    if (opcode == Opcodes.INVOKESTATIC) {
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