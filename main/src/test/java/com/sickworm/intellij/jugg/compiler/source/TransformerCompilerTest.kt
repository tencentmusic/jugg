package com.sickworm.intellij.jugg.compiler.source

import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.CompileStatusHolder
import com.sickworm.intellij.jugg.compiler.CompileTask
import com.sickworm.intellij.jugg.compiler.ClassPreparation
import com.sickworm.intellij.jugg.compiler.ClassPreparationInput
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.deploy.data.ClassAnalysisBatch
import com.sickworm.intellij.jugg.deploy.data.ClassFileParser
import com.sickworm.intellij.jugg.org.objectweb.asm.ClassReader
import com.sickworm.intellij.jugg.org.objectweb.asm.ClassWriter
import com.sickworm.intellij.jugg.org.objectweb.asm.Opcodes
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class TransformerCompilerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `loads generated Hilt base from ordered dependency jar`() {
        val module = ModuleInfo.virtualModule
        val entryFile = writeClass("test/Entry.class", entryPointClass())
        val jarGeneratedBase = simpleClass("test/Hilt_Entry", "test/JarBase")
        val dependencyJar = writeJar("generated-base.jar", "test/Hilt_Entry.class", jarGeneratedBase)
        val shadowDir = temporaryFolder.newFolder("shadow-directory")
        File(shadowDir, "test/Hilt_Entry.class").apply {
            parentFile.mkdirs()
            writeBytes(simpleClass("test/Hilt_Entry", "test/DirectoryBase"))
        }
        val task = compileTask(entryFile, module)
        val context = context(task, module, listOf(dependencyJar.path, shadowDir.path))

        val prepared = TransformerCompiler(context, mock()).transform(task, module, preparation(task.files.single()))

        assertEquals("test/Hilt_Entry", ClassReader(prepared.files.single().file.readBytes()).superName)
        val requiredClasspath = prepared.requiredClasspathFiles.single()
        assertEquals("test/Hilt_Entry", ClassReader(requiredClasspath.file.readBytes()).className)
        assertEquals("test/JarBase", ClassReader(requiredClasspath.file.readBytes()).superName)
    }

    @Test
    fun `preserves classpath read failure when generated Hilt base cannot be resolved`() {
        val module = ModuleInfo.virtualModule
        val entryFile = writeClass("test/Entry.class", entryPointClass())
        val invalidJar = File(temporaryFolder.root, "invalid.jar").apply { writeText("not a zip") }
        val task = compileTask(entryFile, module)
        val context = context(task, module, listOf(invalidJar.path))

        val failure = assertFailsWith<IllegalStateException> {
            TransformerCompiler(context, mock()).transform(task, module, preparation(task.files.single()))
        }

        assertContains(failure.message.orEmpty(), "classpath read failed")
        assertContains(failure.message.orEmpty(), "run a full Gradle build")
        assertNotNull(failure.cause)
    }

    private fun context(
        task: CompileTask,
        module: ModuleInfo,
        classpath: List<String>,
    ): ICompileContext {
        val context = mock<ICompileContext>()
        whenever(context.tempCompileDir).thenReturn(temporaryFolder.newFolder("temp-${classpath.size}"))
        whenever(context.getModuleDependencies(eq(module), eq(task))).thenReturn(classpath)
        return context
    }

    private fun compileTask(entryFile: File, module: ModuleInfo): CompileTask {
        val inputDir = entryFile.parentFile.parentFile
        return CompileTask(
            listOf(CompileFile(CompileFile.Type.Class, entryFile, inputDir, module)),
            temporaryFolder.newFolder("output-${entryFile.length()}"),
            CompileStatusHolder.DEFAULT,
        )
    }

    private fun preparation(file: CompileFile): ClassPreparation {
        val bytes = file.file.readBytes()
        return ClassPreparation(
            inputs = listOf(
                ClassPreparationInput(
                    file,
                    bytes,
                    ClassAnalysisBatch.from(listOf(ClassFileParser.analyze(bytes))),
                ),
            ),
            parsedByteCount = bytes.size.toLong(),
        )
    }

    private fun writeClass(relativePath: String, bytes: ByteArray): File {
        return File(temporaryFolder.root, relativePath).apply {
            parentFile.mkdirs()
            writeBytes(bytes)
        }
    }

    private fun writeJar(name: String, entryName: String, bytes: ByteArray): File {
        return File(temporaryFolder.root, name).apply {
            JarOutputStream(outputStream()).use { output ->
                output.putNextEntry(JarEntry(entryName))
                output.write(bytes)
                output.closeEntry()
            }
        }
    }

    private fun entryPointClass(): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "test/Entry", null, "test/Base", null)
        writer.visitAnnotation("Ldagger/hilt/android/AndroidEntryPoint;", false).visitEnd()
        writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "test/Base", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun simpleClass(name: String, superName: String): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, superName, null)
        writer.visitEnd()
        return writer.toByteArray()
    }
}
