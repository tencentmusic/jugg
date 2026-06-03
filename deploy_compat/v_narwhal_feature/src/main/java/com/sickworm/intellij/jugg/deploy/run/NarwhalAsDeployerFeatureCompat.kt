package com.sickworm.intellij.jugg.deploy.run

import com.android.tools.deployer.*
import com.android.tools.deployer.model.App
import com.android.utils.ILogger
import com.android.tools.deployer.model.DeploymentPlan
import com.android.tools.idea.gradle.dsl.api.ProjectBuildModel
import com.android.tools.idea.gradle.project.model.GradleAndroidModel
import com.android.tools.idea.run.AndroidRunConfiguration
import com.android.tools.idea.run.AndroidRunConfigurationType
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.android.tools.idea.gradle.model.IdeAndroidArtifactCore
import com.android.tools.idea.gradle.model.IdeAndroidProject
import com.android.tools.idea.gradle.model.IdeSigningConfig
import com.intellij.openapi.module.Module
import org.jetbrains.android.facet.AndroidFacet
import java.io.File
import java.nio.file.Path

open class NarwhalAsDeployerFeatureCompat: MeerkatAsDeployerCompat() {

    override fun install(
        adb: AdbClient,
        service: UIService,
        installer: Installer,
        logger: ILogger,
        packageName: String,
        apks: List<String>,
        options: InstallOptions,
        installMode: Deployer.InstallMode,
    ): Boolean {
        val apkInstaller = ApkInstaller(adb, service, installer, logger)

        // only deployerOption.maxDeltaInstallPatchSize is read. if maxDeltaInstallPatchSize reach limits
        // then "Falling back to standard full install"
        val deployOptions = DeployerOption.Builder().setMaxDeltaInstallPatchSize(0).build()

        val app = App.fromPaths(packageName, apks.map { Path.of(it) })
        val deploymentPlan = DeploymentPlan(adb.device, app)
        return apkInstaller.install(deploymentPlan, deployOptions, options, installMode, metrics.deployMetrics)
    }

    override fun getSuggestRunConfigurations(
        existsRunConfigNames: List<String>,
        project: Project,
        logger: Logger,
        isNeedDefaultRunConfig: Boolean,
    ): List<SuggestRunConfiguration> {
        val result = mutableListOf<SuggestRunConfiguration>()

        val existsModuleForRunConfig = existsRunConfigNames.map {
            SuggestRunConfiguration.getModuleNameByRunConfigName(it)
        }.toSet()

        // returns empty with new created project, have to use allConfigurationsList and filter by myself
//        var androidConfigSettings = RunManager.getInstance(project)
//            .getConfigurationSettingsList(AndroidRunConfigurationType::class.java)
//        logger.debug("androidConfigSettings ${androidConfigSettings.map { it.name }}")

        val allConfigSettings = RunManager.getInstance(project).allSettings
        logger.debug("allConfigSettings ${allConfigSettings.map { "${it.name}(${it.type})" }}")
        val androidConfigSettings = allConfigSettings.filter { it.type is AndroidRunConfigurationType }
        logger.debug("androidConfigSettings ${androidConfigSettings.map { it.name }}")

        androidConfigSettings.forEach { configSettings ->
            val suggestRunConfig = getSuggestRunConfiguration(configSettings, project, logger)
            if (suggestRunConfig == null) {
                logger.debug("getSuggestRunConfigurations: runConfig ${configSettings.name} suggestRunConfig " +
                        "is null, ignore")
                return@forEach
            }
            if (suggestRunConfig.moduleName in existsModuleForRunConfig) {
                logger.debug("getSuggestRunConfigurations: runConfig ${configSettings.name} already has relative " +
                        "Jugg config ${suggestRunConfig.runConfigName}, ignore")
                return@forEach
            }
            logger.debug("getSuggestRunConfigurations: add suggest runConfig $suggestRunConfig")
            result.add(suggestRunConfig)
        }

        if (result.isEmpty() && existsRunConfigNames.isEmpty() && isNeedDefaultRunConfig) {
            logger.debug("getSuggestRunConfigurations: no suggest run config and no exists run config, use default")
            return listOf(SuggestRunConfiguration.DEFAULT)
        }

        return result
    }

