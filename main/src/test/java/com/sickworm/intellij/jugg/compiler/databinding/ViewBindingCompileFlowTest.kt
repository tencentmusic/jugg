package com.sickworm.intellij.jugg.compiler.databinding

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.compiler.CompileStatusHolder
import com.sickworm.intellij.jugg.compiler.CompileTask
import com.sickworm.intellij.jugg.mock.SimpleCompileContext
import com.sickworm.intellij.jugg.mock.StdLogger
import com.sickworm.intellij.jugg.project.info.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.project.info.ModuleInfo
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Protects the resource-to-Kotlin compile flow when plain layouts generate ViewBinding sources. */
class ViewBindingCompileFlowTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun viewBindingModuleCanResolveNewField() = checkNewField(dataBinding = false)

    @Test
    fun dataBindingModuleCanResolveNewViewBindingField() = checkNewField(dataBinding = true)

    @Test
    fun dataBindingLayoutStillProducesProcessorTrigger() {
        val context = createContext(dataBinding = true)
        val module = context.applicationModule
        val layout = write(module.moduleRootDir, "src/main/res/layout/screen.xml", """
            <layout xmlns:android="http://schemas.android.com/apk/res/android">
                <data/>
                <LinearLayout android:layout_width="match_parent" android:layout_height="match_parent"/>
            </layout>
        """.trimIndent())
        val parent = object : Disposable { override fun dispose() {} }
        try {
            val result = DataBindingGenBaseClassesCompiler(context, parent).doModuleCompile(layoutTask(layout, context), module)
            assertTrue(result.isAllSuccess, result.toString())
            val args = DataBindingArgsManager(context, module)
            assertEquals(listOf(args.dataBindingAptSourceTrigger), result.outputs.filter { it.type == CompileOutput.Type.Java }.map { it.file })
        } finally {
            Disposer.dispose(parent)
        }
    }

    private fun checkNewField(dataBinding: Boolean) {
        val context = createContext(dataBinding)
        val module = context.applicationModule
        val sourceRoot = module.sourceDirs.single()
        val baselineRoot = File(module.buildPathInfo.generatedSourcePath, "data_binding_base_class_source_out/debug/out")
        write(baselineRoot, "demo/databinding/ScreenBinding.java",
            "package demo.databinding; public class ScreenBinding { public android.widget.TextView existingTitle; }")
        write(sourceRoot, "androidx/viewbinding/ViewBinding.java",
            "package androidx.viewbinding; public interface ViewBinding { android.view.View getRoot(); }")
        write(sourceRoot, "androidx/annotation/NonNull.java", "package androidx.annotation; public @interface NonNull {}")
        write(sourceRoot, "androidx/annotation/Nullable.java", "package androidx.annotation; public @interface Nullable {}")
        write(sourceRoot, "demo/R.java",
            "package demo; public final class R { public static class id { public static int new_title=1; } public static class layout { public static int screen=1; } }")
        val layout = write(module.moduleRootDir, "src/main/res/layout/screen.xml", """
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent" android:layout_height="match_parent">
                <TextView android:id="@+id/new_title" android:layout_width="wrap_content" android:layout_height="wrap_content"/>
            </LinearLayout>
        """.trimIndent())
        val parent = object : Disposable { override fun dispose() {} }
        try {
            val result = DataBindingGenBaseClassesCompiler(context, parent).doModuleCompile(layoutTask(layout, context), module)
            assertTrue(result.isAllSuccess, result.toString())
            assertEquals(listOf("ScreenBinding.java"), result.outputs.filter { it.type == CompileOutput.Type.Java }.map { it.file.name })
            val kotlinFile = write(sourceRoot, "Reader.kt",
                "fun title(binding: demo.databinding.ScreenBinding): android.widget.TextView = binding.newTitle")
            compileKotlin(kotlinFile, listOf(sourceRoot) + context.getGeneratedSourcePaths(module), context.androidJar)
        } finally {
            Disposer.dispose(parent)
        }
    }

    private fun createContext(dataBinding: Boolean): SimpleCompileContext {
        val root = temporaryFolder.root
        val moduleRoot = File(root, "app")
        val module = ModuleInfo.virtualModule.copy(
            name = "app", namespace = "demo", projectRootDir = root, moduleRootDir = moduleRoot,
            sourceDirs = listOf(File(moduleRoot, "src/main/java")),
            isUseViewBinding = true, isUseDataBinding = dataBinding,
            buildPathInfo = ModuleBuildPathInfo(root, moduleRoot, "debug", buildDirRelativePath = ""),
        )
        val sdk = File(requireNotNull(System.getenv("ANDROID_HOME")) { "ANDROID_HOME is required" })
        return SimpleCompileContext(
            logger = StdLogger("ViewBindingCompileFlow"), tempCompileDir = File(root, "compile"),
            tempModuleDir = File(root, "temp_module"), androidHome = sdk,
            androidJar = File(sdk, "platforms/android-33/android.jar"), modules = mapOf("app" to module),
            apkInfos = listOf(ApkInfo(File(root, "baseline.apk"), "demo")), projectDir = root,
            deployedFiles = mutableListOf(), incrementalDataDir = File(root, "incremental"),
        )
    }

    private fun layoutTask(layout: File, context: SimpleCompileContext): CompileTask {
        val module = context.applicationModule
        return CompileTask(
            listOf(CompileFile(CompileFile.Type.Resource, layout, File(module.moduleRootDir, "src/main/res"), module)),
            File(temporaryFolder.root, "output"), CompileStatusHolder.DEFAULT,
        )
    }

    private fun compileKotlin(source: File, roots: List<File>, androidJar: File) {
        val messages = ByteArrayOutputStream()
        val stdlib = File(kotlin.Unit::class.java.protectionDomain.codeSource.location.toURI())
        val exitCode = PrintStream(messages).use { output ->
            K2JVMCompiler().exec(output, source.path,
                "-no-stdlib", "-no-reflect", "-classpath", androidJar.path + File.pathSeparator + stdlib.path,
                "-Xjava-source-roots=" + roots.joinToString(",") { it.path },
                "-d", File(temporaryFolder.root, "classes").path)
        }
        assertEquals(ExitCode.OK, exitCode, messages.toString())
    }

    private fun write(root: File, path: String, text: String): File = File(root, path).apply {
        parentFile.mkdirs()
        writeText(text)
    }
}
