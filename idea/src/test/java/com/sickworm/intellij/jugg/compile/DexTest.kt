package com.sickworm.intellij.jugg.compile

import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.compiler.source.DexFileMaker
import com.sickworm.intellij.jugg.deploy.classNameToPath
import com.sickworm.intellij.jugg.deploy.data.ApkParser
import com.sickworm.intellij.jugg.deploy.desugarDefaultInterfaceSuffix
import com.sickworm.intellij.jugg.mock.*
import com.sickworm.intellij.jugg.org.objectweb.asm.ClassWriter
import com.sickworm.intellij.jugg.org.objectweb.asm.Opcodes
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DexTest {

    private val fakeOriginName = "com/android/tools/r8/origin/Origin"
    private val fakeCommandName = "com/android/tools/r8/D8Command"
    private val fakeBuilderName = "com/android/tools/r8/D8Command\$Builder"

    private val javaCompileTest = JavaCompileTest()

    private val minApi = mockModule.minSdkVersion!!.toInt()

    @Before
    fun init() {
        clearBuild()
    }

    @Test
    fun dex() {
        javaCompileTest.javaCompile()
        val task = JavaCompileTest().helloWorldTask
        repeat(100) {
            dexAndCheck(task, deleteAfterBuild = true)
        }
    }

    @Test
    fun dexMultipleFiles() {
        JavaCompileTest().javaCompileMultiFiles()
        val task = JavaCompileTest().multiFilesTask
        dexAndCheck(task, deleteAfterBuild = false)
    }

    @Test
    fun dexWithExternalR8Classpath() {
        javaCompileTest.javaCompile()
        val classesFiles = stagingDir.listFilesRecursively()
        val r8Classpath = File("../main/libs/r8-8.4.21.jar").canonicalFile

        DexFileMaker(logger).dex(
            outputDir = stagingDir,
            classFilesOrDir = classesFiles,
            classpath = emptyList(),
            androidJar = androidJar,
            minApi = minApi,
            agpR8Classpath = r8Classpath,
        )

        assertTrue(stagingDir.listFilesRecursively().any { it.extension == "dex" })
    }

    @Test
    fun dexFallsBackWhenExternalR8ClasspathIsMissing() {
        javaCompileTest.javaCompile()
        val classesFiles = stagingDir.listFilesRecursively()

        DexFileMaker(logger).dex(
            outputDir = stagingDir,
            classFilesOrDir = classesFiles,
            classpath = emptyList(),
            androidJar = androidJar,
            minApi = minApi,
            agpR8Classpath = File(stagingDir, "missing-r8.jar"),
        )

        assertTrue(stagingDir.listFilesRecursively().any { it.extension == "dex" })
    }

    @Test
    fun dexFallsBackWhenExternalR8CompileFails() {
        javaCompileTest.javaCompile()
        val classesFiles = stagingDir.listFilesRecursively()

        DexFileMaker(logger).dex(
            outputDir = stagingDir,
            classFilesOrDir = classesFiles,
            classpath = emptyList(),
            androidJar = androidJar,
            minApi = minApi,
            agpR8Classpath = createFailingR8Classpath(),
        )

        assertTrue(stagingDir.listFilesRecursively().any { it.extension == "dex" })
    }

    private fun createFailingR8Classpath(): File {
        val rootDir = File(buildDir, "failing-r8").apply { deleteRecursively(); mkdirs() }
        val classesDir = File(rootDir, "classes").apply { mkdirs() }
        writeClass(classesDir, fakeOriginName, createOriginClass())
        writeClass(classesDir, fakeCommandName, createCommandClass())
        writeClass(classesDir, fakeBuilderName, createBuilderClass())
        writeClass(classesDir, "com/android/tools/r8/D8", createFailingD8Class())
        writeClass(classesDir, "com/android/tools/r8/Version", createVersionClass())
        return classesDir
    }

    private fun newClass(name: String): ClassWriter {
        return ClassWriter(ClassWriter.COMPUTE_MAXS).apply {
            visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, name, null, "java/lang/Object", null)
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

    private fun writeClass(classesDir: File, name: String, writer: ClassWriter) {
        writer.visitEnd()
        File(classesDir, "$name.class").apply {
            parentFile.mkdirs()
            writeBytes(writer.toByteArray())
        }
    }

    private fun createOriginClass(): ClassWriter = newClass(fakeOriginName).apply {
        visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "root", "()L$fakeOriginName;", null, null).apply {
            visitCode()
            visitTypeInsn(Opcodes.NEW, fakeOriginName)
            visitInsn(Opcodes.DUP)
            visitMethodInsn(Opcodes.INVOKESPECIAL, fakeOriginName, "<init>", "()V", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
    }

    private fun createCommandClass(): ClassWriter = newClass(fakeCommandName).apply {
        visitInnerClass(fakeBuilderName, fakeCommandName, "Builder", Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC)
        visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            "parse",
            "([Ljava/lang/String;L$fakeOriginName;)L$fakeBuilderName;",
            null,
            null,
        ).apply {
            visitCode()
            visitTypeInsn(Opcodes.NEW, fakeBuilderName)
            visitInsn(Opcodes.DUP)
            visitMethodInsn(Opcodes.INVOKESPECIAL, fakeBuilderName, "<init>", "()V", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
    }

    private fun createBuilderClass(): ClassWriter = newClass(fakeBuilderName).apply {
        visitInnerClass(fakeBuilderName, fakeCommandName, "Builder", Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC)
        visitMethod(Opcodes.ACC_PUBLIC, "build", "()L$fakeCommandName;", null, null).apply {
            visitCode()
            visitTypeInsn(Opcodes.NEW, fakeCommandName)
            visitInsn(Opcodes.DUP)
            visitMethodInsn(Opcodes.INVOKESPECIAL, fakeCommandName, "<init>", "()V", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        visitMethod(
            Opcodes.ACC_PUBLIC,
            "addDesugaredLibraryConfiguration",
            "(Ljava/lang/String;)L$fakeBuilderName;",
            null,
            null,
        ).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
    }

    private fun createFailingD8Class(): ClassWriter = newClass("com/android/tools/r8/D8").apply {
        visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "run", "(L$fakeCommandName;)V", null, null).apply {
            visitCode()
            visitTypeInsn(Opcodes.NEW, "java/lang/RuntimeException")
            visitInsn(Opcodes.DUP)
            visitLdcInsn("expected external D8 failure")
            visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                "java/lang/RuntimeException",
                "<init>",
                "(Ljava/lang/String;)V",
                false,
            )
            visitInsn(Opcodes.ATHROW)
            visitMaxs(0, 0)
            visitEnd()
        }
    }

    private fun createVersionClass(): ClassWriter = newClass("com/android/tools/r8/Version").apply {
        visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            "getVersionString",
            "()Ljava/lang/String;",
            null,
            null,
        ).apply {
            visitCode()
            visitLdcInsn("failing-test-runtime")
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
    }

    private fun dexAndCheck(task: CompileTask, deleteAfterBuild: Boolean) {
        val classesFiles = stagingDir.listFilesRecursively()

        // ART TI requires one .dex file only contains one .class file

        val dependencies = task.files.map { it.dependencyPaths }.flatten().toSet()
        DexFileMaker(logger).dex(stagingDir, classesFiles, dependencies, androidJar, minApi)

        classesFiles.forEach { classFile ->
            val dexFile = classFile.changeBaseDir(stagingDir, stagingDir, "dex")
            assertTrue(dexFile.exists() && dexFile.length() > 0)
            if (deleteAfterBuild) {
                dexFile.delete()
            }
        }
    }

    @Test
    fun dexDefaultInterface() {
        dexAndCheckNew(
            "com.sickworm.jugg.demo.testcase.defaultinterface.DefaultInterface",
            listOf(
                "com/sickworm/jugg/demo/testcase/defaultinterface/DefaultInterface.dex",
                "com/sickworm/jugg/demo/testcase/defaultinterface/DefaultInterface\$-CC.dex",
            ),
        )
    }

    @Test
    fun dexImplOfDefaultInterface() {
        dexAndCheckNew(
            "com.sickworm.jugg.demo.testcase.defaultinterface.ImplementClass1",
            listOf(
                "com/sickworm/jugg/demo/testcase/defaultinterface/ImplementClass1.dex",
            ),
            isHasDefaultMethodInvocation = false,
        )

        dexAndCheckNew(
            "com.sickworm.jugg.demo.testcase.defaultinterface.ImplementClass1",
            listOf(
                "com/sickworm/jugg/demo/testcase/defaultinterface/ImplementClass1.dex",
            ),
            dependencies = listOf(
                getClassFile("com.sickworm.jugg.demo.testcase.defaultinterface.DefaultInterface"),
            ),
            isHasDefaultMethodInvocation = true,
        )
    }

    @Test
    fun dexSubclassKeepsInheritedDefaultInterfaceOverride() {
        dexAndCheckNew(
            "com.sickworm.jugg.demo.testcase.defaultinterface.ParentOverrideChildClass",
            listOf(
                "com/sickworm/jugg/demo/testcase/defaultinterface/ParentOverrideChildClass.dex",
            ),
            dependencies = listOf(
                getClassFile("com.sickworm.jugg.demo.testcase.defaultinterface.ParentOverrideDefaultInterface"),
                getClassFile("com.sickworm.jugg.demo.testcase.defaultinterface.ParentOverrideChildInterface"),
                getClassFile("com.sickworm.jugg.demo.testcase.defaultinterface.ParentOverrideBaseClass"),
                getClassFile("com.sickworm.jugg.demo.testcase.defaultinterface.ParentOverrideRootClass"),
            ),
            isHasDefaultMethodInvocation = false,
        )
    }

    private fun dexAndCheckNew(className: String, expect: List<String>,
                               dependencies: List<CompileFile> = emptyList(),
                               isHasDefaultMethodInvocation: Boolean? = null,
    ) {
        val file = getClassFile(className)
        tempCompileDir.mkdirs()
        tempCompileDir.clearDir()
        dependencies.forEach {
            it.file.copyTo(tempCompileDir.resolve(it.file.relativeTo(it.baseDir)))
        }
        DexFileMaker(logger).dex(stagingDir, listOf(file.file), listOf(tempCompileDir.absolutePath), androidJar, minApi)

        val dexFiles = stagingDir.listFilesRecursively()
        assertContentEquals(
            expect,
            dexFiles.map { it.relativeTo(stagingDir).path }
        )

        if (isHasDefaultMethodInvocation != null) {
            dexFiles.forEach {
                assertEquals(
                    isHasDefaultMethodInvocation,
                    checkDefaultMethodDesugar(it),
                    "file: ${it.path} isHasDefaultMethodInvocation: $isHasDefaultMethodInvocation"
                )
            }
        }
    }

    private fun getClassFile(className: String): CompileFile {
        val relativePath = className.classNameToPath
        val buildPathInfo = AssembleAndroidProjectOnce.getProjectInfo().modules.getValue("app").buildPathInfo
        val baseJavaDir = buildPathInfo.javaClassPath
        val baseKotlinDir = buildPathInfo.kotlinClassPath
        if (File(baseJavaDir, relativePath).exists()) {
            val file = File(baseJavaDir, relativePath)
            return CompileFile(
                CompileFile.Type.Class,
                file,
                baseJavaDir,
                mockModule,
            )
        }
        if (File(baseKotlinDir, relativePath).exists()) {
            val file = File(baseKotlinDir, relativePath)
            return CompileFile(
                CompileFile.Type.Class,
                file,
                baseKotlinDir,
                mockModule,
            )
        }

        throw IllegalArgumentException("class $className not found")
    }

    private fun checkDefaultMethodDesugar(file: File): Boolean {
        val parsedDex = ApkParser().parseDexFiles(listOf(file))
        return parsedDex.methodRefs.any {
            it.key.owner.endsWith(desugarDefaultInterfaceSuffix)
        }
    }
}
