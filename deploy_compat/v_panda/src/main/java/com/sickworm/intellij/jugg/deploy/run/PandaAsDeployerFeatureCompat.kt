package com.sickworm.intellij.jugg.deploy.run

import com.android.tools.idea.gradle.dsl.api.GradleBuildModel
import com.android.tools.idea.gradle.dsl.api.android.AndroidModel
import com.android.tools.idea.gradle.project.model.GradleAndroidModel
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.rootManager
import com.intellij.openapi.vfs.VfsUtil
import org.jetbrains.android.facet.AndroidFacet
import java.io.File

/**
 * Panda version compatibility layer.
 */
open class PandaAsDeployerFeatureCompat : OtterAsDeployerFeatureCompat() {

    override fun getIdeModuleInfo(project: Project, module: Module, logger: Logger, isSafeMode: Boolean): IdeModuleInfo? {
        val buildModel = GradleBuildModel.get(module) ?: return null
        val gradleVariableHelper = GradleVariableHelper(isSafeMode)

        val androidFacet = AndroidFacet.getInstance(module)
        var buildVariant = androidFacet?.properties?.SELECTED_BUILD_VARIANT
        if (buildVariant.isNullOrEmpty()) {
            buildVariant = "debug"
        }
        val gradleAndroidModel = runCatching { GradleAndroidModel.get(module) }.getOrNull()
        val androidTestPackageInfo = readAndroidTestPackageInfo(gradleAndroidModel)

        val result = IdeModuleInfo(
            baseDir = module.guessModuleDirAdvByBuildModel(buildModel),
            buildToolsVersion = gradleVariableHelper.readVariable(
                "buildToolsVersion",
                buildModel,
                { buildModel.android().buildToolsVersion() },
                { this.all { it.isDigit() || it == '.' } }
            ),
            compileVersion = gradleVariableHelper.readVariable("compileVersion") {
                // Try new compileSdk { } block syntax (AGP 8.x+ / Gradle 9.0)
                val compileSdkProp = buildModel.android().compileSdkVersion()
                compileSdkProp.toCompileSdkConfig()?.getVersion()?.toInt()?.toString()
            } ?: gradleVariableHelper.readVariable(
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
        IdeAndroidTestPackageReader.traceReadResult(
            logger = logger,
            moduleName = module.name,
            isSafeMode = isSafeMode,
            buildVariant = buildVariant,
            gradleAndroidModel = gradleAndroidModel,
            packageInfo = androidTestPackageInfo,
            brokenFields = result.brokenFields,
        )
        return result
    }

    private fun Module.guessModuleDirAdvByBuildModel(buildModel: GradleBuildModel): File? {
        val gradleRootDir = buildModel.moduleRootDirectory
        if (gradleRootDir != null) {
            return gradleRootDir
        }

        val contentRoots = rootManager.contentRoots.filter { it.isDirectory }
        val virtualFile = contentRoots.find { name.endsWith(it.name) }
            ?: contentRoots.firstOrNull()
            ?: moduleFile?.parent
            ?: return null
        val file = VfsUtil.virtualToIoFile(virtualFile)
        // java.lang.IllegalArgumentException: this and base files have different roots:
        // rootProject.projectDir/my-service-interface and
        // /Users/sickworm/IdeaProjects/Example_Android.
        if (file.path.startsWith("rootProject.projectDir")) {
            val relativePath = file.path.substring("rootProject.projectDir".length + 1)
            return File(relativePath)
        }
        return file
    }

    private fun GradleBuildModel.android(): AndroidModel {
        return getModel(AndroidModel::class.java)
    }
}
