package com.sickworm.intellij.jugg.gradle.script

import com.sickworm.intellij.jugg.manager.MockJugg
import com.sickworm.intellij.jugg.mock.buildDir
import com.sickworm.intellij.jugg.mock.logger
import com.sickworm.intellij.jugg.project.ProjectInfoSerializer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectInfoSerializerInGradleTest {

    companion object {

        private val mockJugg = MockJugg()
        private val gradleProjectInfo = mockJugg.pathManager.gradleProjectInfoFile
        private val ideProjectInfoFile = File("src/test/assets/android/modify_source/project_infos.json")

        @JvmStatic
        @BeforeClass
        fun runAndGenerate() {
            val gradleProjectInfoLocalFetchManager = mockJugg.gradleProjectInfoLocalFetchManager
            mockJugg.pathManager.markProjectInfoNeedUpdateFlagFile.delete()
            gradleProjectInfoLocalFetchManager.markIsNeedUpdate(true)
            assertTrue(mockJugg.pathManager.markProjectInfoNeedUpdateFlagFile.exists())

            ideProjectInfoFile.copyTo(mockJugg.pathManager.ideProjectInfoFile, overwrite = true)
            val scriptFile = File("src/main/resources/gradle/readProjectInfo.gradle.kts")
            scriptFile.copyTo(mockJugg.pathManager.initGradleFilePath, overwrite = true)

            mockJugg.pathManager.gradleProjectInfoFile.delete()
            gradleProjectInfoLocalFetchManager.runUpdateIfNeeded()
        }
    }

    @Test
    fun testGenerate() {
        assertTrue(mockJugg.pathManager.gradleProjectInfoFile.exists())
        assertTrue(mockJugg.pathManager.gradleProjectInfoFile.length() > 0)
    }

    @Test
    fun testReadConsistent() {
        val projectInfoSerializer = ProjectInfoSerializer(gradleProjectInfo, logger)
        val tmpFile = File(buildDir, "project_infos.json")
        val tmpSerializer = ProjectInfoSerializer(tmpFile, logger)
        tmpSerializer.save(projectInfoSerializer.load())

        assertTrue(tmpSerializer.dataFile.exists())
        assertTrue(tmpSerializer.dataFile.length() > 0)

        // compare fields
        val gradleJsonObject = JSONObject(tmpSerializer.dataFile.readText())
        val ideJsonObject = JSONObject(projectInfoSerializer.dataFile.readText())
        assertJsonObjectEquals("root", ideJsonObject, gradleJsonObject)
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