package com.sickworm.intellij.jugg.compiler.manifest

import com.sickworm.intellij.jugg.apk.ApkReader
import com.sickworm.intellij.jugg.apk.manifest.BinaryXmlParser
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.overlay.ResourceOverlayCompiler
import com.sickworm.intellij.jugg.compiler.withOldManifest
import com.sickworm.intellij.jugg.mock.*
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AndroidManifestCompilerTest {

    @Before
    fun before() {
        clearBuild()
    }

    @Test
    fun testFileEquals() {
        val diffResult = ManifestDiffer().diff(
            XmlParser().parse(context.applicationModule.manifestFile!!),
            XmlParser().parse(context.applicationModule.buildPathInfo.mergedManifest))
        println(diffResult.toXmlString())
        assertTrue(diffResult.isNothingToUpdate)


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
        assertEquals(2, compileResult.outputs.size)
        val outputFile = compileResult.outputs.find { it.file.name == "AndroidManifest.xml" }!!
        assertTrue(outputFile.file.exists())

        val manifest = BinaryXmlParser.parseBinaryFromStream(outputFile.file.inputStream())
        val packageName = manifest.packageName()
        assertEquals(context.packageName, packageName)
        val activities = manifest.activities()
        assertTrue(activities.isNotEmpty())
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
}