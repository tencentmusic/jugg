package com.sickworm.intellij.jugg.compiler.source.apt.processors

import com.android.tools.build.jetifier.core.config.ConfigParser
import com.android.tools.build.jetifier.processor.FileMapping
import com.android.tools.build.jetifier.processor.Processor
import com.sickworm.intellij.jugg.compiler.CompileTask
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.compiler.source.apt.GeneratedSourceCheckpoint
import com.sickworm.intellij.jugg.compiler.source.apt.AnnotationProcessorClasspath
import com.sickworm.intellij.jugg.org.objectweb.asm.ClassReader
import com.sickworm.intellij.jugg.org.objectweb.asm.ClassVisitor
import com.sickworm.intellij.jugg.org.objectweb.asm.FieldVisitor
import com.sickworm.intellij.jugg.org.objectweb.asm.MethodVisitor
import com.sickworm.intellij.jugg.org.objectweb.asm.Opcodes
import com.sickworm.intellij.jugg.org.objectweb.asm.Type
import com.sickworm.intellij.jugg.project.info.ModuleInfo
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.jar.JarFile

/** Prepares the project's ARouter processor and its Fragment/Jetifier compatibility environment. */
internal class ARouterProcessorCompatibility {
    data class ProjectProcessor(val jar: File, val names: List<String>)
    fun projectProcessor(paths: List<File>, module: ModuleInfo): ProjectProcessor {
        val candidates = paths.mapNotNull { jar ->
            val names = processorNames(jar)
            if (names.isEmpty()) null else ProjectProcessor(jar, names)
        }
        check(candidates.size == 1) {
            "Expected one ARouter processor JAR for ${module.name}, found ${candidates.map { it.jar }}; paths=$paths"
        }
        return candidates.single()
    }

    fun projectKspProcessor(paths: List<File>, module: ModuleInfo): ProjectProcessor? {
        val candidates = paths.mapNotNull { file ->
            if (!file.isFile || file.extension != "jar") return@mapNotNull null
            val names = JarFile(file).use { jar ->
                val entry = jar.getJarEntry("META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider")
                    ?: return@use emptyList<String>()
                jar.getInputStream(entry).bufferedReader().use { it.readLines() }
                    .map { it.substringBefore('#').trim() }
                    .filter { it.startsWith("com.alibaba.android.arouter.") }
                    .distinct().onEach { name ->
                        check(jar.getJarEntry("${name.replace('.', '/')}.class") != null) { "ARouter KSP SPI class $name is missing from $file" }
                    }
            }
            if (names.isEmpty()) null else ProjectProcessor(file, names)
        }
        check(candidates.size <= 1) { "Ambiguous ARouter KSP processors for ${module.name}: $candidates" }
        return candidates.singleOrNull()
    }

    fun hasAptProcessor(paths: List<File>): Boolean = paths.any { processorNames(it).isNotEmpty() }

    private fun processorNames(file: File): List<String> {
        if (!file.isFile || !file.extension.equals("jar", ignoreCase = true)) return emptyList()
        val names = AnnotationProcessorClasspath.names(file).filter { it.startsWith("com.alibaba.android.arouter.compiler.processor.") }
        names.forEach { name ->
            check(AnnotationProcessorClasspath.contains(file, "${name.replace('.', '/')}.class")) {
                "ARouter SPI class $name is missing from $file"
            }
        }
        return names
    }

    fun selectProcessor(
        context: ICompileContext,
        module: ModuleInfo,
        original: ProjectProcessor,
        paths: List<File>,
        task: CompileTask,
    ): ProjectProcessor {
        val references = fragmentReferences(original.jar)
        if (references.isEmpty()) return original
        check(references.size == 1) { "ARouter processor has ambiguous Fragment references in ${original.jar}: $references" }
        val target = fragmentTarget(context, module)
        val classpath = context.getModuleDependencies(module, task).map(::File) + paths
        check(classpath.any { containsClass(it, "$target.class") }) {
            "ARouter requires $target for ${module.name}, absent from javac classpath: $classpath"
        }
        if (references.single() == target) return original
        val converted = transformProcessor(context, original.jar, target)
        return ProjectProcessor(converted, original.names)
    }

