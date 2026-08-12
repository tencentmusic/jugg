package com.sickworm.intellij.jugg.gradle.script

import com.sickworm.intellij.jugg.manager.MockJugg
import com.sickworm.intellij.jugg.mock.TestGlobal
import com.sickworm.intellij.jugg.mock.buildDir
import com.sickworm.intellij.jugg.mock.logger
import com.sickworm.intellij.jugg.project.ProjectInfoSerializer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.net.URLClassLoader
import java.util.jar.JarFile
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectInfoSerializerInGradleTest {

    companion object {

        private val mockJugg = MockJugg()
        private val gradleProjectInfo = mockJugg.pathManager.gradleProjectInfoFile
        private val ideProjectInfoFixtureFile = File("src/test/assets/android/modify_source/project_infos.json")
        private val ideProjectInfoFile = File(buildDir, "project_infos_fixture.json")

        @JvmStatic
        @BeforeClass
        fun runAndGenerate() {
            ideProjectInfoFixtureFile.copyTo(ideProjectInfoFile, overwrite = true)
            val gradleProjectInfoLocalFetchManager = mockJugg.gradleProjectInfoLocalFetchManager
            mockJugg.pathManager.markProjectInfoNeedUpdateFlagFile.delete()
            gradleProjectInfoLocalFetchManager.markIsNeedUpdate(true)
            assertTrue(mockJugg.pathManager.markProjectInfoNeedUpdateFlagFile.exists())

            ideProjectInfoFile.copyTo(mockJugg.pathManager.ideProjectInfoFile, overwrite = true)
            val scriptFile = File("../main/src/main/resources/gradle/readProjectInfo.gradle.kts")
            scriptFile.copyTo(mockJugg.pathManager.initGradleFilePath, overwrite = true)

            mockJugg.pathManager.gradleProjectInfoFile.delete()
            gradleProjectInfoLocalFetchManager.runUpdateIfNeeded(specificCompileCommand = "./gradlew :app:assembleDebug")
        }
    }

    @Test
    fun testGenerate() {
        assertTrue(mockJugg.pathManager.gradleProjectInfoFile.exists())
        assertTrue(mockJugg.pathManager.gradleProjectInfoFile.length() > 0)
    }

    @Test
    fun `collects Kotlin common source directories from Android compilation`() {
        val projectInfo = ProjectInfoSerializer(gradleProjectInfo, logger).load()
            ?: error("Gradle project info was not generated")
        val module = projectInfo.modules["kmpCompose"]
            ?: error("kmpCompose module was not found")
        val getter = module.javaClass.methods.singleOrNull {
            it.name == "getKotlinCommonSourceDirs"
        } ?: error("ModuleInfo.kotlinCommonSourceDirs is missing")
        @Suppress("UNCHECKED_CAST")
        val commonSourceDirs = getter.invoke(module) as List<File>
        val relativePaths = commonSourceDirs.map {
            it.relativeTo(module.moduleRootDir).path.replace('\\', '/')
        }.toSet()
        val sourceRelativePaths = module.sourceDirs.map {
            it.relativeTo(module.moduleRootDir).path.replace('\\', '/')
        }.toSet()

        assertTrue("src/commonMain/kotlin" in relativePaths, relativePaths.toString())
        assertTrue("src/sharedMain/kotlin" in relativePaths, relativePaths.toString())
        assertTrue("src/androidMain/kotlin" !in relativePaths, relativePaths.toString())
        assertTrue(sourceRelativePaths.containsAll(relativePaths), sourceRelativePaths.toString())
    }

    @Test
    fun `collects AGP R8 classpath from Android plugin`() {
        val projectInfo = ProjectInfoSerializer(gradleProjectInfo, logger).load()
            ?: error("Gradle project info was not generated")
        val r8Classpath = projectInfo.agpR8Classpath
            ?: error("AGP R8 classpath was not collected")

        assertTrue(r8Classpath.exists(), r8Classpath.absolutePath)
        if (r8Classpath.isFile) {
            JarFile(r8Classpath).use {
                assertTrue(it.getJarEntry("com/android/tools/r8/D8.class") != null)
            }
        } else {
            assertTrue(File(r8Classpath, "com/android/tools/r8/D8.class").exists())
        }
        URLClassLoader(arrayOf(r8Classpath.toURI().toURL()), ClassLoader.getPlatformClassLoader()).use {
            val originClass = it.loadClass("com.android.tools.r8.origin.Origin")
            val commandClass = it.loadClass("com.android.tools.r8.D8Command")
            val origin = originClass.getMethod("root").invoke(null)
            val builder = commandClass.getMethod("parse", Array<String>::class.java, originClass)
                .invoke(null, emptyArray<String>(), origin)

            assertEquals("com.android.tools.r8.D8Command\$Builder", builder.javaClass.name)
        }
    }

    private fun assertJsonObjectEquals(keyName: String, except: JSONObject, actual: JSONObject) {
        if (except == actual) {
            return
        }
        val exceptKeys = except.keySet()
        val actualKeys = actual.keySet()
        val missingKeys = exceptKeys - actualKeys
        assertTrue(missingKeys.isEmpty(), "missing keys: $missingKeys in $keyName}")
        val moreKeys = actualKeys - exceptKeys
        assertTrue(moreKeys.isEmpty(), "more keys: $moreKeys in $keyName")

        exceptKeys.sorted().forEach { key ->
            val exceptValue = except.get(key)
            val actualValue = actual.get(key)
            if ((exceptValue is JSONObject) && (actualValue is JSONObject)) {
                assertJsonObjectEquals("$keyName.$key", exceptValue, actualValue)
            } else if ((exceptValue is JSONArray) && (actualValue is JSONArray)) {
                assertJsonArrayEquals("$keyName.$key", exceptValue, actualValue)
            } else {
                assertEquals(exceptValue, actualValue, "$keyName except ${exceptValue::class.simpleName}, actual ${actualValue::class.simpleName}")
            }
        }
    }

    private fun assertJsonArrayEquals(keyName: String, except: JSONArray, actual: JSONArray) {
        if (except == actual) {
            return
        }
        if (except.length() == 0 && actual.length() == 0) {
            return
        }
        if (except.length() != actual.length()) {
            assertTrue(false, "$keyName size except size ${except.length()} != actual size ${actual.length()}")
        }

        val type = except.get(0)

        when (type) {
            is String, Int, Long, Float, Double, Char, Short, Boolean -> {
                except.forEachIndexed { index, value ->
                    assertEquals(value, actual.get(index), "$keyName array not equals, except $except, actual: $actual")
                }
            }
            is JSONObject -> {
                except.forEachIndexed { index, value ->
                    assertJsonObjectEquals(keyName, value as JSONObject, actual.get(index) as JSONObject)
                }
            }
            is JSONArray -> {
                except.forEachIndexed { index, value ->
                    assertJsonArrayEquals(keyName, value as JSONArray, actual.get(index) as JSONArray)
                }
            }
        }
    }

    @Test
    fun testReadWrite() {
        var serializer = ProjectInfoSerializer(ideProjectInfoFile, logger)

        var count = 10
        var cost = 0L
        while (count-- > 0) {
            serializer = ProjectInfoSerializer(ideProjectInfoFile, logger)
            cost += measureTimeMillis {
                serializer.load()
            }
        }
        println("load cost ${cost/count}ms")

        val tmpFile = File(buildDir, "project_infos.json")
        val tmpSerializer = ProjectInfoSerializer(tmpFile, logger)

        val data = serializer.load()
        count = 10
        cost = 0L
        while (count-- > 0) {
            cost += measureTimeMillis {
                tmpSerializer.save(data)
            }
        }
        println("save cost ${cost/count}ms")
    }

}
