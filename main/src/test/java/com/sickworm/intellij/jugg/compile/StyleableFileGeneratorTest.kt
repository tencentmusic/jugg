package com.sickworm.intellij.jugg.compile

import com.sickworm.intellij.jugg.ModuleApkBelongs
import com.sickworm.intellij.jugg.apk.ApkFileUnit
import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.compiler.overlay.StyleableFileGenerator
import com.sickworm.intellij.jugg.mock.StdLogger
import com.sickworm.intellij.jugg.org.objectweb.asm.ClassWriter
import com.sickworm.intellij.jugg.org.objectweb.asm.Opcodes
import com.sickworm.intellij.jugg.project.info.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.project.info.ModuleInfo
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class StyleableFileGeneratorTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val generator = StyleableFileGenerator(StdLogger("StyleableFileGeneratorTest"))

    @Test
    fun `app and library test APKs use their own aggregate R declarations`() {
        val app = module("app")
        val firstTest = module("first.androidTest", "debugAndroidTest")
        val secondTest = module("second.androidTest", "debugAndroidTest")
        writeRJar(app, "AppOnly")
        writeRJar(firstTest, "FirstTestOnly")
        writeRJar(secondTest, "SecondTestOnly")
        val modules = listOf(app, firstTest, secondTest)
        val apks = modules.map(::apk)
        val context = context(app, modules, apks)

        listOf("AppOnly", "FirstTestOnly", "SecondTestOnly").forEachIndexed { index, name ->
            assertEquals("$name:enabled\n", generate(context, apks[index])?.readText())
        }
    }

    @Test
    fun `library test APK can generate styleables without a host application module`() {
        val test = module("library.androidTest", "debugAndroidTest")
        writeRJar(test, "LibraryTest")
        val apk = apk(test)

        assertEquals("LibraryTest:enabled\n", generate(context(null, listOf(test), listOf(apk)), apk)?.readText())
    }

    @Test
    fun `missing test module does not reuse host application styleables`() {
        val app = module("app")
        val test = module("missing.androidTest", "debugAndroidTest")
        writeRJar(app, "AppOnly")
        val apks = listOf(apk(app), apk(test))

        assertNull(generate(context(app, listOf(app), apks), apks.last()))
    }

    @Test
    fun `legacy class output uses namespace when source manifest has no package`() {
        val app = module("app").copy(namespace = "example.resources")
        writeManifest(app, "<manifest/>")
        writeRClass(app, "example.resources", "Namespaced")
        val apk = apk(app)

        assertEquals("Namespaced:enabled\n", generate(context(app, listOf(app), listOf(apk)), apk)?.readText())
    }

    @Test
    fun `namespace takes precedence over legacy manifest package`() {
        val app = module("app").copy(namespace = "example.resources")
        writeManifest(app, "<manifest package=\"example.old\"/>")
        writeRClass(app, "example.resources", "CurrentNamespace")
        writeRClass(app, "example.old", "StaleNamespace")
        val apk = apk(app)

        assertEquals("CurrentNamespace:enabled\n", generate(context(app, listOf(app), listOf(apk)), apk)?.readText())
    }

    @Test
    fun `legacy module without namespace still reads manifest package`() {
        val app = module("app").copy(namespace = null)
        writeManifest(app, "<manifest package=\"example.legacy\"/>")
        writeRClass(app, "example.legacy", "Legacy")
        val apk = apk(app)

        assertEquals("Legacy:enabled\n", generate(context(app, listOf(app), listOf(apk)), apk)?.readText())
    }

    @Test
    fun `test class output uses its module namespace without a source manifest`() {
        val app = module("app")
        val test = module("library.androidTest", "debugAndroidTest").copy(manifestFile = null)
        writeRClass(app, app.namespace!!, "AppOnly")
        writeRClass(test, test.namespace!!, "TestOnly")
        val apks = listOf(apk(app), apk(test))

        assertEquals("TestOnly:enabled\n", generate(context(app, listOf(app, test), apks), apks.last())?.readText())
    }

    @Test
    fun `dynamic feature retains declarations from its own modules`() {
        val app = module("app")
        val feature = module("feature").copy(moduleType = ModuleInfo.Type.DynamicFeature)
        val library = module("library").copy(moduleType = ModuleInfo.Type.Library)
        writeRJar(app, "AppOnly")
        writeRJar(feature, "Feature")
        writeRJar(library, "FeatureLibrary")
        val base = apk(app).files.single()
        val split = ApkFileUnit(app.applicationId!!, "feature", true, File(temporaryFolder.root, "feature.apk"))
        val apk = ApkInfo(listOf(base, split), app.applicationId!!)
        val context = context(app, listOf(app, feature, library), listOf(apk))
        whenever(context.isSingleApk).thenReturn(false)
        val primary = mapOf(app to base, feature to split, library to split)
        whenever(context.moduleBelongsApkMap).thenReturn(ModuleApkBelongs(primary, primary.mapValues { listOf(it.value) }))

        val output = generator.generateStyleableFile(context, File(temporaryFolder.root, "feature-output"), split)

        assertNotNull(output)
        assertEquals(setOf("Feature:enabled", "FeatureLibrary:enabled"), output.readLines().toSet())
    }

    private fun module(name: String, variant: String = "debug"): ModuleInfo {
        val root = File(temporaryFolder.root, name).apply { mkdirs() }
        val isTest = variant.endsWith("AndroidTest")
        return ModuleInfo.virtualModule.copy(
            name = name,
            moduleType = if (isTest) ModuleInfo.Type.Library else ModuleInfo.Type.Application,
            projectRootDir = temporaryFolder.root,
            moduleRootDir = root,
            buildVariant = variant,
            applicationId = "installed.$name",
            namespace = "resources.$name",
            instrumentationTargetPackage = if (isTest) "installed.$name" else null,
            manifestFile = File(root, "src/main/AndroidManifest.xml"),
            buildPathInfo = ModuleBuildPathInfo(temporaryFolder.root, root, variant, buildDirRelativePath = ""),
        )
    }

    private fun apk(module: ModuleInfo) = ApkInfo(File(temporaryFolder.root, "${module.name}.apk"), module.applicationId!!)
        .copy(instrumentationTargetPackage = module.instrumentationTargetPackage)

    private fun context(app: ModuleInfo?, modules: List<ModuleInfo>, apks: List<ApkInfo>): ICompileContext {
        val context = mock<ICompileContext>()
        whenever(context.applicationModule).thenReturn(app)
        whenever(context.modules).thenReturn(modules.associateBy { it.name })
        whenever(context.apkInfos).thenReturn(apks)
        whenever(context.isSingleApk).thenReturn(apks.sumOf { it.files.size } == 1)
        return context
    }

    private fun generate(context: ICompileContext, apk: ApkInfo): File? =
        generator.generateStyleableFile(context, File(temporaryFolder.root, "output-${apk.applicationId}"), apk.files.single())

    private fun writeManifest(module: ModuleInfo, content: String) {
        module.manifestFile!!.apply {
            parentFile.mkdirs()
            writeText(content)
        }
    }

    private fun writeRJar(module: ModuleInfo, styleable: String) {
        val rFile = File(module.buildPathInfo.buildDir,
            "intermediates/compile_and_runtime_r_class_jar/${module.buildVariant}/processResources/R.jar")
        rFile.parentFile.mkdirs()
        ZipOutputStream(rFile.outputStream()).use {
            it.putNextEntry(ZipEntry("${module.namespace!!.replace('.', '/')}/R\$styleable.class"))
            it.write(styleableClass(module.namespace!!, styleable))
            it.closeEntry()
        }
    }

    private fun writeRClass(module: ModuleInfo, namespace: String, styleable: String) {
        File(module.buildPathInfo.javaClassPath, "${namespace.replace('.', '/')}/R\$styleable.class").apply {
            parentFile.mkdirs()
            writeBytes(styleableClass(namespace, styleable))
        }
    }

    private fun styleableClass(namespace: String, name: String): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "${namespace.replace('.', '/')}/R\$styleable", null, "java/lang/Object", null)
        writer.visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, name, "[I", null, null).visitEnd()
        writer.visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "${name}_enabled", "I", null, null).visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }
}
