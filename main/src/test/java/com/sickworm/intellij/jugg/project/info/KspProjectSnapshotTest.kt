package com.sickworm.intellij.jugg.project.info

import com.google.gson.JsonParser
import com.sickworm.intellij.jugg.gradle.script.ProjectInfoSerializerInGradle
import com.sickworm.intellij.jugg.project.info.ProjectInfoSerializer
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.*

/** Protects KSP classpath roles, round trips, and the snapshot refresh boundary. */
class KspProjectSnapshotTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun groovyAndGsonPreserveSelectedKspEnvironment() {
        listOf(false, true).forEach { ksp2 ->
            val module = ModuleInfo.virtualModule.copy(name = "routes", kspInfo = KspInfo(
                processorDependencies = listOf(LibraryDependency("processor", File("/project/infra/processor.jar"))),
                runtimeDependencies = listOf(LibraryDependency("engine", File("/cache/engine.jar"))),
                useKsp2 = ksp2, arguments = mapOf("MODULE_NAME" to "routes", "token" to "a=b"),
                languageVersion = "1.9", apiVersion = "1.9"))
            val plain = ModuleInfo.virtualModule.copy(name = "plain")
            val snapshot = temporary.newFile("snapshot-$ksp2.json")
            ProjectInfoSerializerInGradle(snapshot).save(
                JuggProjectInfo(mapOf(module.name to module, plain.name to plain), agpR8Classpath = null))
            val readers = listOf(ProjectInfoSerializerInGradle(snapshot).load()!!,
                ProjectInfoSerializer.gson.fromJson(snapshot.readText(), JuggProjectInfoSerialize::class.java))
            readers.forEach { serialized ->
                val restored = JuggProjectInfoSerialize.deserialize(serialized).modules
                assertEquals(module.kspInfo, restored.getValue("routes").kspInfo)
                assertNull(restored.getValue("plain").kspInfo)
            }
        }
    }

    @Test fun versionNineSnapshotsRequireRefreshForBothReaders() {
        val json = JsonParser.parseString(ProjectInfoSerializer.gson.toJson(
            JuggProjectInfoSerialize.serialize(JuggProjectInfo(mapOf("routes" to ModuleInfo.virtualModule.copy(name = "routes")),
                agpR8Classpath = null)))).asJsonObject
        json.addProperty("version", 9)
        val snapshot = temporary.newFile("old-snapshot.json").apply { writeText(json.toString()) }
        val readers = listOf(ProjectInfoSerializerInGradle(snapshot).load()!!,
            ProjectInfoSerializer.gson.fromJson(snapshot.readText(), JuggProjectInfoSerialize::class.java))
        readers.forEach { serialized ->
            assertEquals(9, serialized.version)
            val failure = assertFailsWith<IllegalArgumentException> { JuggProjectInfoSerialize.deserialize(serialized) }
            assertTrue(failure.message!!.contains("expectVersion=10"))
            assertNull(JuggProjectInfoSerialize.deserialize(serialized, isSkipVersionCheck = true)
                .modules.getValue("routes").kspInfo)
        }
    }
}
