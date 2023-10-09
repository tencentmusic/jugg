package com.sickworm.intellij.jugg.compiler.overlay

import com.googlecode.d2j.DexConstants
import com.googlecode.d2j.DexType
import com.googlecode.d2j.Field
import com.googlecode.d2j.Method
import com.googlecode.d2j.Visibility
import com.googlecode.d2j.dex.writer.DexFileWriter
import com.googlecode.d2j.dex.writer.ev.EncodedArray
import com.googlecode.d2j.dex.writer.ev.EncodedValue
import com.googlecode.d2j.dex.writer.item.*
import com.googlecode.d2j.reader.DexFileReader
import com.googlecode.d2j.visitors.*
import com.jetbrains.rd.util.first
import com.sickworm.intellij.jugg.deploy.asmSigFormat
import org.objectweb.asm.*
import com.sickworm.intellij.jugg.deploy.packageNameToPath
import java.io.File

class DexPackageRenamer(private val dexFile: File, private val newPackageName: String) {

    fun generate(dexOutputDir: File, classPathDir: File): Pair<File, File> {
        val outputFile = File(dexOutputDir, newPackageName.packageNameToPath + dexFile.name)
        val outputClasspathFile = File(classPathDir, newPackageName.packageNameToPath + dexFile.nameWithoutExtension + ".class")

        val reader = DexFileReader(dexFile.readBytes())
        val writer = ChangePackageWriter(newPackageName)
        reader.accept(writer, 0)

        // write outputClasspathFile first because data is cleared after toClassByteArray()
        outputClasspathFile.parentFile?.mkdirs()
        outputClasspathFile.writeBytes(writer.toClassByteArray())
        outputFile.parentFile?.mkdirs()
        outputFile.writeBytes(writer.toDexByteArray())
        return outputFile to outputClasspathFile
    }
}

