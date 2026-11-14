package com.sickworm.intellij.jugg.compiler.source

import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.CompileResult
import com.sickworm.intellij.jugg.compiler.CompileStatusHolder
import com.sickworm.intellij.jugg.compiler.CompileTask
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.compiler.source.kotlin.KotlinCompilerInvoker
import com.sickworm.intellij.jugg.mock.StdLogger
import com.sickworm.intellij.jugg.compiler.context.BaseCompileContext
import com.sickworm.intellij.jugg.project.info.LibraryDependency
import com.sickworm.intellij.jugg.project.info.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.project.info.ModuleInfo
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.ToolProvider
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Exercises ROM API recovery through real Java/Kotlin invokers, through context initialization and real compilers, without a device. */
@RunWith(Parameterized::class)
class RomApiCompileTest(private val language: Language, private val scenario: Scenario) {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val androidHome = File(requireNotNull(System.getenv("ANDROID_HOME")))
    private val androidJar = File(androidHome, "platforms/android-35/android.jar")
    private val frameworkJar by lazy { buildFrameworkJar() }
    private val logger = StdLogger("RomApiCompileTest").apply { isEnableDebug = false }

    @Test
    fun `ROM APIs compile when framework precedes SDK`() {
        assertCompiled(compile(sdkFirst = false, initializeContext = false))
    }

    @Test
    fun `context initialization prioritizes compile-only ROM APIs without compiler retries`() {
        val baseline = compile(sdkFirst = true, initializeContext = false)
        if (language == Language.JAVA && scenario == Scenario.INDIRECT_SUPERTYPE) {
            // javac resolves the binary nested class directly, so this case needs no recovery.
            assertCompiled(baseline)
        } else {
            assertFalse(baseline.isAllSuccess, "The SDK-first baseline must reproduce $language/$scenario")
            val error = baseline.failedFiles.single().getFailure()
            assertTrue(error.hasDirectSourceDiagnostic, error.errorMessages)
            assertTrue(scenario.symbol in error.errorMessages, error.errorMessages)
        }
        assertCompiled(compile(sdkFirst = true, initializeContext = true))
    }

    private fun assertCompiled(result: CompileResult) {
        val errors = result.failedFiles.joinToString { it.getFailure().errorMessages }
        assertTrue(result.isAllSuccess, "$language/$scenario must compile successfully:\n$errors")
        assertTrue(result.outputs.any { it.file.name == "Use.class" && it.file.length() > 0 })
    }