    private fun getSuggestRunConfiguration(settings: RunnerAndConfigurationSettings,
                                           project: Project,
                                           logger: Logger,
    ): SuggestRunConfiguration? {
        try {
            // get build module
            val runConfig = settings.configuration as AndroidRunConfiguration
            val module = runConfig.modules.firstOrNull()
            if (module == null) {
                logger.debug("getSuggestRunConfiguration module of runConfig ${runConfig.name} is null")
                return null
            }
            val gradleAndroidModel = GradleAndroidModel.get(module)
            try {
                logger.debug("getSuggestRunConfiguration gradleAndroidModel: ${gradleAndroidModel?.getDesc()}")
            } catch (e: Throwable) {
                logger.debug("print gradleAndroidModel failed", e)
            }
            gradleAndroidModel ?: return null

            // get compile command
            val moduleName = gradleAndroidModel.moduleName.split('.').last()
            val taskName = gradleAndroidModel.mainArtifact.assembleTaskName
            val compileCommand = "./gradlew :$moduleName:$taskName"

            // get apk
            val projectPath = project.basePath!!
            val buildType = gradleAndroidModel.selectedVariant.buildType
            var productFlavorPath = ""
            if (gradleAndroidModel.selectedVariant.productFlavors.isNotEmpty()) {
                gradleAndroidModel.selectedVariant.productFlavors.forEach { flavor ->
                    if (productFlavorPath.isEmpty()) {
                        productFlavorPath = flavor
                    } else {
                        productFlavorPath += flavor.replaceFirstChar { it.uppercaseChar() }
                    }
                }
                productFlavorPath += "/"
            }
            val moduleRelativePath = gradleAndroidModel.rootDirPath.relativeTo(File(projectPath)).path
            val apkPath = moduleRelativePath.replace("\\", "/") + "/build/outputs/apk/$productFlavorPath$buildType/*.apk"
            logger.debug("getSuggestRunConfiguration use apk output path: $apkPath")

            return SuggestRunConfiguration(moduleName, compileCommand, apkPath)
        } catch (e: Exception) {
            logger.debug("getSuggestRunConfiguration for ${settings.name} error, ignore", e)
            return null
        }
    }

    private fun GradleAndroidModel.getDesc(): String {
        return "GradleAndroidModel: " +
                "moduleName: ${moduleName}, " +
                "rootDirPath: ${rootDirPath}, " +
                "filteredVariantNames: ${try { filteredVariantNames } catch (e: Throwable) { e::class.simpleName }}, " +
                "minSdkVersion: ${minSdkVersion}, " +
                "isDebuggable: ${isDebuggable}, " +
                "variant: ${selectedVariant.name}, " +
                "buildType: ${selectedVariant.buildType}, " +
                "productFlavors: ${selectedVariant.productFlavors}, " +
                "agpVersion: ${androidProject.agpVersion}, " +
                "allApplicationIds: ${allApplicationIds}, " +
                "isBaseSplit: ${isBaseSplit}, " +
                "mainArtifact: ${mainArtifact.getDesc()}, " +
                "androidProject: ${androidProject.getDesc()}, " +
                ""
    }

    private fun IdeAndroidArtifactCore.getDesc(): String {
        return "IdeAndroidArtifact: " +
                "assembleTaskName: $assembleTaskName, " +
                "unresolvedDependencies: $unresolvedDependencies, " +
                "signingConfigName: $signingConfigName, " +
                "isSigned: $isSigned, " +
                "buildInformation: $buildInformation" +
                ""
    }

    private fun IdeAndroidProject.getDesc(): String {
        return "IdeAndroidProject: " +
                "compileTarget: $compileTarget, " +
                "bootClasspath: $bootClasspath, " +
                "signingConfigs: ${signingConfigs.map { it.getDesc() }}, " +
                "javaCompileOptions: $javaCompileOptions, " +
                "viewBindingOptions: $viewBindingOptions, " +
                "namespace: $namespace, " +
                "agpFlags: $agpFlags, " +
                "variantsBuildInformation: ${variantsBuildInformation.map { it.variantName }}, " +
                ""
    }

    private fun IdeSigningConfig.getDesc(): String {
        return "IdeSigningConfig(name=$name, " +
                "storeFile=${if (storeFile == null) "null" else if (!storeFile!!.exists()) "not exists" else "exists"}, " +
                "storePassword=${if (storePassword == null) "null" else "not null"}, " +
                "keyAlias=${if (keyAlias == null) "null" else "not null"}"
    }

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
        val androidTestApplicationId = readAndroidTestApplicationId(gradleAndroidModel)
        val mainApplicationId = readMainApplicationId(gradleAndroidModel)

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
            androidTestApplicationId = androidTestApplicationId,
            androidTestInstrumentationTargetPackage = mainApplicationId,
        )
    }

    protected fun readAndroidTestApplicationId(gradleAndroidModel: Any?): String? {
        return readArtifactApplicationId(gradleAndroidModel, "getArtifactCoreForAndroidTest")
    }

    protected fun readMainApplicationId(gradleAndroidModel: Any?): String? {
        return readArtifactApplicationId(gradleAndroidModel, "getMainArtifact")
    }

    // Keep this reflective because newer Android Studio jars expose artifact core methods inconsistently to Kotlin.
    private fun readArtifactApplicationId(gradleAndroidModel: Any?, methodName: String): String? {
        val artifact = gradleAndroidModel?.invokeNoArgMethod(methodName) ?: return null
        return artifact.invokeNoArgMethod("getApplicationId") as? String
    }

    private fun Any.invokeNoArgMethod(methodName: String): Any? {
        return runCatching {
            javaClass.methods.firstOrNull { it.name == methodName && it.parameterCount == 0 }?.invoke(this)
        }.getOrNull()
    }


    fun test(): String {
        return "NarwhalAsDeployerCompat test"
    }
}
