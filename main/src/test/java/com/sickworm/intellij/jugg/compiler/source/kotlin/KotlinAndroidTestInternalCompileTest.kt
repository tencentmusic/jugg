package com.sickworm.intellij.jugg.compiler.source.kotlin

import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.mock.StdLogger
import com.sickworm.intellij.jugg.project.data.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.project.data.ModuleDependency
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real compiler regressions for androidTest access to its owner's internal declarations. */
class KotlinAndroidTestInternalCompileTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()
    private val logger = StdLogger("KotlinAndroidTestInternalCompileTest")

    @Test
    fun `androidTest can read and update owner internal members`() {
        val fixture = fixture()
        assertCompiled(compile(fixture, fixture.test, "Probe", probeSource))
    }

    @Test
    fun `androidTest cannot access internal members of another dependency`() {
        val fixture = fixture()
        val other = fixture.owner.copy(
            name = "other", gradleModuleName = "other", moduleRootDir = File(fixture.root, "other"),
            buildPathInfo = ModuleBuildPathInfo(fixture.root, File(fixture.root, "other"), "debug", buildDirRelativePath = ""),
        )
        fixture.modules[other.name] = other
        assertCompiled(compile(fixture, other, "Other", "package other; internal object Secret"))
        val test = fixture.test.copy(moduleDependencies = listOf(ModuleDependency(other.name), ModuleDependency(fixture.owner.name)))
        val result = compile(fixture, test, "Forbidden", "package probe; fun forbidden() { println(other.Secret) }")
        assertFalse(result.isAllSuccess)
        val errors = result.failedFiles.joinToString { it.getFailure().errorMessages }
        assertTrue(errors.contains("internal"), errors)
    }

    @Test
    fun `androidTest keeps access to its own internal baseline`() {
        val fixture = fixture()
        assertCompiled(compile(fixture, fixture.test, "Helper", "package probe; internal fun helper() = 42"))
        assertCompiled(compile(fixture, fixture.test, "UseHelper", "package probe; fun useHelper() = helper()"))
    }

    @Test
    fun `ordinary incremental compilation still accesses its own internal baseline`() {
        val fixture = fixture()
        assertCompiled(compile(fixture, fixture.owner, "Probe", probeSource))
    }

    private fun fixture(): Fixture {
        val root = temporaryFolder.newFolder()
        val ownerRoot = File(root, "library").apply { mkdirs() }
        val owner = ModuleInfo.virtualModule.copy(
            name = "library", gradleModuleName = "library", moduleRootDir = ownerRoot,
            projectRootDir = root, buildVariant = "debug", kotlinJvmTarget = "1.8",
            buildPathInfo = ModuleBuildPathInfo(root, ownerRoot, "debug", buildDirRelativePath = ""),
        )
        val test = owner.copy(
            name = "library.androidTest", buildVariant = "debugAndroidTest",
            instrumentationTargetPackage = "sample.test",
            buildPathInfo = owner.buildPathInfo.copy(buildVariant = "debugAndroidTest"),
            moduleDependencies = listOf(ModuleDependency(owner.name)),
        )
        val fixture = Fixture(root, owner, test, linkedMapOf(owner.name to owner, test.name to test))
        assertCompiled(compile(fixture, owner, "Manager", """
            package sample
            internal class Manager { internal var listener: (() -> Unit)? = null }
            object Provider { internal val transport = Manager() }
        """.trimIndent()))
        return fixture
    }

    private fun compile(fixture: Fixture, module: ModuleInfo, name: String, source: String): CompileResult {
        val sourceDir = File(fixture.root, "sources/${module.name}/$name").apply { mkdirs() }
        val file = File(sourceDir, "$name.kt").apply { writeText(source) }
        val context = mock<ICompileContext>()
        whenever(context.projectDir).thenReturn(fixture.root)
        whenever(context.tempCompileDir).thenReturn(File(fixture.root, "tmp").apply { mkdirs() })
        whenever(context.modules).thenReturn(fixture.modules)
        val stdlib = File(Unit::class.java.protectionDomain.codeSource.location.toURI())
        whenever(context.getModuleDependencies(any(), any())).thenReturn(
            fixture.modules.values.map { it.buildPathInfo.kotlinClassPath.absolutePath } + stdlib.absolutePath,
        )
        val task = CompileTask(
            listOf(CompileFile(CompileFile.Type.Kotlin, file, sourceDir, module)),
            File(fixture.root, "output/${module.name}/$name").apply { mkdirs() }, CompileStatusHolder.DEFAULT,
        )
        return KotlinCompilerInvoker().compile(context, module, task, logger, KotlinCompilerInvoker.Options(
            forceUseEmbeddedKotlinCompiler = true, isCanAutoRetry = false, javaSourceDirs = emptyList(),
        ))
    }

    private fun assertCompiled(result: CompileResult) {
        assertTrue(result.isAllSuccess, result.failedFiles.joinToString { it.getFailure().errorMessages })
        assertTrue(result.outputs.any { it.file.extension == "class" && it.file.length() > 0 })
    }

    private data class Fixture(
        val root: File, val owner: ModuleInfo, val test: ModuleInfo, val modules: MutableMap<String, ModuleInfo>,
    )

    private val probeSource = """
        package probe
        import sample.Provider
        fun probe() { val manager = Provider.transport; manager.listener = {}; manager.listener = null }
    """.trimIndent()
}
