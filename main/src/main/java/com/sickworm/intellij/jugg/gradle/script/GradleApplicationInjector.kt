@file:Suppress("DEPRECATION", "unused")

package com.sickworm.intellij.jugg.gradle.script

import com.sickworm.intellij.jugg.project.JuggPathManager
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.provider.Property
import java.io.File

/**
 * GradleApplicationInjector wires manifest/runtime dependency hooks into Android application variants.
 */
class GradleApplicationInjector(
    private val rootProject: Project,
) {

    companion object {
        const val PARAM_ENABLE = "jugg.inject.application.enable"
    }

    fun injectApplication(project: Project) {
        if (!project.plugins.hasPlugin("com.android.application")) {
            return
        }
        println("Jugg: project ${project.name} is android application, inject manifest task")
        val isEnable = rootProject.properties[PARAM_ENABLE] == "true"
        if (!isEnable) {
            println("Jugg: injectApplication is not enable, ignore")
            return
        }

        injectProguardKeepRulesForVariant(project)
        addRuntimeDependency(project)
        rootProject.gradle.projectsEvaluated {
            injectManifestTask(project)
        }
    }

    private fun injectManifestTask(project: Project) {
        val androidExt = reflector(project.extensions.getByName("android"))
        val applicationVariants = androidExt["applicationVariants"]
        val variants = applicationVariants?.value as? Collection<Any?>
        println("Jugg: project ${project.name} applicationVariants: ${variants?.size}")
        if (variants.isNullOrEmpty()) {
            throw IllegalStateException("Jugg: project ${project.name} applicationVariants is null or empty")
        }

        variants.forEach { variant ->
            injectManifestTask(project, variant)
        }
    }

    @Suppress("DefaultLocale")
    private fun injectManifestTask(project: Project, variant: Any?) {
        val name = reflector(variant)["name"]?.valueString ?: return
        val capitalizedName = name.camelCompat
        val manifestTaskName = "process${capitalizedName}Manifest"
        val manifestTask = project.tasks.findByName(manifestTaskName)
        println("Jugg inject manifestTask: $manifestTaskName, task instance: $manifestTask")

        manifestTask?.doLast {
            println("Jugg manifestTask replace application variant: $name")
            findMergedManifest(manifestTask).forEach {
                tryReplace(it)
            }
            println("Jugg manifestTask doLast finish")
        }
    }

    private fun tryReplace(mergedManifest: File) {
        if (!mergedManifest.exists()) {
            throw IllegalStateException("Jugg tryReplace: mergedManifest is null or not exists")
        }

        InitScriptManifestXmlHelper(mergedManifest).replaceApplication(
            applicationName = "com.sickworm.intellij.jugg.hotfix.BootstrapApplication",
            rawApplicationMetaDataName = "com.sickworm.intellij.jugg.hotfix.raw.application",
            appComponentFactoryName = "com.sickworm.intellij.jugg.hotfix.BootstrapAppComponentFactory",
            rawAppComponentFactoryMetaDataName = "com.sickworm.intellij.jugg.hotfix.raw.appComponentFactory",
        )
    }

    private fun addRuntimeDependency(project: Project) {
        val runtimeJarFile = JuggPathManager(rootProject.rootDir).runtimeJarFilePath
        val runtimeConfiguration = project.files(runtimeJarFile)
        project.dependencies.add("runtimeOnly", runtimeConfiguration)
    }

    private fun findMergedManifest(task: Task): List<File> {
        // task: ProcessMultiApkApplicationManifest
        val dirValue = (reflector(task)["multiApkManifestOutputDirectory"]?.value as? Property<*>)?.get()
        val mergedManifestDir: File? = (dirValue as? org.gradle.api.file.FileSystemLocation)?.asFile
        if (mergedManifestDir != null) {
            val manifestFile = findMergedManifest(mergedManifestDir)
            if (manifestFile.isNotEmpty()) {
                return manifestFile
            }
        }
        val dirValue2 = (reflector(task)["manifestOutputDirectory"]?.value as? Property<*>)?.get()
        val mergedManifestDir2: File? = (dirValue2 as? org.gradle.api.file.FileSystemLocation)?.asFile
        if (mergedManifestDir2 != null) {
            val manifestFile2 = findMergedManifest(mergedManifestDir2)
            if (manifestFile2.isNotEmpty()) {
                return manifestFile2
            }
        }

        throw IllegalStateException("Jugg mergedManifest: task is null or not exists in: multiApkManifestOutputDirectory:$mergedManifestDir or manifestOutputDirectory:$mergedManifestDir2")
    }

    private fun findMergedManifest(dir: File): List<File> {
        // ProcessMultiApkApplicationManifest
        val mergedManifest = File(dir, "AndroidManifest.xml")
        if (mergedManifest.exists()) {
            println("Jugg: use manifest file $mergedManifest")
            return listOf(mergedManifest)
        }

        // compat with split-abi
        val filesInDir = dir.listFilesRecursively()
        val guessMergedManifests = filesInDir.filter {
            it.name == "AndroidManifest.xml"
        }
        if (guessMergedManifests.isNotEmpty()) {
            println("Jugg: use guessMergedManifest $guessMergedManifests")
            return guessMergedManifests
        }
        println("Jugg: findMergedManifest failed in $dir")
        return emptyList()
    }

    private fun File.listFilesRecursively(): List<File> {
        if (!exists()) {
            return emptyList()
        }

        if (isFile) {
            return listOf(this)
        }

        return listFiles()?.flatMap {
            it.listFilesRecursively()
        }?: emptyList()
    }

    @Suppress("DefaultLocale")
    private fun injectProguardKeepRulesForVariant(project: Project) {
        val androidExt = reflector(project.extensions.getByName("android"))
        val buildTypes = androidExt["buildTypes"]
        val buildTypesValue = buildTypes?.value as? Collection<Any?>
        if (buildTypesValue.isNullOrEmpty()) {
            return
        }
        val dynamicRulesFile = project.layout.buildDirectory.file("jugg/proguard-rules.pro").get().asFile
        generateDynamicKeepRules(dynamicRulesFile)

        buildTypesValue.forEach { buildType ->
            val buildTypeReflector = reflector(buildType)
            // Check if minification is enabled
            val isMinifyEnabledValue = buildTypeReflector["isMinifyEnabled"]?.value as? Boolean
            val name = buildTypeReflector["name"]?.valueString ?: return@forEach
            var isMinifyEnabled = isMinifyEnabledValue ?: false
            if (isMinifyEnabledValue == null && name.endsWith("release", ignoreCase = true)) {
                isMinifyEnabled = true // null if not specific, and AGP regard as true
            }
            if (!isMinifyEnabled) {
                return@forEach
            }
            println("Jugg: inject proguard keep rules for buildType: $name")
            buildTypeReflector.invoke("proguardFile",
                Reflector.Value(Any::class.java, dynamicRulesFile))
        }

    }

    private fun generateDynamicKeepRules(outputFile: File) {
        outputFile.parentFile.mkdirs()

        val rules = mutableListOf<String>()

        rules.add("# ============================================================================")
        rules.add("# Jugg Dynamic Keep Rules - Auto-generated")
        rules.add("# Generated at build time to keep all Application and AppComponentFactory subclasses")
        rules.add("# ============================================================================")
        rules.add("# Keep all Application subclasses (generic rule as fallback)")
        rules.add("-keep class * extends android.app.Application { *; }")
        rules.add("-keep class * extends android.app.AppComponentFactory { *; }")

        outputFile.writeText(rules.joinToString("\n"))
    }

}
