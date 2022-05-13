package com.sickworm.intellij.jugg.mock

import com.android.tools.idea.gradle.dsl.api.*
import com.android.tools.idea.gradle.dsl.api.android.*
import com.android.tools.idea.gradle.dsl.api.configurations.ConfigurationsModel
import com.android.tools.idea.gradle.dsl.api.dependencies.DependenciesModel
import com.android.tools.idea.gradle.dsl.api.ext.ExtModel
import com.android.tools.idea.gradle.dsl.api.ext.GradlePropertyModel
import com.android.tools.idea.gradle.dsl.api.ext.ResolvedPropertyModel
import com.android.tools.idea.gradle.dsl.api.java.JavaModel
import com.android.tools.idea.gradle.dsl.api.java.LanguageLevelPropertyModel
import com.android.tools.idea.gradle.dsl.api.repositories.RepositoriesModel
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.pom.java.LanguageLevel
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import java.io.File

class MockGradleBuildModel: GradleBuildModel {

    override fun android(): AndroidModel {
        val compileSdkVersion = mock(ResolvedPropertyModel::class.java)
        `when`(compileSdkVersion.valueAsString()).thenReturn(androidBuildTools.name.substring(0, 2))

        val buildToolsVersion = mock(ResolvedPropertyModel::class.java)
        `when`(buildToolsVersion.valueAsString()).thenReturn(androidBuildTools.name)

        val compileOptionsModel = mock(CompileOptionsModel::class.java)
        val languageLevelPropertyModel = mock(LanguageLevelPropertyModel::class.java)
        `when`(languageLevelPropertyModel.toLanguageLevel()).thenReturn(LanguageLevel.JDK_1_8) // TODO read from build.gradle
        `when`(compileOptionsModel.sourceCompatibility()).thenReturn(languageLevelPropertyModel)
        `when`(compileOptionsModel.targetCompatibility()).thenReturn(languageLevelPropertyModel)

        val kotlinOptionsModel = mock(KotlinOptionsModel::class.java)
        val jvmTarget = mock(LanguageLevelPropertyModel::class.java)
        `when`(jvmTarget.toLanguageLevel()).thenReturn(LanguageLevel.JDK_1_8) // TODO read from build.gradle
        `when`(kotlinOptionsModel.jvmTarget()).thenReturn(jvmTarget)

        val androidModel = mock(AndroidModel::class.java)
        `when`(androidModel.sourceSets()).thenReturn(mutableListOf())
        `when`(androidModel.buildToolsVersion()).thenReturn(buildToolsVersion)
        `when`(androidModel.compileSdkVersion()).thenReturn(compileSdkVersion)
        `when`(androidModel.compileOptions()).thenReturn(compileOptionsModel)
        `when`(androidModel.kotlinOptions()).thenReturn(kotlinOptionsModel)

        return androidModel
    }

    override fun getInScopeProperties(): MutableMap<String, GradlePropertyModel> {
        return mutableMapOf()
    }

    override fun getDeclaredProperties(): MutableList<GradlePropertyModel> {
        TODO("Not yet implemented")
    }

    override fun getPsiElement(): PsiElement? {
        TODO("Not yet implemented")
    }

    override fun getProject(): Project {
        TODO("Not yet implemented")
    }

    override fun reparse() {
        TODO("Not yet implemented")
    }

    override fun isModified(): Boolean {
        TODO("Not yet implemented")
    }

    override fun resetState() {
        TODO("Not yet implemented")
    }

    override fun getVirtualFile(): VirtualFile {
        TODO("Not yet implemented")
    }

    override fun applyChanges() {
        TODO("Not yet implemented")
    }

    override fun getNotifications(): MutableMap<String, MutableList<BuildModelNotification>> {
        TODO("Not yet implemented")
    }

    override fun getPsiFile(): PsiFile? {
        TODO("Not yet implemented")
    }

    override fun plugins(): MutableList<PluginModel> {
        TODO("Not yet implemented")
    }

    override fun applyPlugin(plugin: String): PluginModel {
        TODO("Not yet implemented")
    }

    override fun removePlugin(plugin: String) {
        TODO("Not yet implemented")
    }

    override fun buildscript(): BuildScriptModel {
        TODO("Not yet implemented")
    }

    override fun configurations(): ConfigurationsModel {
        TODO("Not yet implemented")
    }

    override fun dependencies(): DependenciesModel {
        TODO("Not yet implemented")
    }

    override fun ext(): ExtModel {
        TODO("Not yet implemented")
    }

    override fun java(): JavaModel {
        TODO("Not yet implemented")
    }

    override fun repositories(): RepositoriesModel {
        TODO("Not yet implemented")
    }

    override fun getInvolvedFiles(): MutableSet<GradleFileModel> {
        TODO("Not yet implemented")
    }

    override fun getModuleRootDirectory(): File {
        TODO("Not yet implemented")
    }

    override fun removeRepositoriesBlocks() {
        TODO("Not yet implemented")
    }
}