package com.sickworm.intellij.jugg.deploy.run

import com.intellij.openapi.diagnostic.Logger

/**
 * Android test package metadata read from IDE Gradle models.
 */
data class IdeAndroidTestPackageInfo(
    val applicationId: String?,
    val instrumentationTargetPackage: String?,
)

/**
 * Reads androidTest package metadata across Android Studio model versions.
 */
object IdeAndroidTestPackageReader {

    fun read(gradleAndroidModel: Any?): IdeAndroidTestPackageInfo {
        val androidTestApplicationId = readAndroidTestApplicationId(gradleAndroidModel)
        val mainApplicationId = readMainApplicationId(gradleAndroidModel)
        val instrumentationTargetPackage = if (isLibraryProject(gradleAndroidModel)) {
            androidTestApplicationId
        } else {
            mainApplicationId ?: androidTestApplicationId
        }
        return IdeAndroidTestPackageInfo(androidTestApplicationId, instrumentationTargetPackage)
    }

    fun traceReadResult(
        logger: Logger,
        moduleName: String,
        isSafeMode: Boolean,
        buildVariant: String,
        gradleAndroidModel: Any?,
        packageInfo: IdeAndroidTestPackageInfo,
        brokenFields: List<String>,
    ) {
        logger.trace(
            "IDE module androidTest package metadata: module=$moduleName, isSafeMode=$isSafeMode, " +
                    "buildVariant=$buildVariant, hasGradleAndroidModel=${gradleAndroidModel != null}, " +
                    "gradleAndroidModelClass=${gradleAndroidModel?.javaClass?.name}, " +
                    "androidTestApplicationId=${packageInfo.applicationId}, " +
                    "androidTestInstrumentationTargetPackage=${packageInfo.instrumentationTargetPackage}, " +
                    "brokenFields=$brokenFields"
        )
    }

    fun readAndroidTestApplicationId(gradleAndroidModel: Any?): String? {
        return readArtifactApplicationId(gradleAndroidModel, "getArtifactCoreForAndroidTest")
            ?: readArtifactApplicationId(gradleAndroidModel, "getArtifactForAndroidTest")
            ?: readSelectedBasicVariantString(gradleAndroidModel, "getTestApplicationId")
            ?: readAndroidProjectString(gradleAndroidModel, "getTestNamespace")
            ?: readAndroidProjectString(gradleAndroidModel, "getNamespace")?.let { "$it.test" }
    }

    fun readMainApplicationId(gradleAndroidModel: Any?): String? {
        return readDirectString(gradleAndroidModel, "getApplicationId")
            ?: readArtifactApplicationId(gradleAndroidModel, "getMainArtifact")
            ?: readSelectedBasicVariantString(gradleAndroidModel, "getApplicationId")
    }

    // Keep this reflective because Android Studio versions expose model methods with different APIs.
    private fun readArtifactApplicationId(gradleAndroidModel: Any?, methodName: String): String? {
        val artifact = gradleAndroidModel?.invokeNoArgMethod(methodName) ?: return null
        return artifact.invokeNoArgMethod("getApplicationId") as? String
    }

    private fun readDirectString(target: Any?, methodName: String): String? {
        return target?.invokeNoArgMethod(methodName) as? String
    }

    private fun readSelectedBasicVariantString(gradleAndroidModel: Any?, methodName: String): String? {
        val selectedBasicVariant = gradleAndroidModel?.invokeNoArgMethod("getSelectedBasicVariant") ?: return null
        return selectedBasicVariant.invokeNoArgMethod(methodName) as? String
    }

    private fun readAndroidProjectString(gradleAndroidModel: Any?, methodName: String): String? {
        val androidProject = gradleAndroidModel?.invokeNoArgMethod("getAndroidProject") ?: return null
        return androidProject.invokeNoArgMethod(methodName) as? String
    }

    private fun isLibraryProject(gradleAndroidModel: Any?): Boolean {
        val androidProject = gradleAndroidModel?.invokeNoArgMethod("getAndroidProject") ?: return false
        val projectType = androidProject.invokeNoArgMethod("getProjectType") ?: return false
        return projectType.toString() == "PROJECT_TYPE_LIBRARY"
    }

    private fun Any.invokeNoArgMethod(methodName: String): Any? {
        return runCatching {
            javaClass.methods.firstOrNull { it.name == methodName && it.parameterCount == 0 }?.invoke(this)
        }.getOrNull()
    }
}
