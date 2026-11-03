@file:Suppress("IncorrectParentDisposable")

package com.sickworm.intellij.jugg.gradle

import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.gradle.compile.IGradleCompileClient
import com.sickworm.intellij.jugg.gradle.compile.LocalGradleCompileClient
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.ide.JuggRunConfigurationOptions
import com.sickworm.intellij.jugg.ide.logic.toCompileOptions
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.manager.changeAndRevert
import com.sickworm.intellij.jugg.mock.*
import com.sickworm.intellij.jugg.project.ChangedFile
import com.sickworm.intellij.jugg.project.JuggPathManager
import com.sickworm.intellij.jugg.project.dependency.DependencyDiffResultHelper
import com.sickworm.intellij.jugg.project.dependency.DependencyDiffResultSet
import org.junit.BeforeClass
import org.junit.Test
import org.mockito.Mockito
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

open class LocalGradleCompileClientTest {

    companion object {

        lateinit var juggGradleCompileOptions: JuggGradleCompileOptions
        private lateinit var project: JuggMockProject

        @JvmStatic
        @BeforeClass
        fun beforeClass() {
            project = JuggMockProject(projectInfo.projectRoot)

            val moduleManager = Mockito.mock(ModuleManager::class.java)
            Mockito.doReturn(arrayOf<Module>()).`when`(moduleManager).modules
            project.registerService(ModuleManager::class.java, moduleManager)

            val pathManager = JuggPathManager(projectInfo.projectRoot)
            JuggLogger.register(project, pathManager.logDir)


            val options = JuggRunConfigurationOptions().also {
                it.compileCommand = "./gradlew :app:assembleDebug"
                it.outputApkName = "app-debug.apk"
                it.isRemoteCompile = false
            }
            juggGradleCompileOptions = options.toCompileOptions(pathManager)

            val initGradleFile = File(juggGradleCompileOptions.initGradleFilePath)
            JuggCompilerHelper::class.java.getResource("/gradle/readProjectInfo.gradle.kts")!!.openStream().use { ins ->
                val text = ins.reader().readText()
                initGradleFile.parentFile.mkdirs()
                initGradleFile.writeText(text)
            }
        }
    }

    open fun getClient(): IGradleCompileClient {
        return LocalGradleCompileClient(TestGlobal.projectInfo.projectRoot, buildDir, null, logger)
    }

    @Test
    open fun testCompile() {
        val localClient = getClient()
        localClient.login(juggGradleCompileOptions)
        val remoteCompileResult = localClient.compileAndFetchResult()
        assertTrue(remoteCompileResult.isSuccess)
    }

    @Test
    open fun testCancel() {
        val localClient = getClient()
        localClient.terminalOutputListener = object : IGradleCompileClient.TerminalOutputListener {

            override fun onOutput(line: String, isNeedPrint: Boolean) {
                if (isNeedPrint) {
                    println(line)
                }
                if (line.contains(":preBuild")) {
                    localClient.cancelAction(true)
                }
            }

            override fun onOutputErr(line: String) {
                System.err.println(line)
            }
        }
        localClient.login(juggGradleCompileOptions)
        val remoteCompileResult = localClient.compileAndFetchResult()
        assertFalse(remoteCompileResult.isSuccess)
        assertTrue(remoteCompileResult.isCanceled)
    }

