@file:Suppress("IncorrectParentDisposable")

package com.sickworm.intellij.jugg.gradle

import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.gradle.compile.IGradleCompileClient
import com.sickworm.intellij.jugg.gradle.compile.LocalGradleCompileClient
import com.sickworm.intellij.jugg.ide.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.ide.JuggRunConfigurationOptions
import com.sickworm.intellij.jugg.ide.toCompileOptions
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.manager.changeAndRevert
import com.sickworm.intellij.jugg.mock.*
import com.sickworm.intellij.jugg.project.JuggPathManager
import com.sickworm.intellij.jugg.project.dependency.DependencyDiffResultHelper
import com.sickworm.intellij.jugg.project.dependency.DependencyDiffResultSet
import org.junit.BeforeClass
import org.junit.Test
import org.mockito.Mockito
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

            val initGradleFile = File(juggGradleCompileOptions.projectRootPath, juggGradleCompileOptions.initGradleFileRelativePath)
            JuggCompilerHelper::class.java.getResource("/gradle/readProjectInfo.gradle.kts")!!.openStream().use { ins ->
                val text = ins.reader().readText()
                initGradleFile.parentFile.mkdirs()
                initGradleFile.writeText(text)
            }
        }
    }

    open fun getClient(): IGradleCompileClient {
        return LocalGradleCompileClient(project, buildDir, logger)
    }

    @Test
    open fun testCompile() {
        val localClient = LocalGradleCompileClient(project, buildDir, logger)
        localClient.login(juggGradleCompileOptions)
        val remoteCompileResult = localClient.compileAndFetchResult()
        assertTrue(remoteCompileResult.isSuccess)
    }

    @Test
    open fun testCancel() {
        val localClient = LocalGradleCompileClient(project, buildDir, logger)
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
        val client = getClient()
        client.login(juggGradleCompileOptions)
        val compileResult = client.compileAndFetchResult()
        assertTrue(compileResult.isSuccess)

        client.fetchLibraryChanges(0).checkChanges(
            hasChanges = false
        )

        var incDeployTimes = 0
        val buildFile = projectInfo.projectRoot.resolve("app/build.gradle")
        // update to 2.8.1
        changeAndRevert(buildFile,
            "com.google.code.gson:gson:2.8.0",
            "com.google.code.gson:gson:2.8.1",
        ) {
            client.fetchLibraryChanges(incDeployTimes).checkChanges(
                hasChanges = true,
                updateLibraries = listOf(
                    "com.google.code.gson:gson:2.8.0" to "com.google.code.gson:gson:2.8.1",
                ),
                newLibraryFiles = listOf(
                    "com.google.code.gson:gson:2.8.0" to "com.google.code.gson:gson:2.8.1",
                ),
            )
        }
        // mark as incremental compile
        incDeployTimes++

        // update to 2.8.2
        changeAndRevert(buildFile,
            "com.google.code.gson:gson:2.8.0",
            "com.google.code.gson:gson:2.8.2",
        ) {
            // compare with last incremental build
            client.fetchLibraryChanges(incDeployTimes).checkChanges(
                hasChanges = true,
                updateLibraries = listOf(
                    "com.google.code.gson:gson:2.8.1" to "com.google.code.gson:gson:2.8.2",
                ),
                newLibraryFiles = listOf(
                    "com.google.code.gson:gson:2.8.0" to "com.google.code.gson:gson:2.8.2",
                )
            )
        }
        // mark as incremental compile
        incDeployTimes++

        // stay to 2.8.2
        changeAndRevert(buildFile,
            "com.google.code.gson:gson:2.8.0",
            "com.google.code.gson:gson:2.8.2",
        ) {
            // compare with last incremental build
            client.fetchLibraryChanges(incDeployTimes).checkChanges(hasChanges = false)
        }
        // mark as incremental compile
        incDeployTimes++


        // start update second library fastjson
        // keep version unchanged
        changeAndRevert(buildFile,
            "com.google.code.gson:gson:2.8.0",
            "com.google.code.gson:gson:2.8.2",
        ) {
            changeAndRevert(buildFile,
                "com.alibaba:fastjson:2.0.2.android",
                "com.alibaba:fastjson:2.0.3.android",
            ) {
                // compare with last incremental build
                client.fetchLibraryChanges(incDeployTimes).checkChanges(hasChanges = true,
                    newLibraries = listOf(
                        "org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.6.10", // added by fastjson
                    ),
                    updateLibraries = listOf(
                        "com.alibaba:fastjson:2.0.2.android" to "com.alibaba:fastjson:2.0.3.android",
                        "com.alibaba.fastjson2:fastjson2-extension:2.0.2.android" to "com.alibaba.fastjson2:fastjson2-extension:2.0.3.android",
                        "com.alibaba.fastjson2:fastjson2:2.0.2.android" to "com.alibaba.fastjson2:fastjson2:2.0.3.android",
                    ),
                    newLibraryFiles = listOf(
                        "com.alibaba:fastjson:2.0.2.android" to "com.alibaba:fastjson:2.0.3.android",
                        "com.alibaba.fastjson2:fastjson2-extension:2.0.2.android" to "com.alibaba.fastjson2:fastjson2-extension:2.0.3.android",
                        "com.alibaba.fastjson2:fastjson2:2.0.2.android" to "com.alibaba.fastjson2:fastjson2:2.0.3.android",
                        null to "org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.6.10", // added by fastjson
                    ),
                )
            }
        }
        // mark as incremental compile
        incDeployTimes++

        // update together
        changeAndRevert(buildFile,
            "com.google.code.gson:gson:2.8.0",
            "com.google.code.gson:gson:2.8.3",
        ) {
            changeAndRevert(buildFile,
                "com.alibaba:fastjson:2.0.2.android",
                "com.alibaba:fastjson:2.0.4.android",
            ) {
                // compare with last incremental build
                client.fetchLibraryChanges(incDeployTimes).checkChanges(hasChanges = true,
                    updateLibraries = listOf(
                        "com.alibaba:fastjson:2.0.3.android" to "com.alibaba:fastjson:2.0.4.android",
                        "com.alibaba.fastjson2:fastjson2-extension:2.0.3.android" to "com.alibaba.fastjson2:fastjson2-extension:2.0.4.android",
                        "com.alibaba.fastjson2:fastjson2:2.0.3.android" to "com.alibaba.fastjson2:fastjson2:2.0.4.android",
                        "com.google.code.gson:gson:2.8.2" to "com.google.code.gson:gson:2.8.3",
                    ),
                    removedLibraries = listOf(
                        "org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.6.10", // removed by fastjson
                    ),
                    newLibraryFiles = listOf(
                        "com.alibaba:fastjson:2.0.2.android" to "com.alibaba:fastjson:2.0.4.android",
                        "com.alibaba.fastjson2:fastjson2-extension:2.0.2.android" to "com.alibaba.fastjson2:fastjson2-extension:2.0.4.android",
                        "com.alibaba.fastjson2:fastjson2:2.0.2.android" to "com.alibaba.fastjson2:fastjson2:2.0.4.android",
                        "com.google.code.gson:gson:2.8.0" to "com.google.code.gson:gson:2.8.3",
                    ),
                    removedLibraryFiles = listOf(
                        "org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.6.10", // removed by fastjson
                    ),
                )
            }
        }
        // mark as incremental compile
        incDeployTimes++

        // keep version unchanged
        changeAndRevert(buildFile,
            "com.google.code.gson:gson:2.8.0",
            "com.google.code.gson:gson:2.8.3",
        ) {
            changeAndRevert(buildFile,
                "com.alibaba:fastjson:2.0.2.android",
                "com.alibaba:fastjson:2.0.4.android",
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
                "com.alibaba:fastjson:2.0.4.android" to "com.alibaba:fastjson:2.0.2.android",
                "com.alibaba.fastjson2:fastjson2-extension:2.0.4.android" to "com.alibaba.fastjson2:fastjson2-extension:2.0.2.android",
                "com.alibaba.fastjson2:fastjson2:2.0.4.android" to "com.alibaba.fastjson2:fastjson2:2.0.2.android",
                "com.google.code.gson:gson:2.8.3" to "com.google.code.gson:gson:2.8.0",
            ),
            removedLibraryFiles = listOf(
                "com.alibaba:fastjson:2.0.2.android",
                "com.alibaba.fastjson2:fastjson2-extension:2.0.2.android",
                "com.alibaba.fastjson2:fastjson2:2.0.2.android",
                "com.google.code.gson:gson:2.8.0",
            )
        )
        // mark as incremental compile
        incDeployTimes++

        // keep rollback
        // compare with last incremental build
        client.fetchLibraryChanges(incDeployTimes).checkChanges(hasChanges = false)
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
        newLibraryFiles.sortedBy { it.second }.forEachIndexed { index, it ->
            val isMavenDepend = it.second.contains(":")
            if (isMavenDepend) {
                val newVersion = it.second.substringAfterLast(':')
                assertEquals(it.second, actualNewLibraryFiles[index].dependencyName)
                assertTrue(actualNewLibraryFiles[index].file.absolutePath.contains(newVersion))

                val oldVersion = it.first?.substringAfterLast(':')
                if (oldVersion != null) {
                    when (actualNewLibraryFiles[index].type) {
                        CompileFile.Type.Class -> assertTrue(actualNewLibraryFiles[index].oldJar!!.absolutePath.contains(oldVersion))
                        CompileFile.Type.AndroidManifest -> assertTrue(actualNewLibraryFiles[index].oldManifest!!.absolutePath.contains(oldVersion))
                        CompileFile.Type.Resource -> assertTrue(actualNewLibraryFiles[index].oldRes!!.absolutePath.contains(oldVersion))
                        else -> throw IllegalArgumentException("unknown type: ${actualNewLibraryFiles[index].type}")
                    }
                }
            } else {
                // it's a local library file
                assertTrue(File(projectInfo.projectRoot, it.first!!).exists())
                assertTrue(File(projectInfo.projectRoot, it.second).exists())
            }
        }
        assertEquals(newLibraryFiles.size, actualNewLibraryFiles.size,
            "actual: ${actualNewLibraryFiles.map { it.dependencyName }}",
        )

        actualNewLibraryFiles.forEach {
            assertTrue(it.file.exists())
        }

        val actualRemovedLibraryFiles = diffResultHelper.getRemovedLibraryFiles().sortedBy { it.dependencyName }
        removedLibraryFiles.sorted().forEachIndexed { index, it ->
            assertEquals(it, actualRemovedLibraryFiles[index].dependencyName)
        }
        assertEquals(removedLibraryFiles.size, actualRemovedLibraryFiles.size,
            "actual: ${actualRemovedLibraryFiles.map { it.dependencyName }}",
        )
    }
}