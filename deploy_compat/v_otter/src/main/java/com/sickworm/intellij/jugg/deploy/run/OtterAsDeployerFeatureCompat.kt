package com.sickworm.intellij.jugg.deploy.run

import com.android.tools.idea.gradle.dsl.android.model.android.android
import com.android.tools.idea.gradle.dsl.api.ProjectBuildModel
import com.android.tools.idea.gradle.project.model.GradleAndroidModel
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.module.Module
import org.jetbrains.android.facet.AndroidFacet

open class OtterAsDeployerFeatureCompat: NarwhalAsDeployerFeatureCompat() {

    override fun getIdeModuleInfo(project: Project, module: Module, logger: Logger, isSafeMode: Boolean): IdeModuleInfo? {
        val projectBuildModel = ProjectBuildModel.get(project)
        val buildModel = projectBuildModel.getModuleBuildModel(module) ?: return null
        val gradleVariableHelper = GradleVariableHelper(isSafeMode)

        val androidFacet = AndroidFacet.getInstance(module)
        var buildVariant = androidFacet?.properties?.SELECTED_BUILD_VARIANT
        if (buildVariant.isNullOrEmpty()) {
            buildVariant = "debug"
        }
        val gradleAndroidModel = runCatching { GradleAndroidModel.get(module) }.getOrNull()
        val androidTestPackageInfo = readAndroidTestPackageInfo(gradleAndroidModel)

        return IdeModuleInfo(
            baseDir = module.guessModuleDirAdv(projectBuildModel),
            buildToolsVersion = gradleVariableHelper.readVariable(
                "buildToolsVersion",
                buildModel,
                { buildModel.android().buildToolsVersion() },
                { this.all { it.isDigit() || it == '.' } }
            ),
            compileVersion = gradleVariableHelper.readVariable(
                "compileVersion",
                buildModel,
                { buildModel.android().compileSdkVersion() },
                { this.all { it.isDigit() || it == '.' } }
            ),
            minSdkVersion = gradleVariableHelper.readVariable(
                "minSdkVersion",
                buildModel,
                { buildModel.android().defaultConfig().minSdkVersion() },
                { this.all { it.isDigit() || it == '.' } }
            ),
            kotlinJvmTarget = gradleVariableHelper.readVariable("kotlinJvmTarget") {
                buildModel.android().kotlinOptions().jvmTarget().toJavaVersion()
            },
            kotlinFreeCompilerArgs = gradleVariableHelper.readVariable("kotlinFreeCompilerArgs") {
                buildModel.android().kotlinOptions().freeCompilerArgs()
                    .toList()?.map { it.toString() } ?: emptyList()
            },
            javaSourceCompatibility = gradleVariableHelper.readVariable("javaSourceCompatibility") {
                buildModel.android().compileOptions().sourceCompatibility().toJavaVersion()
            },
            javaTargetCompatibility = gradleVariableHelper.readVariable("javaTargetCompatibility") {
                buildModel.android().compileOptions().targetCompatibility().toJavaVersion()
            },
            minifyEnabled = gradleVariableHelper.readVariable("minifyEnabled") {
                buildModel.android().buildTypes()
                    .find { it.name() == buildVariant }
                    ?.minifyEnabled()?.toString()
            },
            buildVariant = buildVariant,
            manifestRelativePath = gradleVariableHelper.readVariable("manifestRelativePath") {
                androidFacet?.properties?.MANIFEST_FILE_RELATIVE_PATH
            },
            brokenFields = gradleVariableHelper.brokenFields,
            androidTestApplicationId = androidTestPackageInfo.applicationId,
            androidTestInstrumentationTargetPackage = androidTestPackageInfo.instrumentationTargetPackage,
        )
    }

}
