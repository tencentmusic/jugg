package com.sickworm.intellij.jugg.deploy.data

import com.sickworm.intellij.jugg.org.objectweb.asm.*
import java.io.File

class ClassSourceReader(
    private val classFile: File,
) {

    private var className: String? =  null
    private var source: String? = null

    fun read(): Pair<String?, String?> {
        classFile.inputStream().use { ins ->
            val classReader = ClassReader(ins)
            val classVisitor = SourceCollector()
            classReader.accept(classVisitor, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        }
        return className to source
    }

    private inner class SourceCollector: ClassVisitor(Opcodes.ASM9) {

        override fun visit(
            version: Int,
            access: Int,
            name: String?,
            signature: String?,
            superName: String?,
            interfaces: Array<out String?>?
        ) {
            this@ClassSourceReader.className = name
        }

        override fun visitSource(source: String?, debug: String?) {
            this@ClassSourceReader.source = source
        }
    }
}