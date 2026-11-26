package com.sickworm.intellij.jugg.project.dependency

import com.intellij.openapi.diagnostic.Logger
import org.junit.Assert.assertEquals
import org.junit.Test

class DependencyChangeManagerByGradleTest {

    @Test
    fun `apply dependency decision should update change status`() {
        val manager = DependencyChangeManagerByGradle(Logger.getInstance("DependencyChangeManagerByGradleTest"))
        val diffResult = DependencyDiffResultSet.createEmpty()

        manager.applyDependencyChangeDecision(diffResult, true)

        assertEquals(IDependencyChangeManager.ChangeStatus.INCREMENTAL_COMPILE, manager.changeStatus)
    }

    @Test
    fun `negative dependency decision should fallback to rebuild when build file changed`() {
        val manager = DependencyChangeManagerByGradle(Logger.getInstance("DependencyChangeManagerByGradleTest"))
        manager.onUpdateChangedBuildFiles(listOf(java.io.File("build.gradle")))

        manager.applyDependencyChangeDecision(DependencyDiffResultSet.createEmpty(), false)

        assertEquals(IDependencyChangeManager.ChangeStatus.REBUILD, manager.changeStatus)
    }
}
