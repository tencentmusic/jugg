@file:Suppress("DEPRECATION", "unused")

package com.sickworm.intellij.jugg.gradle.script

import com.sickworm.intellij.jugg.project.JuggPathManager
import groovy.util.Node
import groovy.util.NodeList
import groovy.util.XmlNodePrinter
import groovy.xml.QName
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.provider.Property
import java.io.File
import java.io.PrintWriter

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

        addRuntimeDependency(project)
        rootProject.gradle.projectsEvaluated {
            injectManifestTask(project)
        }
    }

    private fun injectManifestTask(project: Project) {
        val androidExt = Reflector(project.extensions.getByName("android"))
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
        val name = Reflector(variant)["name"]?.valueString ?: return
        val capitalizedName = name.capitalize() // compat kotlin 1.4, name.capitalize(Locale.ROOT)
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

        // Jugg has XmlParer too, and it will compile together into readProjectInfo.gradle.kts.
        // Here we use full name to avoid conflicting.
        val manifestRoot: Node = groovy.util.XmlParser().parse(mergedManifest)
        val application = ((manifestRoot.get("application") as? NodeList)?.firstOrNull() as? Node)
        println("Jugg application node exists: ${application != null}")
        if (application == null) {
            throw IllegalStateException("Wrong format in AndroidManifest, no application node is found !")
        }

        var originApplicationName: String? = null
        var originAppComponentName: String? = null
        val attributes = application.attributes() as? MutableMap<Any?, Any?>
        println("Jugg attributes size: ${attributes?.size}")

        // don't import BuildConfig cause it a java file which can not included in readProjectInfo.gradle.kts
        val juggApplicationName = "com.sickworm.intellij.jugg.hotfix.BootstrapApplication"  // BuildConfig.INJECT_APPLICATION_NAME
        val juggAppComponentName = "com.sickworm.intellij.jugg.hotfix.BootstrapAppComponentFactory"  // BuildConfig.INJECT_APP_COMPONENT_FACTORY_NAME

        attributes?.forEach {
            if((it.key as? QName)?.localPart == "name") {
                originApplicationName = it.value?.toString()
                attributes[it.key] = juggApplicationName
            }
            if ((it.key as? QName)?.localPart == "appComponentFactory") {
                originAppComponentName = it.value?.toString()
                attributes[it.key] = juggAppComponentName
            }
        }
        if (originApplicationName == null) {
            println("Jugg: originApplicationName is null, add name attribute to application")
            attributes?.put("android:name", "com.sickworm.intellij.jugg.hotfix.BootstrapApplication")  // BuildConfig.INJECT_APPLICATION_NAME
        } else if (originApplicationName != juggApplicationName) {
            application.appendNode("meta-data", mapOf(
                // don't import BuildConfig cause it a java file which can not included in readProjectInfo.gradle.kts
                "android:name" to "com.sickworm.intellij.jugg.hotfix.raw.application", // BuildConfig.META_DATA_LABEL_RAW_APPLICATION
                "android:value" to originApplicationName,
            ))
        }
        if (originAppComponentName == null) {
            println("Jugg: originAppComponentName is null, no need to handle")
        } else if (originAppComponentName != juggAppComponentName) {
            application.appendNode("meta-data", mapOf(
                "android:name" to "com.sickworm.intellij.jugg.hotfix.raw.appComponentFactory", // BuildConfig.META_DATA_LABEL_RAW_APP_COMPONENT_FACTORY
                "android:value" to originAppComponentName,
            ))
            attributes?.put("android:backupAgent", "com.sickworm.intellij.jugg.hotfix.raw.appComponentFactory") // BuildConfig.INJECT_APP_COMPONENT_FACTORY_NAME
        }

        val printer = XmlNodePrinter(PrintWriter(mergedManifest.absolutePath, "utf-8"))
        printer.isPreserveWhitespace = true
        printer.print(manifestRoot)
    }

    private fun addRuntimeDependency(project: Project) {
        // Prefer jugg.projectDir to support projects where Gradle root != IDE project dir
        val projectDir = rootProject.properties["jugg.projectDir"]?.toString()?.let { File(it) }
            ?: rootProject.rootDir
        val jarDir = JuggPathManager(projectDir).configDir
        if (!jarDir.exists() || jarDir.listFiles().isNullOrEmpty()) {
            throw IllegalStateException("Jugg jarDir: $jarDir not exists or has no jar files")
        }
        val runtimeConfiguration = project.fileTree(mapOf("dir" to jarDir.path, "include" to listOf("*.jar")))
        project.dependencies.add("runtimeOnly", runtimeConfiguration)
    }

    private fun findMergedManifest(task: Task): List<File> {
        // task: ProcessMultiApkApplicationManifest
        val dirValue = (Reflector(task)["multiApkManifestOutputDirectory"]?.value as? Property<*>)?.get()
        val mergedManifestDir: File? = (dirValue as? org.gradle.api.file.FileSystemLocation)?.asFile
        if (mergedManifestDir != null) {
            val manifestFile = findMergedManifest(mergedManifestDir)
            if (manifestFile.isNotEmpty()) {
                return manifestFile
            }
        }
        val dirValue2 = (Reflector(task)["manifestOutputDirectory"]?.value as? Property<*>)?.get()
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

}