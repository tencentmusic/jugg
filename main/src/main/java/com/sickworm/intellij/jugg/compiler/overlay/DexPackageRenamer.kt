package com.sickworm.intellij.jugg.compiler.overlay

import com.googlecode.d2j.DexType
import com.googlecode.d2j.Visibility
import com.googlecode.d2j.dex.writer.DexFileWriter
import com.googlecode.d2j.reader.DexFileReader
import com.googlecode.d2j.visitors.DexAnnotationVisitor
import com.googlecode.d2j.visitors.DexClassVisitor
import com.googlecode.d2j.visitors.DexFileVisitor
import com.sickworm.intellij.jugg.deploy.classSigName
import com.sickworm.intellij.jugg.deploy.packageNameToPath
import java.io.File

class DexPackageRenamer(private val dexFile: File, private val newPackageName: String) {

    fun generate(outputDir: File): File {
        val outputFile = File(outputDir, newPackageName.packageNameToPath + dexFile.name)

        val reader = DexFileReader(dexFile.readBytes())
        val writer = ChangePackageWriter(newPackageName)
        reader.accept(writer, 0)

        outputFile.parentFile?.mkdirs()
        outputFile.writeBytes(writer.toByteArray())
        return outputFile
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
                return object : DexAnnotationVisitor(writerAnnotationVisitor) {
                    override fun visit(name: String?, value: Any?) {
                        if (value is DexType) {
                            val newDesc = value.desc.replace(classPackageSigPrefix, newClassPackageSigPrefix)
                            val newDexType = DexType(newDesc)
                            super.visit(name, newDexType)
                        } else {
                            super.visit(name, value)
                        }
                    }
                }
            }
        }
    }

    fun toByteArray(): ByteArray {
        return writer.toByteArray()
    }

    private fun changePackageName() {

    }
}