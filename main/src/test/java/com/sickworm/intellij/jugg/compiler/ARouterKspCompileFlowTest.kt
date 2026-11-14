package com.sickworm.intellij.jugg.compiler

import com.intellij.openapi.Disposable
import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.compiler.source.SourceCompiler
import com.sickworm.intellij.jugg.mock.SimpleCompileContext
import com.sickworm.intellij.jugg.mock.StdLogger
import com.sickworm.intellij.jugg.project.runtime.JuggPathManager
import com.sickworm.intellij.jugg.project.info.ProjectInfoSerializer
import com.sickworm.intellij.jugg.project.info.JuggProjectInfo
import com.sickworm.intellij.jugg.project.info.ModuleInfo
import org.junit.Assume.assumeTrue
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Replays a real KMP project processor through Gradle, project-info collection and Jugg compilation. */
class ARouterKspCompileFlowTest {
    @get:Rule val temporary = TemporaryFolder()
    private val logger = StdLogger("ARouterKspCompileFlowTest")

    @Ignore("Historical negative reproduction; positive engineering Flow is deferred by user request")
    @Test
    fun kmpProjectKspRouteChangeFailsWithoutPublishingStaleIndexes() {
        val project = copyFixture()
        val info = assembleAndReadProject(project)
        val module = info.modules.getValue("module.debug")
        assertKspBaseline(project, module)
        val source = File(module.moduleRootDir,
            "src/androidMain/kotlin/com/tencent/yb/module/debug/YBJuggRouteDemoFragment.kt")
        val sourceRoot = checkNotNull(module.sourceDirs.filter { source.toPath().startsWith(it.toPath()) }
            .maxByOrNull { it.path.length })
        val indexes = File(module.buildPathInfo.generatedSourcePath, "ksp/android/androidDebug/kotlin")
            .walkTopDown().filter { it.isFile && it.extension == "kt" }.associateWith { it.readBytes() }
        val context = createContext(project, info)
        val parent = object : Disposable { override fun dispose() = Unit }
        source.writeText(source.readText().replace("RoutePaths.DEMO", "\"/debug/ksp-after\""))

        val compiler = SourceCompiler(context, parent)
        try {
            val result = compiler.compile(CompileTask(
                listOf(CompileFile(CompileFile.Type.Kotlin, source, sourceRoot, module)),
                File(project, "build/reproduction/staging"), CompileStatusHolder.DEFAULT,
            ))
            // This reproducer protects fail-closed behavior until project KSP regeneration is supported.
            assertFalse(result.isAllSuccess, "A changed route must not succeed with the old KSP indexes")
            assertEquals(listOf(source), result.failedFiles.map { it.getFailure().file.file })
            assertTrue(result.failedFiles.single().getFailure().errorMessages.contains("ARouter"))
            assertTrue(result.outputs.isEmpty(), "Failed generation must not publish deployable output")
            indexes.forEach { (file, bytes) -> assertTrue(bytes.contentEquals(file.readBytes())) }
        } finally {
            compiler.dispose()
        }
    }

    private fun copyFixture(): File {
        val fixture = System.getenv("JUGG_AROUTER_KSP_PROJECT")?.let(::File)
        assumeTrue("Set JUGG_AROUTER_KSP_PROJECT to the supplied KMP/KSP reproduction project",
            fixture?.isDirectory == true)
        val root = checkNotNull(fixture)
        val project = temporary.newFolder("project")
        root.walkTopDown().onEnter { it.name !in setOf("build", ".gradle", ".kotlin", ".idea") }
            .filter { it.isFile && it.name != "local.properties" }.forEach { file ->
                file.copyTo(File(project, file.relativeTo(root).path).apply { parentFile.mkdirs() })
            }
        File(project, "gradlew").setExecutable(true)
        val sdk = checkNotNull(System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT"))
        File(project, "local.properties").writeText("sdk.dir=$sdk\n")
        return project
    }

    /** Runs the actual project processor before loading the snapshot produced by the current init script. */
    private fun assembleAndReadProject(project: File): JuggProjectInfo {
        val script = File(project, "readProjectInfo.gradle.kts")
        script.writeText(checkNotNull(javaClass.getResource("/gradle/readProjectInfo.gradle.kts")).readText())
        val runtime = File(project, ".gradle/jugg/jugg-runtime.jar").apply { parentFile.mkdirs() }
        JarOutputStream(runtime.outputStream()).use { }
        val process = ProcessBuilder(
            File(project, "gradlew").path, ":app:assembleDebug", ":module:debug:writeKspEnvironment",
            "-I", script.path, "--no-daemon", "--console=plain", "--stacktrace",
            "-Pjugg.inject.application.enable=false",
        ).directory(project).redirectErrorStream(true).apply {
            environment()["JAVA_HOME"] = System.getProperty("java.home")
        }.start()
        val output = process.inputStream.bufferedReader().readText()
        File(project, "baseline.log").writeText(output)
        assertEquals(0, process.waitFor(), "The real KSP Gradle baseline failed:\n$output")
        return assertNotNull(ProjectInfoSerializer(JuggPathManager(project).gradleProjectInfoFile, logger)
            .load(isSkipVersionCheck = true))
    }

    private fun assertKspBaseline(project: File, module: ModuleInfo) {
        assertEquals(ModuleInfo.Type.Library, module.moduleType)
        assertTrue(module.kotlinCommonSourceDirs.any { it.path.endsWith("src/commonMain/kotlin") })
        assertTrue(module.annotationProcessorDependencies.isEmpty())
        assertTrue(module.kaptDependencies.isEmpty())
        val paths = File(module.buildPathInfo.buildDir, "ksp-processor-path.txt").readLines().map(::File)
        val processor = paths.single { it.name == "arouter-ksp.jar" }
        JarFile(processor).use { jar ->
            val spi = "META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider"
            val provider = jar.getInputStream(assertNotNull(jar.getJarEntry(spi))).bufferedReader().readText().trim()
            assertEquals("com.alibaba.android.arouter.ksp.ARouterSymbolProcessorProvider", provider)
        }
        val group = File(module.buildPathInfo.generatedSourcePath,
            "ksp/android/androidDebug/kotlin/com/tencent/yb/routes/ARouter\$\$Group\$\$debug.kt")
        assertTrue(group.readText().contains("/debug/ksp-before"))
        assertFalse(group.readText().contains("/debug/ksp-after"))
        assertTrue(File(project, "app/build/outputs/apk/debug/app-debug.apk").isFile)
        val businessClass = "com/tencent/yb/module/debug/YBJuggRouteDemoFragment.class"
        assertTrue(module.buildPathInfo.allClassPath.any { File(it, businessClass).isFile })
    }

    private fun createContext(project: File, info: JuggProjectInfo): SimpleCompileContext {
        val sdk = File(checkNotNull(System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")))
        val app = info.modules.getValue("app")
        val modules = linkedMapOf(app.name to app).apply { putAll(info.modules) }
        return SimpleCompileContext(
            logger = logger,
            tempCompileDir = File(project, "build/reproduction/compiled"),
            tempModuleDir = File(project, "build/reproduction/temp"),
            androidHome = sdk,
            androidJar = File(sdk, "platforms/android-36/android.jar"),
            modules = modules,
            apkInfos = listOf(ApkInfo(File(project, "app/build/outputs/apk/debug/app-debug.apk"),
                "com.sickworm.jugg.arouterksp")),
            projectDir = project,
            deployedFiles = mutableListOf(),
            incrementalDataDir = File(project, "build/reproduction/incremental"),
        )
    }
}
