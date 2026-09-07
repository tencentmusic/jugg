package com.sickworm.intellij.jugg.compile

import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.compiler.source.DexCompiler
import com.sickworm.intellij.jugg.compiler.source.JavaCompiler
import com.sickworm.intellij.jugg.deploy.classSigName
import com.sickworm.intellij.jugg.deploy.data.ApkParser
import com.sickworm.intellij.jugg.mock.*
import com.sickworm.intellij.jugg.org.objectweb.asm.ClassWriter
import com.sickworm.intellij.jugg.org.objectweb.asm.Opcodes
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DexCompileTest {

    private val dexCompiler = DexCompiler(context, mockParentDisposable)

    @Before
    fun init() {
        clearBuild()
    }

    private val classTask = CompileTask(
        listOf(
            CompileFile(
                CompileFile.Type.Class,
                File(assetsAndroidDir, "app/build/intermediates/javac/debug/classes/com/example/myapplication/MainActivity2.class"),
                File(assetsAndroidDir, "app/build/intermediates/javac/debug/classes/"),
                context.tempModule,
            ),
            CompileFile(
                CompileFile.Type.Class,
                File(assetsAndroidDir, "app/build/intermediates/javac/debug/classes/com/example/myapplication/ABC.class"),
                File(assetsAndroidDir, "app/build/intermediates/javac/debug/classes/"),
                context.tempModule,
            )
        ),
        stagingDir,
    )

    @Test
    fun compileClasses() {
        val task = classTask
        val result = dexCompiler.compile(task)
        assertCompileResult(task, result)
    }

    private val jarsTask = CompileTask(
        listOf(
            CompileFile(
                CompileFile.Type.Class,
                File(assetsLibDir, "reactive-streams-1.0.3.jar"),
                assetsLibDir,
                context.tempModule,
            ).withDependencyName("org.reactivestreams:reactive-streams:1.0.3@aar"),
            CompileFile(
                CompileFile.Type.Class,
                File(assetsLibDir, "rxjava-3.0.12.jar"),
                assetsLibDir,
                context.tempModule,
            ).withDependencyName("io.reactivex.rxjava3:rxjava:3.0.12@aar"),
        ),
        stagingDir,
    )

    @Test
    fun compileJars() {
        val task = jarsTask
        val result = dexCompiler.compile(task)
        assertCompileResult(task, result)
    }

    @Test
    fun compileClassesAndJars() {
        val task = jarsTask + classTask
        val result = dexCompiler.compile(task)
        assertCompileResult(task, result)
    }

    @Test
    fun compileJarsWithOldJar() {
        val jarTask = CompileTask(
            listOf(
                CompileFile(
                    CompileFile.Type.Class,
                    File(assetsLibDir, "rxjava-3.1.8.jar"),
                    assetsLibDir,
                    context.tempModule,
                ).withDependencyName("io.reactivex.rxjava3:rxjava:3.1.8@aar")
                    .withOldJar(File(assetsLibDir, "rxjava-3.0.12.jar")),
            ),
            stagingDir,
        )

        val result = dexCompiler.compile(jarTask)
        assertCompileResult(jarTask, result)

        val parsedDex = ApkParser().parseDexFiles(result.outputs.map { it.file })
        assertEquals(1, parsedDex.classDeployItems.size)
        assertEquals(true, parsedDex.classDeployItems.first().isMultipleDex)
        assertEquals(362 + 8, parsedDex.classDeployItems.first().classNodes.size) // 362 is changed classes, 8 is desugared classes
    }

    @Test
    fun compileJarsWithSameOldJar() {
        val jarTask = CompileTask(
            listOf(
                CompileFile(
                    CompileFile.Type.Class,
                    File(assetsLibDir, "rxjava-3.0.12.jar"),
                    assetsLibDir,
                    context.tempModule,
                ).withDependencyName("io.reactivex.rxjava3:rxjava:3.1.8@aar")
                    .withOldJar(File(assetsLibDir, "rxjava-3.0.12.jar")),
            ),
            stagingDir,
        )

        val result = dexCompiler.compile(jarTask)
        assertTrue(result.isAllSuccess)
        assertEquals(0, result.outputs.size)
    }

    @Test
    fun compileJarWhenNestHostChanges() {
        val oldJar = File(buildDir, "old-nest.jar")
        val newJar = File(buildDir, "new-nest.jar")
        writeNestJar(oldJar, false)
        writeNestJar(newJar, true)
        val jarTask = CompileTask(
            listOf(
                CompileFile(
                    CompileFile.Type.Class,
                    newJar,
                    buildDir,
                    context.tempModule,
                ).withDependencyName("test:nest:2.0")
                    .withOldJar(oldJar),
            ),
            stagingDir,
        )

        val result = dexCompiler.compile(jarTask)

        assertCompileResult(jarTask, result)
    }

    @Test
    fun dexCoreLibraryDesugar() {
        val javaCompiler = JavaCompiler(context, mockParentDisposable)
        val javaTask = CompileTask.singleJavaFile(File(projectInfo.projectRoot,
            "app/src/main/java/com/sickworm/jugg/demo/testcase/desugar/JavaInvoker.java"), stagingDir)
        val result = javaCompiler.compile(javaTask)
        assert(result.isAllSuccess)

        val context = context
        val dexCompiler = DexCompiler(context, mockParentDisposable)
        context.desugarInfo = DesugarInfo(
            emptyList(), emptyMap(), true, File(assetsAssetsDir, "desugar.json").readText()
        )

        val dexTask = CompileTask(
            result.outputs.map {
                CompileFile(CompileFile.Type.Class, it.file, it.baseDir, context.tempModule)
            },
            stagingDir,
        )
        val dexResult = dexCompiler.compile(dexTask)
        assertCompileResult(dexTask, dexResult)
    }


    private fun assertCompileResult(task: CompileTask, result: CompileResult) {
        val mapper: OutputFileMapper = { compileFile ->
            if (compileFile.file.extension == "class") {
                listOf(
                    CompileOutput(
                        CompileOutput.Type.Dex,
                        compileFile.file.changeBaseDir(compileFile.baseDir, stagingDir, "dex"),
                        stagingDir,
                    )
                )
            } else {
                listOf(
                    CompileOutput(
                        CompileOutput.Type.Dex,
                        File(stagingDir, compileFile.jarDexFileName),
                        stagingDir,
                    )
                )
            }
        }
        assertCompileResult(task, result, mapper)
    }

    private fun writeNestJar(jar: File, changedHost: Boolean) {
        jar.parentFile.mkdirs()
        JarOutputStream(jar.outputStream()).use { output ->
            output.putNextEntry(JarEntry("test/NestHost.class"))
            output.write(createNestHost(changedHost))
            output.closeEntry()
            output.putNextEntry(JarEntry("test/NestHost\$Member.class"))
            output.write(createNestMember())
            output.closeEntry()
        }
    }

    private fun createNestHost(changed: Boolean): ByteArray {
        val writer = newClass("test/NestHost")
        writer.visitNestMember("test/NestHost\$Member")
        if (changed) {
            writer.visitMethod(Opcodes.ACC_PUBLIC, "changed", "()V", null, null).apply {
                visitCode()
                visitInsn(Opcodes.RETURN)
                visitMaxs(0, 0)
                visitEnd()
            }
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun createNestMember(): ByteArray {
        val writer = newClass("test/NestHost\$Member")
        writer.visitNestHost("test/NestHost")
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun newClass(name: String): ClassWriter {
        return ClassWriter(ClassWriter.COMPUTE_MAXS).apply {
            visit(Opcodes.V11, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, name, null, "java/lang/Object", null)
            visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
                visitCode()
                visitVarInsn(Opcodes.ALOAD, 0)
                visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
                visitInsn(Opcodes.RETURN)
                visitMaxs(0, 0)
                visitEnd()
            }
        }
    }
}