    private fun fragmentTarget(context: ICompileContext, module: ModuleInfo): String {
        val descriptors = listOf("L$ANDROIDX_FRAGMENT;", "L$SUPPORT_FRAGMENT;")
        val found = context.containsApkClass(descriptors).map { it.className }.toSet()
        val androidx = descriptors[0] in found
        val support = descriptors[1] in found
        val target = if (context.isSingleApk && support && !androidx) SUPPORT_FRAGMENT else ANDROIDX_FRAGMENT
        context.logger.debug("ARouter Fragment selection for ${module.name}: androidx=$androidx, support=$support, " +
                "singleApk=${context.isSingleApk}, target=$target; an empty query can also mean no database result")
        return target
    }

    private fun containsClass(file: File, name: String): Boolean = AnnotationProcessorClasspath.contains(file, name)

    /** Inspects actual class references and reflection strings rather than artifact names or versions. */
    private fun fragmentReferences(file: File): Set<String> {
        val found = linkedSetOf<String>()
        val visitor = fragmentVisitor(found)
        JarFile(file).use { jar ->
            jar.entries().asSequence().filter { it.name.startsWith("com/alibaba/android/arouter/compiler/") && it.name.endsWith(".class") }
                .forEach { entry -> jar.getInputStream(entry).use { ClassReader(it).accept(visitor, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES) } }
        }
        return found
    }

    /** Exact reflection names are observable; dynamically assembled names in forks are not inferred. */
    private fun fragmentVisitor(found: MutableSet<String>): ClassVisitor {
        fun record(value: String?) {
            listOf(ANDROIDX_FRAGMENT, SUPPORT_FRAGMENT).forEach { type ->
                if (value == type || value == type.replace('/', '.') || value?.contains("L$type;") == true) found += type
            }
        }
        return object : ClassVisitor(Opcodes.ASM9) {
            override fun visit(version: Int, access: Int, name: String, signature: String?, superName: String?, interfaces: Array<out String>?) {
                record(superName)
                interfaces.orEmpty().forEach(::record)
                record(signature)
            }
            override fun visitField(access: Int, name: String, descriptor: String, signature: String?, value: Any?): FieldVisitor? {
                record(descriptor)
                record(signature)
                if (value is String) record(value)
                return null
            }
            override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor {
                record(descriptor)
                record(signature)
                return object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitLdcInsn(value: Any) {
                        record(if (value is Type) value.descriptor else value as? String)
                    }
                    override fun visitTypeInsn(opcode: Int, type: String) = record(type)
                    override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
                        record(owner)
                        record(descriptor)
                    }
                    override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
                        record(owner)
                        record(descriptor)
                    }
                }
            }
        }
    }

    private fun transformProcessor(context: ICompileContext, original: File, target: String): File {
        val key = "${GeneratedSourceCheckpoint.digest(listOf(original))}-$target-$JETIFIER_VERSION".replace('/', '_')
        val output = File(context.incrementalDataDir, "arouter/jetifier/$key/compiler.jar")
        if (output.isFile && fragmentReferences(output) == setOf(target) && processorNames(output) == processorNames(original)) return output
        output.parentFile.mkdirs()
        val pending = File(output.parentFile, "compiler.pending.jar")
        try {
            val config = checkNotNull(ConfigParser.loadDefaultConfig()) { "Jetifier default mapping is missing" }
            val processor = Processor.createProcessor4(config, reversedMode = target == SUPPORT_FRAGMENT)
            processor.transform2(setOf(FileMapping(original, pending)), skipLibsWithAndroidXReferences = false)
            check(pending.isFile && fragmentReferences(pending) == setOf(target)) {
                "Jetifier could not produce an ARouter processor for $target from $original"
            }
            check(processorNames(pending) == processorNames(original)) { "Jetifier changed ARouter SPI in $original" }
            Files.move(pending.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
            context.logger.debug("ARouter processor converted for $target: $original -> $output")
            return output
        } finally {
            pending.delete()
        }
    }

    fun replaceProcessorJar(paths: List<File>, original: File, selected: File): List<File> =
        paths.map { if (it.canonicalFile == original.canonicalFile) selected else it }.distinctBy { it.canonicalPath }

    fun processorContext(context: ICompileContext, original: File, selected: File): ICompileContext {
        if (original == selected) return context
        return object : ICompileContext by context {
            override fun getModuleDependencies(moduleInfo: ModuleInfo, task: CompileTask): List<String> =
                replaceProcessorJar(context.getModuleDependencies(moduleInfo, task).map(::File), original, selected).map { it.path }
        }
    }

    companion object {
        private const val ANDROIDX_FRAGMENT = "androidx/fragment/app/Fragment"
        private const val SUPPORT_FRAGMENT = "android/support/v4/app/Fragment"
        const val JETIFIER_VERSION = "1.0.0-beta10"
    }
}
