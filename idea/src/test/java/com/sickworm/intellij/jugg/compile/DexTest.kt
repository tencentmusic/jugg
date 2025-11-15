package com.sickworm.intellij.jugg.compile

import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.compiler.source.DexFileMaker
import com.sickworm.intellij.jugg.deploy.classNameToPath
import com.sickworm.intellij.jugg.deploy.data.ApkParser
import com.sickworm.intellij.jugg.deploy.desugarDefaultInterfaceSuffix
import com.sickworm.intellij.jugg.mock.*
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DexTest {

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
        val baseJavaDir = assetsAndroidDir.resolve("app/build/intermediates/javac/debug/classes")
        val baseKotlinDir = assetsAndroidDir.resolve("app/build/tmp/kotlin-classes/debug")
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