private class ChangePackageWriter(
    private val newPackageName:  String,
    private val writer: DexFileWriter = DexFileWriter()
) : DexFileVisitor(writer) {

    override fun visit(
        access_flags: Int,
        className: String,
        superClass: String?,
        interfaceNames: Array<out String>?
    ): DexClassVisitor {
        val classPackageSigPrefix = className.substringBeforeLast("/")
        val newClassPackageSigPrefix = "L" + newPackageName.replace(".", "/")
        val newClassSigName = className.replace(classPackageSigPrefix, newClassPackageSigPrefix)

        val writerClassVisitor = writer.visit(access_flags, newClassSigName, superClass, interfaceNames)
        return object : DexClassVisitor(writerClassVisitor) {
            override fun visitAnnotation(name: String?, visibility: Visibility?): DexAnnotationVisitor {
                val writerAnnotationVisitor = writerClassVisitor.visitAnnotation(name, visibility)
                when (name) {
                    "Ldalvik/annotation/EnclosingClass;" -> {
                        return object : DexAnnotationVisitor(writerAnnotationVisitor) {
                            override fun visit(name: String?, value: Any?) {
                                when (value) {
                                    is DexType -> {
                                        val newDesc = value.desc.replace(classPackageSigPrefix, newClassPackageSigPrefix)
                                        val newDexType = DexType(newDesc)
                                        super.visit(name, newDexType)
                                    }

                                    else -> {
                                        super.visit(name, value)
                                    }
                                }
                            }
                        }
                    }
                    "Ldalvik/annotation/MemberClasses;" -> {
                        return object : DexAnnotationVisitor(writerAnnotationVisitor) {
                            override fun visitArray(name: String?): DexAnnotationVisitor {
                                return object : DexAnnotationVisitor(super.visitArray(name)) {
                                    override fun visit(name: String?, value: Any?) {
                                        when (value) {
                                            is DexType -> {
                                                val newDesc = value.desc.replace(classPackageSigPrefix, newClassPackageSigPrefix)
                                                val newDexType = DexType(newDesc)
                                                super.visit(name, newDexType)
                                            }

                                            else -> {
                                                super.visit(name, value)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    else -> {
                        return writerAnnotationVisitor
                    }
                }
            }

            override fun visitMethod(accessFlags: Int, method: Method): DexMethodVisitor {
                return if (method.owner == className) {
                    super.visitMethod(accessFlags, Method(newClassSigName, method.name, method.proto))
                } else {
                    super.visitMethod(accessFlags, method)
                }
            }

            override fun visitField(accessFlags: Int, field: Field, value: Any?): DexFieldVisitor {
                return if (field.owner == className) {
                    super.visitField(accessFlags, Field(newClassSigName, field.name, field.type), value)
                } else {
                    super.visitField(accessFlags, field, value)
                }
            }
        }
    }

    fun toDexByteArray(): ByteArray {
        return writer.toByteArray()
    }

    fun toClassByteArray(): ByteArray {
        val node: ClassDefItem = writer.cp.classDefs.first().value
        val cw = ClassWriter(0)
        // class
        cw.visit(
            Opcodes.V1_7, // Java 7
            node.accessFlags,
            node.clazz.descriptor.stringData.string.asmSigFormat,
            null,
            node.superclazz.descriptor.stringData.string.asmSigFormat,
            node.interfaces?.items?.map { it.descriptor.stringData.string.asmSigFormat }?.toTypedArray() ?: emptyArray()
        )
        cw.visitSource(node.sourceFile.stringData.string, null)

        node.classAnnotations.annotations.find {
            it.annotation.type.descriptor.stringData.string == "Ldalvik/annotation/MemberClasses;"
        }?.let {
            (it.annotation.elements.first().value.value as? EncodedArray)?.values?.forEach { value ->
                val innerClass = value.value as? TypeIdItem ?: return@forEach
                val innerClassName = innerClass.descriptor.stringData.string.asmSigFormat
                cw.visitInnerClass(innerClassName,
                    node.clazz.descriptor.stringData.string.asmSigFormat,
                    innerClassName.substringAfterLast("$"),
                    Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL
                    )
            }
        }

        val outerClassAnnotation = node.classAnnotations.annotations.find {
            it.annotation.type.descriptor.stringData.string == "Ldalvik/annotation/EnclosingClass;"
        }

        val innerClassAnnotation = node.classAnnotations.annotations.find {
            it.annotation.type.descriptor.stringData.string == "Ldalvik/annotation/InnerClass;"
        }
        if (outerClassAnnotation != null && innerClassAnnotation != null) {
            val outerClassName = ((outerClassAnnotation.annotation.elements.first().value as EncodedValue).value as TypeIdItem)
                .descriptor.stringData.string
            val innerClassName = ((innerClassAnnotation.annotation.elements.find { it.name.stringData.string == "name" }
                !!.value as EncodedValue).value as StringIdItem).stringData.string
            val innerClassAccessFlags = (innerClassAnnotation.annotation.elements.find { it.name.stringData.string == "accessFlags" }
                !!.value as EncodedValue).value as Int
            cw.visitInnerClass(node.clazz.descriptor.stringData.string.asmSigFormat,
                outerClassName,
                innerClassName,
                innerClassAccessFlags,
            )
        }

        // fields
        val visitField: (ClassDataItem.EncodedField) -> Unit = {
            cw.visitField(it.accessFlags,
                it.field.name.stringData.string,
                it.field.type.descriptor.stringData.string,
                null,
                it.staticValue?.value)
        }
        node.classData.staticFields.forEach {
            visitField(it)
        }
        node.classData.instanceFields.forEach {
            visitField(it)
        }

        // methods
        val visitMethod: (ClassDataItem.EncodedMethod) -> Unit = {
            val mv = cw.visitMethod(
                it.accessFlags,
                it.method.name.stringData.string,
                "(%s)%s".format(
                    it.method.proto.parameters.items.joinToString { param -> param.descriptor.stringData.string },
                    it.method.proto.ret.descriptor.stringData.string,
                ),
                null,
                null
            )
            when (it.method.proto.ret.descriptor.stringData.string) {
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
        node.classData.directMethods?.forEach {
            visitMethod(it)
        }
        node.classData.virtualMethods?.forEach {
            visitMethod(it)
        }

        cw.visitEnd()
        return cw.toByteArray()
    }
}