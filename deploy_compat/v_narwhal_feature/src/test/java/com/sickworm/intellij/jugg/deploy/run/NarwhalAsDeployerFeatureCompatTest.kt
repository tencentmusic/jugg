package com.sickworm.intellij.jugg.deploy.run

import org.junit.Assert.assertEquals
import org.junit.Test

class NarwhalAsDeployerFeatureCompatTest {

    @Test
    fun `library androidTest uses selected test application id as self target`() {
        val model = FakeGradleAndroidModel(
            androidProject = FakeAndroidProject(projectType = FakeProjectType.PROJECT_TYPE_LIBRARY),
            selectedBasicVariant = FakeBasicVariant(
                applicationId = null,
                testApplicationId = "com.example.library.test",
            ),
        )

        val result = IdeAndroidTestPackageReader.read(model)

        assertEquals("com.example.library.test", result.applicationId)
        assertEquals("com.example.library.test", result.instrumentationTargetPackage)
    }

    @Test
    fun `library androidTest falls back to namespace dot test`() {
        val model = FakeGradleAndroidModel(
            androidProject = FakeAndroidProject(
                projectType = FakeProjectType.PROJECT_TYPE_LIBRARY,
                namespace = "com.example.library",
            ),
        )

        val result = IdeAndroidTestPackageReader.read(model)

        assertEquals("com.example.library.test", result.applicationId)
        assertEquals("com.example.library.test", result.instrumentationTargetPackage)
    }

    @Test
    fun `application androidTest still targets main application id`() {
        val model = FakeGradleAndroidModel(
            androidProject = FakeAndroidProject(projectType = FakeProjectType.PROJECT_TYPE_APP),
            selectedBasicVariant = FakeBasicVariant(
                applicationId = "com.example.app",
                testApplicationId = "com.example.app.test",
            ),
        )

        val result = IdeAndroidTestPackageReader.read(model)

        assertEquals("com.example.app.test", result.applicationId)
        assertEquals("com.example.app", result.instrumentationTargetPackage)
    }

    private class FakeGradleAndroidModel(
        private val androidProject: FakeAndroidProject,
        private val selectedBasicVariant: FakeBasicVariant? = null,
        private val androidTestArtifact: FakeArtifact? = null,
        private val mainArtifact: FakeArtifact? = null,
    ) {
        fun getAndroidProject() = androidProject
        fun getSelectedBasicVariant() = selectedBasicVariant
        fun getArtifactCoreForAndroidTest() = androidTestArtifact
        fun getMainArtifact() = mainArtifact
    }

    private class FakeAndroidProject(
        private val projectType: FakeProjectType,
        private val namespace: String? = null,
        private val testNamespace: String? = null,
    ) {
        fun getProjectType() = projectType
        fun getNamespace() = namespace
        fun getTestNamespace() = testNamespace
    }

    private class FakeBasicVariant(
        private val applicationId: String?,
        private val testApplicationId: String?,
    ) {
        fun getApplicationId() = applicationId
        fun getTestApplicationId() = testApplicationId
    }

    private class FakeArtifact(private val applicationId: String?) {
        fun getApplicationId() = applicationId
    }

    private enum class FakeProjectType {
        PROJECT_TYPE_APP,
        PROJECT_TYPE_LIBRARY,
    }
}