    private fun compile(sdkFirst: Boolean, initializeContext: Boolean): CompileResult {
        assertTrue(androidJar.isFile, "Missing Android platform: $androidJar")
        val systemModules = File(androidJar.parentFile, "core-for-system-modules.jar")
        assertTrue(systemModules.isFile, "Missing Android Java platform: $systemModules")
        val root = temporaryFolder.newFolder()
        val stdlib = File(Unit::class.java.protectionDomain.codeSource.location.toURI())
        val module = ModuleInfo.virtualModule.copy(
            name = "app",
            moduleType = ModuleInfo.Type.Application,
            projectRootDir = root,
            moduleRootDir = root,
            javaSourceCompatibility = "17",
            javaTargetCompatibility = "17",
            kotlinJvmTarget = "17",
            compileVersion = "35",
            libraryDependencies = listOf(LibraryDependency("framework", frameworkJar), LibraryDependency("stdlib", stdlib)),
            runtimeLibraryDependencies = listOf(LibraryDependency("stdlib", stdlib)),
            buildPathInfo = ModuleBuildPathInfo(root, root, "debug", buildDirRelativePath = ""),
        )
        val sourceDir = File(root, "src").apply { mkdirs() }
        val file = File(sourceDir, "Use.${language.extension}").apply {
            writeText(if (language == Language.JAVA) scenario.javaSource else scenario.kotlinSource)
        }
        val task = CompileTask(
            listOf(CompileFile(language.fileType, file, sourceDir, module)),
            File(root, "output").apply { mkdirs() }, CompileStatusHolder.DEFAULT,
        )
        val dependencies = if (sdkFirst) listOf(androidJar, frameworkJar) else listOf(frameworkJar, androidJar)
        val context = mock<ICompileContext>()
        whenever(context.androidHome).thenReturn(androidHome)
        whenever(context.projectDir).thenReturn(root)
        whenever(context.tempCompileDir).thenReturn(File(root, "tmp").apply { mkdirs() })
        whenever(context.modules).thenReturn(mapOf(module.name to module))
        whenever(context.getModuleDependencies(any(), any())).thenReturn((dependencies + stdlib).map { it.absolutePath })
        val compileContext = if (initializeContext) BaseCompileContext(
            logger = logger,
            androidHome = androidHome,
            projectDir = root,
            tempCompileDir = File(root, "tmp"),
            tempModuleDir = File(root, "temp-module"),
            incrementalDataDir = File(root, "incremental"),
            modules = mapOf(module.name to module),
            cmdCompileEnv = emptyList(),
            scene = ICompileContext.Scene.IDE,
            deployFileManager = mock(),
            deployHistoryManager = mock(),
            customCompilerManager = mock(),
        ) else context
        if (initializeContext) {
            val paths = compileContext.getModuleDependencies(module, task)
            assertTrue(paths.indexOf(frameworkJar.absolutePath) < paths.indexOf(androidJar.absolutePath))
        }
        return when (language) {
            Language.JAVA -> JavaCompilerInvoker().compile(
                compileContext, module, task, logger, JavaCompilerInvoker.Options(isCanAutoRetry = false),
            )
            Language.KOTLIN -> KotlinCompilerInvoker().compile(
                compileContext, module, task, logger, KotlinCompilerInvoker.Options(
                    isCanAutoRetry = false, forceUseEmbeddedKotlinCompiler = true, javaSourceDirs = emptyList(),
                ),
            )
        }
    }

    /** Compiles complete framework classes so the fixtures exercise member lookup, not hand-built bytecode. */
    private fun buildFrameworkJar(): File {
        val root = temporaryFolder.newFolder("framework")
        val classes = File(root, "classes").apply { mkdirs() }
        val sources = frameworkSources.map { (path, source) ->
            File(root, "src/$path").apply {
                parentFile.mkdirs()
                writeText(source.trimIndent())
            }
        }
        val compiler = ToolProvider.getSystemJavaCompiler()
        val diagnostics = DiagnosticCollector<JavaFileObject>()
        compiler.getStandardFileManager(diagnostics, null, null).use { fileManager ->
            val success = compiler.getTask(
                null, fileManager, diagnostics,
                listOf("--release", "8", "-proc:none", "-d", classes.absolutePath), null,
                fileManager.getJavaFileObjectsFromFiles(sources),
            ).call()
            assertTrue(success, diagnostics.diagnostics.joinToString("\n"))
        }
        return File(root, "framework.jar").apply {
            JarOutputStream(outputStream()).use { output ->
                classes.walkTopDown().filter { it.isFile }.forEach { file ->
                    output.putNextEntry(JarEntry(file.relativeTo(classes).invariantSeparatorsPath))
                    output.write(file.readBytes())
                    output.closeEntry()
                }
            }
        }
    }

    /** Source languages exercised by the same ROM API matrix. */
    enum class Language(val extension: String, val fileType: CompileFile.Type) {
        JAVA("java", CompileFile.Type.Java),
        KOTLIN("kt", CompileFile.Type.Kotlin),
    }

