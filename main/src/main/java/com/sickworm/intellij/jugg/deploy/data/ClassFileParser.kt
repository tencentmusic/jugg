package com.sickworm.intellij.jugg.deploy.data

import com.sickworm.intellij.jugg.deploy.classSigName
import com.sickworm.intellij.jugg.deploy.isBootClasspathClass
import com.sickworm.intellij.jugg.org.objectweb.asm.AnnotationVisitor
import com.sickworm.intellij.jugg.org.objectweb.asm.ClassReader
import com.sickworm.intellij.jugg.org.objectweb.asm.ClassVisitor
import com.sickworm.intellij.jugg.org.objectweb.asm.Handle
import com.sickworm.intellij.jugg.org.objectweb.asm.MethodVisitor
import com.sickworm.intellij.jugg.org.objectweb.asm.Opcodes
import java.io.File
import java.util.zip.ZipFile

/** Immutable analysis collected from one JVM class. */
internal data class ClassAnalysis(
    val className: String,
    val superClass: String?,
    val interfaces: Set<String>,
    val staticInvocationRefs: Set<String>,
    val annotationDescriptors: Set<String>,
)

/** Immutable batch view that excludes references declared by the same program input. */
internal data class ClassAnalysisBatch(
    val analyses: List<ClassAnalysis>,
    val classes: Set<String>,
    val interfaces: Set<String>,
    val staticInvocationRefs: Set<String>,
    val externalSuperClasses: Set<String>,
) {
    companion object {
        val EMPTY = from(emptyList())

        fun from(analyses: List<ClassAnalysis>): ClassAnalysisBatch {
            val immutableAnalyses = analyses.toList()
            val classes = immutableAnalyses.mapTo(linkedSetOf(), ClassAnalysis::className)
            return ClassAnalysisBatch(
                analyses = immutableAnalyses,
                classes = classes,
                interfaces = immutableAnalyses.flatMapTo(linkedSetOf()) { it.interfaces }.filterTo(linkedSetOf()) {
                    it !in classes
                },
                staticInvocationRefs = immutableAnalyses.flatMapTo(linkedSetOf()) { it.staticInvocationRefs }
                    .filterTo(linkedSetOf()) { it !in classes },
                externalSuperClasses = immutableAnalyses.mapNotNullTo(linkedSetOf(), ClassAnalysis::superClass)
                    .filterTo(linkedSetOf()) { it !in classes && !it.isBootClasspathClass },
            )
        }

        fun merge(batches: List<ClassAnalysisBatch>): ClassAnalysisBatch {
            return from(batches.flatMap { it.analyses })
        }
    }
}

/** Minimal class header used while walking an external superclass chain. */
internal data class ClassHeader(
    val className: String,
    val superClass: String?,
    val annotationDescriptors: Set<String>,
)

/**
 * Collects class, interface, superclass, annotation, and static invocation references from JVM classes.
 */
internal class ClassFileParser(
    private val classFiles: List<File>,
) {
    private var result = ClassAnalysisBatch.EMPTY
    var parsedByteCount: Long = 0
        private set

    val classes: Set<String> get() = result.classes
    val interfaces: Set<String> get() = result.interfaces
    val staticInvocationRefs: Set<String> get() = result.staticInvocationRefs
    val externalSuperClasses: Set<String> get() = result.externalSuperClasses

    fun parse(): ClassAnalysisBatch {
        parsedByteCount = 0
        result = ClassAnalysisBatch.from(classFiles.flatMap(::analyzeFile))
        return result
    }

    private fun analyzeFile(classFile: File): List<ClassAnalysis> {
        if (classFile.extension != "jar") {
            return listOf(analyzeBytes(classFile.readBytes()))
        }
        return ZipFile(classFile).use { jarFile ->
            jarFile.entries().asSequence()
                .filter { it.name.endsWith(".class") }
                .map { entry -> jarFile.getInputStream(entry).use { analyzeBytes(it.readBytes()) } }
                .toList()
        }
    }

    private fun analyzeBytes(bytes: ByteArray): ClassAnalysis {
        parsedByteCount += bytes.size
        return analyze(bytes)
    }

    companion object {
        fun analyze(bytes: ByteArray): ClassAnalysis {
            val collector = InvocationCollector()
            ClassReader(bytes).accept(collector, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
            return collector.analysis()
        }

        fun analyzeHeader(bytes: ByteArray): ClassHeader {
            val collector = HeaderCollector()
            ClassReader(bytes).accept(
                collector,
                ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
            )
            return collector.header()
        }
    }

    private open class HeaderCollector : ClassVisitor(Opcodes.ASM9) {
        private lateinit var className: String
        private var superClass: String? = null
        protected val annotationDescriptors = linkedSetOf<String>()

        override fun visit(
            version: Int,
            access: Int,
            name: String,
            signature: String?,
            superName: String?,
            interfaces: Array<out String>?,
        ) {
            className = name.classSigName
            superClass = superName?.classSigName
        }

        override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
            annotationDescriptors.add(descriptor)
            return null
        }

        fun header(): ClassHeader {
            return ClassHeader(className, superClass, annotationDescriptors.toSet())
        }
    }

    private class InvocationCollector : HeaderCollector() {
        private val interfaces = linkedSetOf<String>()
        private val staticInvocationRefs = linkedSetOf<String>()

        override fun visit(
            version: Int,
            access: Int,
            name: String,
            signature: String?,
            superName: String?,
            interfaces: Array<out String>?,
        ) {
            super.visit(version, access, name, signature, superName, interfaces)
            interfaces?.mapTo(this.interfaces) { it.classSigName }
        }

        override fun visitMethod(
            access: Int,
            name: String?,
            descriptor: String?,
            signature: String?,
            exceptions: Array<out String>?,
        ): MethodVisitor {
            return object : MethodVisitor(Opcodes.ASM9) {
                override fun visitMethodInsn(
                    opcode: Int,
                    owner: String?,
                    name: String?,
                    descriptor: String?,
                    isInterface: Boolean,
                ) {
                    if (opcode == Opcodes.INVOKESTATIC && owner != null) {
                        staticInvocationRefs.add(owner.classSigName)
                    }
                }

                override fun visitInvokeDynamicInsn(
                    name: String?,
                    descriptor: String?,
                    bootstrapMethodHandle: Handle?,
                    vararg bootstrapMethodArguments: Any?,
                ) {
                    descriptor?.substringAfter(')')?.let(interfaces::add)
                }
            }
        }

        fun analysis(): ClassAnalysis {
            val header = header()
            return ClassAnalysis(
                className = header.className,
                superClass = header.superClass,
                interfaces = interfaces.toSet(),
                staticInvocationRefs = staticInvocationRefs.toSet(),
                annotationDescriptors = annotationDescriptors.toSet(),
            )
        }
    }
}