    @Test
    open fun testFetchLibraryChanges() {
        // Initialize shared fixtures before recording the dependency baseline.
        AssembleAndroidProjectOnce.ensure()
        val client = getClient()
        client.login(juggGradleCompileOptions)
        val compileResult = client.compileAndFetchResult()
        assertTrue(compileResult.isSuccess)

        client.fetchLibraryChanges(0).checkChanges(
            hasChanges = false
        )

        var incDeployTimes = 0
        val buildFile = projectInfo.projectRoot.resolve("app/build.gradle")
        val libraryBuildFile = projectInfo.projectRoot.resolve("library1/build.gradle")
        fun changeGsonAndRevert(version: String, block: () -> Unit) {
            val dependency = "com.google.code.gson:gson"
            changeAndRevert(buildFile, "$dependency:2.10.1", "$dependency:$version") {
                changeAndRevert(libraryBuildFile, "$dependency:2.10.1", "$dependency:$version", block)
            }
        }
        // update to 2.8.9
        changeGsonAndRevert("2.8.9") {
            client.fetchLibraryChanges(incDeployTimes).checkChanges(
                hasChanges = true,
                updateLibraries = listOf(
                    "com.google.code.gson:gson:2.10.1" to "com.google.code.gson:gson:2.8.9",
                ),
                newLibraryFiles = listOf(
                    "com.google.code.gson:gson:2.10.1" to "com.google.code.gson:gson:2.8.9",
                ),
            )
        }
        // mark as incremental compile
        incDeployTimes++

        // update to 2.8.5
        changeGsonAndRevert("2.8.5") {
            // compare with last incremental build
            client.fetchLibraryChanges(incDeployTimes).checkChanges(
                hasChanges = true,
                updateLibraries = listOf(
                    "com.google.code.gson:gson:2.8.9" to "com.google.code.gson:gson:2.8.5",
                ),
                newLibraryFiles = listOf(
                    "com.google.code.gson:gson:2.10.1" to "com.google.code.gson:gson:2.8.5",
                )
            )
        }
        // mark as incremental compile
        incDeployTimes++

        // stay to 2.8.5
        changeGsonAndRevert("2.8.5") {
            // compare with last incremental build
            client.fetchLibraryChanges(incDeployTimes).checkChanges(hasChanges = false)
        }
        // mark as incremental compile
        incDeployTimes++


        // Change the second library and its transitive dependency while keeping Gson unchanged.
        changeGsonAndRevert("2.8.5") {
            changeAndRevert(buildFile,
                "io.reactivex.rxjava3:rxjava:3.1.8",
                "io.reactivex.rxjava3:rxjava:3.1.0",
            ) {
                // compare with last incremental build
                client.fetchLibraryChanges(incDeployTimes).checkChanges(hasChanges = true,
                    updateLibraries = listOf(
                        "io.reactivex.rxjava3:rxjava:3.1.8" to "io.reactivex.rxjava3:rxjava:3.1.0",
                        "org.reactivestreams:reactive-streams:1.0.4" to "org.reactivestreams:reactive-streams:1.0.3",
                    ),
                    newLibraryFiles = listOf(
                        "io.reactivex.rxjava3:rxjava:3.1.8" to "io.reactivex.rxjava3:rxjava:3.1.0",
                        "org.reactivestreams:reactive-streams:1.0.4" to "org.reactivestreams:reactive-streams:1.0.3",
                    ),
                )
            }
        }
        // mark as incremental compile
        incDeployTimes++

        // update together
        changeGsonAndRevert("2.9.1") {
            changeAndRevert(buildFile,
                "io.reactivex.rxjava3:rxjava:3.1.8",
                "io.reactivex.rxjava3:rxjava:3.1.9",
            ) {
                // compare with last incremental build
                client.fetchLibraryChanges(incDeployTimes).checkChanges(hasChanges = true,
                    updateLibraries = listOf(
                        "io.reactivex.rxjava3:rxjava:3.1.0" to "io.reactivex.rxjava3:rxjava:3.1.9",
                        "org.reactivestreams:reactive-streams:1.0.3" to "org.reactivestreams:reactive-streams:1.0.4",
                        "com.google.code.gson:gson:2.8.5" to "com.google.code.gson:gson:2.9.1",
                    ),
                    newLibraryFiles = listOf(
                        "io.reactivex.rxjava3:rxjava:3.1.8" to "io.reactivex.rxjava3:rxjava:3.1.9",
                        "com.google.code.gson:gson:2.10.1" to "com.google.code.gson:gson:2.9.1",
                    ),
                    removedLibraryFiles = listOf("org.reactivestreams:reactive-streams:1.0.4"),
                )
            }
        }
        // mark as incremental compile
        incDeployTimes++

        // keep version unchanged
        changeGsonAndRevert("2.9.1") {
            changeAndRevert(buildFile,
                "io.reactivex.rxjava3:rxjava:3.1.8",
                "io.reactivex.rxjava3:rxjava:3.1.9",
            ) {
                // compare with last incremental build
                client.fetchLibraryChanges(incDeployTimes).checkChanges(hasChanges = false)
            }
        }
        // mark as incremental compile
        incDeployTimes++

        // rollback
        // compare with last incremental build
        client.fetchLibraryChanges(incDeployTimes).checkChanges(hasChanges = true,
            updateLibraries = listOf(
                "com.google.code.gson:gson:2.9.1" to "com.google.code.gson:gson:2.10.1",
                "io.reactivex.rxjava3:rxjava:3.1.9" to "io.reactivex.rxjava3:rxjava:3.1.8",
            ),
            removedLibraryFiles = listOf(
                "io.reactivex.rxjava3:rxjava:3.1.8",
                "com.google.code.gson:gson:2.10.1",
            )
        )
        // mark as incremental compile
        incDeployTimes++

        // keep rollback
        // compare with last incremental build
        client.fetchLibraryChanges(incDeployTimes).checkChanges(hasChanges = false)
    }

