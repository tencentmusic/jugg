package com.sickworm.intellij.jugg.compiler.manifest

import com.intellij.openapi.util.Disposer
import com.sickworm.intellij.jugg.apk.ApkReader
import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.aapt2.Aapt2DaemonInvoker
import com.sickworm.intellij.jugg.apk.manifest.BinaryXmlParser
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.CompileResult
import com.sickworm.intellij.jugg.compiler.JuggCompiler
import com.sickworm.intellij.jugg.compiler.overlay.ResourceOverlayCompiler
import com.sickworm.intellij.jugg.compiler.withOldManifest
import com.sickworm.intellij.jugg.mock.*
import com.sickworm.intellij.jugg.project.info.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.project.info.ModuleDependency
import com.sickworm.intellij.jugg.project.info.ModuleInfo
import org.junit.Before
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AndroidManifestCompilerTest {

    @Before
    fun before() {
        clearBuild()
    }

    @Test
    fun `library manifest changes reach app and self targeting test with independent identities`() {
        val fixture = createLibraryTestContext()
        val task = libraryManifestTask(fixture, "manifest_scope")
        val result = AndroidManifestCompiler(fixture, mockParentDisposable).compile(task)

        assertScopedManifests(fixture, result, "SharedScreen")
        assertEquals(2, result.outputs.map { it.file.canonicalPath }.distinct().size)
        val initialContents = result.outputs.associate { it.apkPath to it.file.readText() }

        val library = fixture.modules.getValue("manifestScope")
        library.manifestFile!!.writeText(library.manifestFile!!.readText().replace("SharedScreen", "SecondScreen"))
        val next = AndroidManifestCompiler(fixture, mockParentDisposable)
            .compile(libraryManifestTask(fixture, "manifest_scope_next"))

        assertScopedManifests(fixture, next, "SecondScreen")
        next.outputs.forEach {
            assertTrue(it.file.readText().contains("SharedScreen"), "previous incremental component must be retained")
        }
        result.outputs.forEach { assertEquals(initialContents[it.apkPath], it.file.readText()) }
    }

    @Test
    fun `library test manifest baseline missing fails instead of reusing app manifest`() {
        val fixture = createLibraryTestContext()
        fixture.modules.getValue("manifestScope.androidTest").buildPathInfo.mergedManifest.delete()
        val compiler = AndroidManifestCompiler(fixture, mockParentDisposable)
        val task = libraryManifestTask(fixture, "missing_test_manifest")
        assertTrue(compiler.doApkCompile(task, fixture.apkInfos.first().files.single()).isAllSuccess)
        val result = compiler.doApkCompile(task, fixture.apkInfos.last().files.single())

        assertFalse(result.isAllSuccess)
        assertTrue(result.outputs.isEmpty())
        assertTrue(result.failedFiles.any { it.getFailure().errorMessages.contains("fallback to gradle") })
    }

    @Test
    fun `library manifest links against separate app and test resource tables`() {
        val fixture = createLibraryTestContext()
        createLibraryTestApk(fixture)
        val compiler = ResourceOverlayCompiler(fixture, mockParentDisposable)
        try {
            val result = compiler.compile(libraryManifestTask(fixture, "resource_scope"))
            assertTrue(result.isAllSuccess, result.failedFiles.toString())
            val manifests = result.outputs.filter { it.file.name == "AndroidManifest.xml" }
            assertEquals(2, manifests.size)
            assertEquals(2, manifests.map { it.file.canonicalPath }.distinct().size)
            manifests.forEach {
                val expected = fixture.apkInfos.single { apk -> apk.files.single().apkFile.path == it.apkPath }
                val manifest = it.file.inputStream().use { stream -> BinaryXmlParser.parseBinaryFromStream(stream) }
                assertEquals(expected.applicationId, manifest.packageName())
                assertEquals(expected.instrumentationTargetPackage, manifest.instrumentationTargetPackage())
                assertTrue(manifest.activities().any { activity -> activity.qualifiedName?.endsWith(".SharedScreen") == true })
            }
        } finally {
            Disposer.dispose(compiler)
        }
    }

    @Test
    fun `library manifest compile flow retains both APK manifests in staging`() {
        val fixture = createLibraryTestContext()
        createLibraryTestApk(fixture)
        val compiler = JuggCompiler(fixture, mockParentDisposable)
        try {
            val result = compiler.compile(libraryManifestTask(fixture, "final_scope"))
            assertTrue(result.isAllSuccess, result.failedFiles.toString())
            val manifests = result.outputs.filter { it.file.name == "AndroidManifest.xml" }
            assertEquals(2, manifests.size)
            assertEquals(2, manifests.map { it.file.canonicalPath }.distinct().size)
            manifests.forEach {
                assertEquals("AndroidManifest.xml", it.relativeFile.path)
                val expected = fixture.apkInfos.single { apk -> apk.files.single().apkFile.path == it.apkPath }
                val manifest = it.file.inputStream().use { stream -> BinaryXmlParser.parseBinaryFromStream(stream) }
                assertEquals(expected.applicationId, manifest.packageName())
                assertEquals(expected.instrumentationTargetPackage, manifest.instrumentationTargetPackage())
            }
        } finally {
            Disposer.dispose(compiler)
        }
    }

    private fun createLibraryTestApk(fixture: SimpleCompileContext) {
        val root = File(tempCompileDir, "manifest_fixture/library")
        val manifest = File(root, "build/intermediates/packaged_manifests/debugAndroidTest/processDebugAndroidTestManifest/AndroidManifest.xml")
        val resDir = File(root, "test_res")
        File(resDir, "values/strings.xml").apply {
            parentFile.mkdirs()
            writeText("<resources><string name=\"test_label\">Test baseline</string></resources>")
        }
        val compiledRes = File(root, "test_res.zip")
        val invoker = Aapt2DaemonInvoker(logger)
        try {
            val compiled = invoker.invoke(listOf("compile", "--dir", resDir.path, "-o", compiledRes.path))
            assertTrue(compiled.isSuccess, compiled.errorOutput)
            val result = invoker.invoke(listOf(
                "link", "-I", androidJar.path, "--manifest", manifest.path,
                "-o", fixture.apkInfos.last().files.single().apkFile.path, compiledRes.path,
            ))
            assertTrue(result.isSuccess, result.errorOutput)
        } finally {
            invoker.release()
        }
    }

    private fun createLibraryTestContext(): SimpleCompileContext {
        val original = context
        val root = File(tempCompileDir, "manifest_fixture").apply { mkdirs() }
        val library = createManifestLibrary(root)
        val testPackage = "com.example.manifestscope.test"
        val testModule = library.copy(
            name = "${library.name}.androidTest", applicationId = testPackage,
            instrumentationTargetPackage = testPackage, buildVariant = "debugAndroidTest",
            manifestFile = null, moduleDependencies = listOf(ModuleDependency(library.name)),
            buildPathInfo = library.buildPathInfo.copy(buildVariant = "debugAndroidTest"),
        )
        val testManifest = File(library.buildPathInfo.buildDir,
            "intermediates/packaged_manifests/debugAndroidTest/processDebugAndroidTestManifest/AndroidManifest.xml")
        testManifest.parentFile.mkdirs()
        testManifest.writeText("""
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$testPackage">
                <uses-sdk android:minSdkVersion="21"/>
                <instrumentation android:name="androidx.test.runner.AndroidJUnitRunner" android:targetPackage="$testPackage"/>
                <application android:label="test baseline"/>
            </manifest>
        """.trimIndent())
        val testApk = ApkInfo(File(root, "library-test.apk"), testPackage).copy(
            instrumentationTargetPackage = testPackage, instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner",
        )
        return original.copy(
            modules = original.modules + mapOf(library.name to library, testModule.name to testModule),
            apkInfos = listOf(original.apkInfos.first(), testApk), tempModuleDir = File(root, "temp_module"),
        )
    }

    private fun createManifestLibrary(root: File): ModuleInfo {
        val moduleDir = File(root, "library").apply { mkdirs() }
        val buildPaths = ModuleBuildPathInfo(root, moduleDir, "debug", buildDirRelativePath = "")
        val baseline = File(buildPaths.buildDir, "intermediates/merged_manifest/debug/AndroidManifest.xml")
        baseline.parentFile.mkdirs()
        baseline.writeText("<manifest package=\"com.example.manifestscope\"><application/></manifest>")
        val manifest = File(moduleDir, "src/main/AndroidManifest.xml")
        manifest.parentFile.mkdirs()
        manifest.writeText("""
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.manifestscope">
                <application>
                    <activity android:name=".SharedScreen"/>
                    <provider android:name=".SharedProvider" android:authorities="${'$'}{applicationId}.shared"/>
                </application>
            </manifest>
        """.trimIndent())
        return ModuleInfo.virtualModule.copy(
            name = "manifestScope", moduleType = ModuleInfo.Type.Library,
            projectRootDir = root, moduleRootDir = moduleDir, buildVariant = "debug",
            namespace = "com.example.manifestscope", manifestFile = manifest, buildPathInfo = buildPaths,
        )
    }

    private fun libraryManifestTask(fixture: SimpleCompileContext, directory: String): com.sickworm.intellij.jugg.compiler.CompileTask {
        val library = fixture.modules.getValue("manifestScope")
        val manifest = library.manifestFile!!
        return CompileTask(listOf(CompileFile(CompileFile.Type.AndroidManifest, manifest, manifest.parentFile, library)),
            File(stagingDir, directory))
    }

    private fun assertScopedManifests(fixture: SimpleCompileContext, result: CompileResult, activity: String) {
        assertTrue(result.isAllSuccess, result.failedFiles.toString())
        assertEquals(2, result.outputs.size)
        result.outputs.forEach { output ->
            val expected = fixture.apkInfos.single { it.files.single().apkFile.path == output.apkPath }
            val node = XmlParser().parse(output.file).node
            assertEquals(expected.applicationId, node.getAttribute("package"))
            val instrumentation = node.getElementsByTagName("instrumentation")
            assertEquals(if (expected.isTestApk) 1 else 0, instrumentation.length)
            if (expected.isTestApk) {
                assertEquals(expected.applicationId, (instrumentation.item(0) as Element).getAttribute("android:targetPackage"))
                val application = node.getElementsByTagName("application").item(0) as Element
                assertEquals("test baseline", application.getAttribute("android:label"))
            }
            val text = output.file.readText()
            assertTrue(text.contains("com.example.manifestscope.$activity"))
            assertTrue(text.contains("${expected.applicationId}.shared"))
        }
    }

    @Test
    fun testFileEquals() {
        val compileFile = CompileFile(
            CompileFile.Type.AndroidManifest,
            context.applicationModule.manifestFile!!,
            context.applicationModule.manifestFile!!.parentFile,
            context.applicationModule,
        )
        val compileTask = CompileTask(listOf(compileFile), stagingDir)

        val compiler = ResourceOverlayCompiler(context, mockParentDisposable)
        val compileResult = compiler.compile(compileTask)

        assertTrue(compileResult.isAllSuccess)
        assertTrue(compileResult.outputs.isEmpty())
    }

    @Test
    fun testAddActivity() {
        val changedManifestFile = File(tempCompileDir, "AndroidManifest_add_activity.xml")
        changedManifestFile.parentFile.mkdirs()
        changedManifestFile.writeText(context.applicationModule.manifestFile!!.readText()
            .replace(
                "</application>",
                """
                    <activity android:name=".ActivityNew">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN"/>
                            <category android:name="android.intent.category.LAUNCHER"/>
                        </intent-filter>
                    </activity>
                    </application>
                """.trimIndent()
            )
        )

        val compileFile = CompileFile(
            CompileFile.Type.AndroidManifest,
            changedManifestFile,
            changedManifestFile.parentFile,
            context.applicationModule,
        )

        val compileTask = CompileTask(listOf(compileFile), stagingDir)

        val compiler = ResourceOverlayCompiler(context, mockParentDisposable)
        val compileResult = compiler.compile(compileTask)

        assertTrue(compileResult.isAllSuccess)
        assertEquals(2, compileResult.outputs.size)
        val outputFile = compileResult.outputs.find { it.file.name == "AndroidManifest.xml" }!!
        assertTrue(outputFile.file.exists())


        val oldManifest = ApkReader(context.apkFile!!, logger).getManifest()
        val manifest = BinaryXmlParser.parseBinaryFromStream(outputFile.file.inputStream())
        val packageName = manifest.packageName()
        assertEquals(context.packageName, packageName)
        val activities = manifest.activities()
        assertEquals(oldManifest.activities().size + 1, activities.size)

        val newActivity = activities.find { it.qualifiedName == "$packageName.ActivityNew" }
        assertNotNull(newActivity)
        assertTrue(newActivity.hasAction("android.intent.action.MAIN"))
        assertTrue(newActivity.hasCategory("android.intent.category.LAUNCHER"))
    }

    @Test
    fun testAndroidManifestUpdate() {
        val changedManifestFile = File(tempCompileDir, "AndroidManifest_same.xml")
        changedManifestFile.parentFile.mkdirs()
        changedManifestFile.writeText(context.applicationModule.manifestFile!!.readText())
        val compileFile = CompileFile(
            CompileFile.Type.AndroidManifest,
            changedManifestFile,
            changedManifestFile.parentFile,
            context.tempModule,
        ).withOldManifest(context.applicationModule.manifestFile!!)

        val compileTask = CompileTask(listOf(compileFile), stagingDir)
        val compiler = ResourceOverlayCompiler(context, mockParentDisposable)
        val compileResult = compiler.compile(compileTask)

        assertTrue(compileResult.isAllSuccess)
        assertEquals(0, compileResult.outputs.size)
    }

    @Test
    fun testNewActivityInLibraries() {
        val changedManifestFile = File(tempCompileDir, "AndroidManifest_add_activity.xml")
        changedManifestFile.parentFile.mkdirs()
        changedManifestFile.writeText(context.applicationModule.manifestFile!!.readText()
            .replace(
                "</application>",
                """
                    <activity android:name=".ActivityNew">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN"/>
                            <category android:name="android.intent.category.LAUNCHER"/>
                        </intent-filter>
                    </activity>
                    </application>
                """.trimIndent()
            )
        )

        val compileFile = CompileFile(
            CompileFile.Type.AndroidManifest,
            changedManifestFile,
            changedManifestFile.parentFile,
            context.tempModule,
        ).withOldManifest(context.applicationModule.manifestFile!!)

        val compileTask = CompileTask(listOf(compileFile), stagingDir)
        val compiler = ResourceOverlayCompiler(context, mockParentDisposable)
        val compileResult = compiler.compile(compileTask)

        assertTrue(compileResult.isAllSuccess)
        assertEquals(2, compileResult.outputs.size)
        val outputFile = compileResult.outputs.find { it.file.name == "AndroidManifest.xml" }!!
        assertTrue(outputFile.file.exists())


        val oldManifest = ApkReader(context.apkFile!!, logger).getManifest()
        val manifest = BinaryXmlParser.parseBinaryFromStream(outputFile.file.inputStream())
        val packageName = manifest.packageName()
        assertEquals(context.packageName, packageName)
        val activities = manifest.activities()
        assertEquals(oldManifest.activities().size + 1, activities.size)

        val newActivity = activities.find { it.qualifiedName == "$packageName.ActivityNew" }
        assertNotNull(newActivity)
        assertTrue(newActivity.hasAction("android.intent.action.MAIN"))
        assertTrue(newActivity.hasCategory("android.intent.category.LAUNCHER"))
    }

    @Test
    fun `library applicationId placeholder uses final package name`() {
        val changedManifestFile = File(tempCompileDir, "library_placeholder/AndroidManifest.xml")
        changedManifestFile.parentFile.mkdirs()
        changedManifestFile.writeText(
            """
                <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                    package="com.example.library">
                    <application>
                        <provider
                            android:name="com.example.ReportFileProvider"
                            android:authorities="${'$'}{applicationId}.report-fileprovider" />
                    </application>
                </manifest>
            """.trimIndent()
        )
        val compileFile = CompileFile(
            CompileFile.Type.AndroidManifest,
            changedManifestFile,
            changedManifestFile.parentFile,
            context.tempModule,
        )
        val compileTask = CompileTask(listOf(compileFile), File(stagingDir, "library_placeholder"))
        val apkFileUnit = context.apkInfos.first().files.first().copy(
            applicationId = "com.example.test",
        )

        val compileResult = AndroidManifestCompiler(context, mockParentDisposable)
            .doApkCompile(compileTask, apkFileUnit)

        assertTrue(compileResult.isAllSuccess)
        val outputManifest = compileResult.outputs.single().file
        val providers = XmlParser().parse(outputManifest).node.getElementsByTagName("provider")
        val provider = (0 until providers.length)
            .map { providers.item(it) as Element }
            .single { it.getAttribute("android:name") == "com.example.ReportFileProvider" }
        assertEquals(
            "${apkFileUnit.applicationId}.report-fileprovider",
            provider.getAttribute("android:authorities"),
        )
    }

    @Test
    fun `library explicit applicationId placeholder is preserved`() {
        val libraryApplicationId = "com.example.library.fixed"
        val changedManifestFile = File(tempCompileDir, "library_explicit_placeholder/AndroidManifest.xml")
        changedManifestFile.parentFile.mkdirs()
        changedManifestFile.writeText(
            """
                <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                    package="com.example.library">
                    <application>
                        <provider
                            android:name="com.example.ExplicitFileProvider"
                            android:authorities="${'$'}{applicationId}.explicit-fileprovider" />
                    </application>
                </manifest>
            """.trimIndent()
        )
        val libraryModule = context.tempModule.copy(
            manifestPlaceHolders = mapOf("applicationId" to libraryApplicationId),
        )
        val compileFile = CompileFile(
            CompileFile.Type.AndroidManifest,
            changedManifestFile,
            changedManifestFile.parentFile,
            libraryModule,
        )
        val compileTask = CompileTask(listOf(compileFile), File(stagingDir, "library_explicit_placeholder"))
        val apkFileUnit = context.apkInfos.first().files.first()

        val compileResult = AndroidManifestCompiler(context, mockParentDisposable)
            .doApkCompile(compileTask, apkFileUnit)

        assertTrue(compileResult.isAllSuccess)
        val outputManifest = compileResult.outputs.single().file
        val providers = XmlParser().parse(outputManifest).node.getElementsByTagName("provider")
        val provider = (0 until providers.length)
            .map { providers.item(it) as Element }
            .single { it.getAttribute("android:name") == "com.example.ExplicitFileProvider" }
        assertEquals(
            "$libraryApplicationId.explicit-fileprovider",
            provider.getAttribute("android:authorities"),
        )
    }
}
