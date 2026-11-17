package com.sickworm.intellij.jugg.cmdline

import com.sickworm.intellij.jugg.deploy.DeployHistoryData
import com.sickworm.intellij.jugg.project.JuggPathManager
import com.sickworm.intellij.jugg.project.ProjectInfoSerializer
import com.sickworm.intellij.jugg.project.data.JuggProjectInfo
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CiProjectInfoTest {
    @get:Rule val temporary = TemporaryFolder()
    private val logger = StdLogger("CiProjectInfoTest")

    @Test fun `included modules join primary snapshot and survive baseline relocation`() {
        val source = temporary.newFolder("source")
        val paths = JuggPathManager(source)
        val primary = ModuleInfo.virtualModule.copy(name = "app", moduleRootDir = source)
        val external = ModuleInfo.virtualModule.copy(name = "lib", moduleRootDir = temporary.newFolder("lib"))
        ProjectInfoSerializer(paths.gradleProjectInfoFile, logger).save(JuggProjectInfo(mapOf("app" to primary), null))
        val snapshot = File(paths.gradleIncludeBuildsFile.parentFile, "include_build_1_gradle_project_infos.json")
        ProjectInfoSerializer(snapshot, logger).save(JuggProjectInfo(mapOf("lib" to external), null))
        paths.gradleIncludeBuildsFile.writeText("/original/build/machine/${snapshot.name}")
        val merged = loadCiProjectInfo(paths, logger, skipVersionCheck = false)
        assertEquals(setOf("app", "lib"), merged.modules.keys)
        assertEquals(external.moduleRootDir, merged.modules.getValue("lib").moduleRootDir)
        // The primary snapshot already contains backed-up modules after a base build.
        ProjectInfoSerializer(paths.gradleProjectInfoFile, logger).save(merged)
        assertEquals(merged, loadCiProjectInfo(paths, logger, skipVersionCheck = true))
    }

    @Test fun `missing included snapshot cannot silently omit a source repository`() {
        val paths = JuggPathManager(temporary.newFolder("source"))
        ProjectInfoSerializer(paths.gradleProjectInfoFile, logger).save(
            JuggProjectInfo(mapOf("app" to ModuleInfo.virtualModule), null))
        paths.gradleIncludeBuildsFile.writeText("/missing/include_build_1_gradle_project_infos.json")
        assertFailsWith<IllegalStateException> { loadCiProjectInfo(paths, logger, skipVersionCheck = false) }
    }
}