    @Test
    open fun testFetchRemovedLibraryChanges() {
        AssembleAndroidProjectOnce.ensure()
        val client = getClient()
        client.login(juggGradleCompileOptions)
        assertTrue(client.compileAndFetchResult().isSuccess)
        val buildFile = projectInfo.projectRoot.resolve("app/build.gradle")
        changeAndRevert(buildFile, "implementation 'io.reactivex.rxjava3:rxjava:3.1.8'", "") {
            val removedLibraries = listOf(
                "io.reactivex.rxjava3:rxjava:3.1.8",
                "org.reactivestreams:reactive-streams:1.0.4",
            )
            client.fetchLibraryChanges(0).checkChanges(
                hasChanges = true,
                removedLibraries = removedLibraries,
                removedLibraryFiles = removedLibraries,
            )
            client.fetchLibraryChanges(1).checkChanges(hasChanges = false)
        }
        client.fetchLibraryChanges(2).checkChanges(
            hasChanges = true,
            newLibraries = listOf(
                "io.reactivex.rxjava3:rxjava:3.1.8",
                "org.reactivestreams:reactive-streams:1.0.4",
            ),
            removedLibraryFiles = listOf(
                "io.reactivex.rxjava3:rxjava:3.1.8",
                "org.reactivestreams:reactive-streams:1.0.4",
            ),
        )
    }

    @Test
    open fun testFetchLocalLibraryChanges() {
        val client = getClient()
        client.login(juggGradleCompileOptions)
        val compileResult = client.compileAndFetchResult()
        assertTrue(compileResult.isSuccess)

        var incDeployTimes = 0
        // update library2
        changeAndRevert(
            "library2.v2.jar" to "library2.jar",
            directory = "app/libs",
        ) {
            client.fetchLibraryChanges(incDeployTimes).checkChanges(
                hasChanges = true,
                updateLibraries = listOf(
                    "./app/libs/library2.jar" to "./app/libs/library2.jar",
                ),
                newLibraryFiles = listOf(
                    "./app/libs/library2.jar" to "./app/libs/library2.jar",
                ),
            )
        }
        // mark as incremental compile
        incDeployTimes++

        // rollback
        client.fetchLibraryChanges(incDeployTimes).checkChanges(
            hasChanges = true,
            updateLibraries = listOf(
                "./app/libs/library2.jar" to "./app/libs/library2.jar",
            ),
            removedLibraryFiles = listOf(
                "./app/libs/library2.jar"
            )
        )
        // mark as incremental compile
        incDeployTimes++
    }

    @Test
    open fun testFetchLocalLibraryAarChanges() {
        assertLocalLibraryAssetCanChange("library1-debug.v2.aar", "library1-debug.aar")
    }