    /** Source forms observed in reports and the classpath-order reproductions. */
    enum class Scenario(val symbol: String, val javaSource: String, val kotlinSource: String) {
        HIDDEN_FIELD(
            "HID_HOST",
            reportDemoSource("AccessoriesFragmentCase.java"),
            "class Use { fun value(): Int = android.bluetooth.BluetoothProfile.HID_HOST }",
        ),
        HIDDEN_METHODS(
            "removeDoubleQuotes",
            """
                import android.net.wifi.*;
                public class Use {
                    public void value(WifiManager manager, WifiConfiguration config) {
                        WifiInfo.removeDoubleQuotes("ssid");
                        manager.connect(1, null);
                        manager.forget(1, null);
                        manager.disconnectForNetId(1);
                        config.getLastConnectedTimestamp();
                        manager.ipConfigurationLostReceived(1);
                    }
                }
            """.trimIndent(),
            reportDemoSource("WirelessNetworkManagerCase.kt"),
        ),
        HIDDEN_NESTED_TYPE(
            "SoftApCallback",
            "public class Use { public android.net.wifi.WifiManager.SoftApCallback value; }",
            "class Use(val value: android.net.wifi.WifiManager.SoftApCallback)",
        ),
        METHOD_OVERLOAD(
            "finish",
            "public class Use { public void value(android.app.Activity activity) { activity.finish(1); } }",
            "class Use { fun value(activity: android.app.Activity) { activity.finish(1) } }",
        ),
        CONSTRUCTOR_OVERLOAD(
            "Activity",
            "public class Use { public android.app.Activity value() { return new android.app.Activity(1); } }",
            "class Use { fun value(): android.app.Activity = android.app.Activity(1) }",
        ),
        INDIRECT_SUPERTYPE(
            "SoftApCallback",
            """
                public class Use extends rom.RomSoftApCallback {
                    @Override public void onStateChanged(int state, int reason) {}
                }
            """.trimIndent(),
            """
                class Use : rom.RomSoftApCallback() {
                    override fun onStateChanged(state: Int, reason: Int) {}
                }
            """.trimIndent(),
        ),
    }

    companion object {
        private fun reportDemoSource(name: String): String {
            val root = File(System.getProperty("user.dir"))
            val demo = sequenceOf(root, root.parentFile).map { File(it, "android_demo_project") }
                .first { it.isDirectory }
            val file = File(demo, "app/src/romApi/java/com/sickworm/jugg/demo/testcase/romhiddenapi/$name")
            return file.readText().lineSequence().filterNot { it.startsWith("package ") }.joinToString("\n")
                .replace(name.substringBeforeLast('.'), "Use")
        }

        @JvmStatic
        @Parameterized.Parameters(name = "{0}/{1}")
        fun cases(): List<Array<Any>> = Language.values().flatMap { language ->
            Scenario.values().map { scenario -> arrayOf<Any>(language, scenario) }
        }

        private val frameworkSources = mapOf(
            "android/bluetooth/BluetoothProfile.java" to """
                package android.bluetooth;
                public interface BluetoothProfile { int HID_HOST = 4; }
            """,
            "android/net/wifi/WifiInfo.java" to """
                package android.net.wifi;
                public class WifiInfo {
                    public static String removeDoubleQuotes(String ssid) { return ssid; }
                }
            """,
            "android/net/wifi/WifiConfiguration.java" to """
                package android.net.wifi;
                public class WifiConfiguration {
                    public long getLastConnectedTimestamp() { return 0L; }
                }
            """,
            "android/net/wifi/WifiManager.java" to """
                package android.net.wifi;
                public class WifiManager {
                    public interface ActionListener {}
                    public void connect(int networkId, ActionListener listener) {}
                    public void forget(int networkId, ActionListener listener) {}
                    public void disconnectForNetId(int networkId) {}
                    public void ipConfigurationLostReceived(int networkId) {}
                    public static abstract class SoftApCallback {
                        public abstract void onStateChanged(int state, int reason);
                    }
                }
            """,
            "android/app/Activity.java" to """
                package android.app;
                public class Activity {
                    public Activity() {}
                    public Activity(int displayId) {}
                    public void finish() {}
                    public void finish(int resultCode) {}
                }
            """,
            "rom/RomSoftApCallback.java" to """
                package rom;
                public abstract class RomSoftApCallback extends android.net.wifi.WifiManager.SoftApCallback {}
            """,
        )
    }
}