    private fun DependencyDiffResultSet?.checkChanges(
        hasChanges: Boolean,
        newLibraries: List<String> = emptyList(),
        updateLibraries: List<Pair<String, String>> = emptyList(),
        removedLibraries: List<String> = emptyList(),
        newLibraryFiles: List<Pair<String?, String>> = emptyList(),
        removedLibraryFiles: List<String> = emptyList(),
    ) {
        assertTrue(this != null)
        assertEquals(hasChanges, this.hasChanges)
        val sortedUpdatedLibraries = this.diffResult.updatedLibraries.sortedBy { it.oldDependency?.declaration }
        updateLibraries.sortedBy { it.first }.forEachIndexed { index, it ->
            assertEquals(it.first, sortedUpdatedLibraries[index].oldDependency?.declaration)
            assertEquals(it.second, sortedUpdatedLibraries[index].dependency?.declaration)
        }
        assertEquals(updateLibraries.size, this.diffResult.updatedLibraries.size,
            "actual: ${diffResult.updatedLibraries.map { it.dependency?.declaration }}",
        )

        val sortedNewLibraries = this.diffResult.addedLibraries.sortedBy { it.dependency?.declaration }
        newLibraries.sorted().forEachIndexed { index, it ->
            assertEquals(it, sortedNewLibraries[index].dependency?.declaration)
        }

        val sortedRemovedLibraries = this.diffResult.removedLibraries.sortedBy { it.oldDependency?.declaration }
        removedLibraries.sorted().forEachIndexed { index, it ->
            assertEquals(it, sortedRemovedLibraries[index].oldDependency?.declaration)
        }

        val diffResultHelper = DependencyDiffResultHelper(
            logger, context.tempModule, this.diffResult, this.diffResultWithFull
        )

        val actualNewLibraryFiles = diffResultHelper.getNewLibraryFiles().sortedBy { it.dependencyName }
        newLibraryFiles.sortedBy { it.second }.forEachIndexed { index, (oldDepend, newDepend) ->
            val isMavenDepend = newDepend.contains(":")
            if (isMavenDepend) {
                val newVersion = newDepend.substringAfterLast(':')

                actualNewLibraryFiles.filter { it.dependencyName == newDepend }.forEach { changedFile: ChangedFile  ->
                    assertEquals(newDepend, changedFile.dependencyName)
                    assertTrue(changedFile.file.absolutePath.contains(newVersion))

                    val oldVersion = oldDepend?.substringAfterLast(':')
                    if (oldVersion != null) {
                        when (changedFile.type) {
                            CompileFile.Type.Class -> changedFile.oldJar?.let { assertTrue(it.absolutePath.contains(oldVersion)) }
                            CompileFile.Type.AndroidManifest -> changedFile.oldManifest?.let { assertTrue(it.absolutePath.contains(oldVersion)) }
                            CompileFile.Type.Resource -> changedFile.oldRes?.let { assertTrue(it.absolutePath.contains(oldVersion)) }
                            CompileFile.Type.Asset -> changedFile.oldRes?.let { assertTrue(it.absolutePath.contains(oldVersion)) }
                            CompileFile.Type.NativeLib -> changedFile.oldRes?.let { assertTrue(it.absolutePath.contains(oldVersion)) }
                            else -> throw IllegalArgumentException("unknown type: ${changedFile.type}")
                        }
                    }
                }
            } else {
                // it's a local library file
                assertTrue(File(projectInfo.projectRoot, oldDepend!!).exists())
                assertTrue(File(projectInfo.projectRoot, newDepend).exists())

                actualNewLibraryFiles.filter { it.dependencyName == newDepend }.forEach { changedFile: ChangedFile ->
                    assertEquals(newDepend, changedFile.dependencyName)
                    if (changedFile.dependencyName.endsWith(".jar")) {
                        // local jar file has no relative old jar
                        return@forEach
                    }
                    when (changedFile.type) {
                        CompileFile.Type.Class -> assertNotNull(changedFile.oldJar)
                        CompileFile.Type.AndroidManifest -> assertNotNull(changedFile.oldManifest)
                        CompileFile.Type.Resource -> assertNotNull(changedFile.oldRes)
                        CompileFile.Type.Asset -> assertNotNull(changedFile.oldRes)
                        CompileFile.Type.NativeLib -> assertNotNull(changedFile.oldRes)
                        else -> throw IllegalArgumentException("unknown type: ${changedFile.type}")
                    }
                }
            }
        }
        assertEquals(newLibraryFiles.size, actualNewLibraryFiles.distinctBy { it.dependencyName }.size,
            "actual: ${actualNewLibraryFiles.distinctBy { it.dependencyName }}",
        )

        actualNewLibraryFiles.forEach {
            assertTrue(it.file.exists())
        }

        val actualRemovedLibraryFiles = diffResultHelper.getRemovedLibraryFiles().sortedBy { it.dependencyName }
        removedLibraryFiles.sorted().forEachIndexed { index, it ->
            assertEquals(it, actualRemovedLibraryFiles[index].dependencyName)
        }
        assertEquals(removedLibraryFiles.size, actualRemovedLibraryFiles.distinctBy { it.dependencyName }.size,
            "actual: ${actualRemovedLibraryFiles.map { it.dependencyName }}",
        )
    }

    private fun assertLocalLibraryAssetCanChange(sourceFileName: String, destFileName: String) {
        val sourceFile = File(assetsAndroidModifySourceDir, "app/libs/$sourceFileName")
        val destFile = File(projectInfo.projectRoot, "app/libs/$destFileName")
        assertTrue(sourceFile.exists(), "missing source asset: $sourceFile")
        assertTrue(destFile.exists(), "missing target library: $destFile")
        assertFalse(
            sourceFile.readBytes().contentEquals(destFile.readBytes()),
            "source asset must differ from target library: $sourceFile -> $destFile",
        )
    }
}
