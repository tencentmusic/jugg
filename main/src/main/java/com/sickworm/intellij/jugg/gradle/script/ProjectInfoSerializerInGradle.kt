package com.sickworm.intellij.jugg.gradle.script

import com.sickworm.intellij.jugg.project.info.*
import groovy.json.JsonBuilder
import groovy.json.JsonGenerator
import groovy.json.JsonSlurper
import java.io.File


/**
 * ProjectInfoSerializerInGradle persists and restores compact project info within Gradle scripts.
 */
class ProjectInfoSerializerInGradle(private val dataFile: File) {

    @Synchronized
    fun save(projectInfo: JuggProjectInfo) {
//        val startTime = System.currentTimeMillis()

        dataFile.parentFile?.mkdirs()
        val juggProjectInfoSerialize = JuggProjectInfoSerialize.serialize(projectInfo)
        val generator = getJsonGenerator()
        val builder = JsonBuilder(juggProjectInfoSerialize, generator)
        val result = builder.toString()
        dataFile.writeText(result)

//        val costTime = System.currentTimeMillis() - startTime
//        println("Jugg: Save project info to ${dataFile.absolutePath} cost $costTime ms")
    }

    @Suppress("UNCHECKED_CAST")
    @Synchronized
    fun load(): JuggProjectInfoSerialize? {
        if (!dataFile.exists()) {
            return null
        }
        try {
//            val startTime = System.currentTimeMillis()
            val jsonSlurper = JsonSlurper()
            var juggProjectInfoSerialize: JuggProjectInfoSerialize? = null
            dataFile.inputStream().use { inputStream ->
                // we can not invoke gson in init.gradle.kts, so...
                // JsonSlurper is not a ORM tool, so we just read what we need: build variant, library dependency
                val json = jsonSlurper.parse(inputStream) as Map<String, Any> // JuggProjectInfoSerialize
                val modules: List<ModuleInfoSerialize> = (json["modules"] as List<Map<String, Any>>).map {
                    val module = it["moduleInfoExceptLibraries"] as Map<String, Any>
                    val projectRootDir = File(module["projectRootDir"] as String)
                    val moduleRootDir = File(module["moduleRootDir"] as String)
                    val buildVariant = module["buildVariant"] as String
                    val buildPath = module["buildPathInfo"] as? Map<String, Any>
                    val moduleInfo = ModuleInfo.virtualModule.copy(
                        name = module["name"] as String,
                        buildVariant = buildVariant,
                        moduleRootDir = moduleRootDir,
                        projectRootDir = projectRootDir,
                        kotlinCommonSourceDirs = (module["kotlinCommonSourceDirs"] as? List<String>)?.map(::File)
                            ?: emptyList(),
                        kotlinFragmentSourceDirs = (module["kotlinFragmentSourceDirs"] as? Map<String, List<String>>)
                            ?.mapValues { (_, paths) -> paths.map(::File) }
                            ?: emptyMap(),
                        kotlinFragmentRefines = module["kotlinFragmentRefines"] as? Map<String, List<String>>
                            ?: emptyMap(),
                        kotlinDefaultFragmentName = module["kotlinDefaultFragmentName"] as? String,
                        buildPathInfo = ModuleBuildPathInfo(
                            projectRootDir,
                            moduleRootDir,
                            buildVariant,
                            buildDirRelativePath = buildPath?.get("buildDirRelativePath") as? String
                                ?: ""
                        ),
                        moduleType = ModuleInfo.Type.valueOf(module["moduleType"] as String),
                        runtimeModuleDependencies = (module["runtimeModuleDependencies"] as? List<Map<String, Any>>)
                            ?.mapNotNull { dependency ->
                                (dependency["moduleName"] as? String)?.let(::ModuleDependency)
                            },
                        instrumentationTargetPackage = module["instrumentationTargetPackage"] as? String,
                        composeResourceInfo = parseComposeResourceInfo(module["composeResourceInfo"]),
                    )
                    ModuleInfoSerialize(
                        moduleInfo,
                        it["libraryDependencies"] as? List<Int>,
                        it["runtimeLibraryDependencies"] as? List<Int>,
                        it["annotationProcessorDependencies"] as? List<Int>,
                        it["kaptDependencies"] as? List<Int>,
                        it["kotlinPlugins"] as? List<Int>,
                        it["kotlinExtensions"] as? List<Int>,
                        it["kspDependencies"] as? List<Int>,
                    )
                }
                val dependencyList = (json["dependencyList"] as List<Map<String, Any>>).map {
                    LibraryDependency(
                        name = it["name"] as String,
                        file = File(it["file"] as String),
                        lastModifiedTime = (it["lastModifiedTime"] as Number).toLong(),
                        crc32 = (it["crc32"] as Number).toLong(), // you will get Int and Long, so convert to Number
                    )
                }
                val rootInfo = json["juggProjectInfoExceptModules"] as? Map<String, Any>
                juggProjectInfoSerialize = JuggProjectInfoSerialize(
                    juggProjectInfoExceptModules = JuggProjectInfo(
                        modules = modules.associate {
                            it.moduleInfoExceptLibraries.name to it.moduleInfoExceptLibraries
                        },
                        agpR8Classpath = (rootInfo?.get("agpR8Classpath") as? String)?.let(::File),
                    ),
                    modules = modules,
                    dependencyList = dependencyList)
            }
//            val costTime = System.currentTimeMillis() - startTime
//            println("Jugg: Load project info to ${dataFile.absolutePath} cost $costTime ms")
            return juggProjectInfoSerialize
        } catch (e: Exception) {
            println("Jugg: Failed to load project info from ${dataFile.absolutePath}, $e")
            printException(e)
            return null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseComposeResourceInfo(value: Any?): ComposeResourceInfo? {
        val composeInfo = value as? Map<String, Any> ?: return null
        val classpath = (composeInfo["generatorClasspath"] as? List<String>)?.map(::File) ?: return null
        val resourceDirectories = (composeInfo["resourceDirectories"] as? List<Map<String, Any>>)?.map {
            ComposeResourceDirectory(
                sourceSetName = it["sourceSetName"] as? String ?: return null,
                directory = File(it["directory"] as? String ?: return null),
            )
        } ?: return null
        return ComposeResourceInfo(
            generatorClasspath = classpath,
            packageName = composeInfo["packageName"] as? String ?: return null,
            publicResClass = composeInfo["publicResClass"] as? Boolean ?: return null,
            resourceDirectories = resourceDirectories,
            assetRelativePath = composeInfo["assetRelativePath"] as? String ?: return null,
            resClassName = composeInfo["resClassName"] as? String ?: "Res",
            generateResourceContentHash = composeInfo["generateResourceContentHash"] as? Boolean ?: false,
            usesLegacyGenerator = composeInfo["usesLegacyGenerator"] as? Boolean ?: false,
            supportStatus = (composeInfo["supportStatus"] as? String)
                ?.let(ComposeResourceSupportStatus::valueOf)
                ?: ComposeResourceSupportStatus.Supported,
            unsupportedReason = composeInfo["unsupportedReason"] as? String
        )
    }

    companion object {

        fun getJsonGenerator() : JsonGenerator {
            val fileConverter = object : JsonGenerator.Converter {
                override fun handles(p0: Class<*>?): Boolean {
                    return p0 == File::class.java
                }

                override fun convert(p0: Any?, p1: String?): Any? {
                    return (p0 as File).path
                }
            }
            // JsonGenerator will create "valid", "res" which are getter property, so we manually handle it there
            val libraryConverter = object : JsonGenerator.Converter {
                override fun handles(p0: Class<*>?): Boolean {
                    return p0 == LibraryDependency::class.java
                }

                override fun convert(p0: Any?, p1: String?): Any? {
                    val libraryDependency = p0 as? LibraryDependency ?: return "null"
                    val result = mutableMapOf<String, Any>()
                    result["name"] = libraryDependency.name
                    result["file"] = libraryDependency.file
                    result["lastModifiedTime"] = libraryDependency.lastModifiedTime
                    result["crc32"] = libraryDependency.crc32
                    return result
                }
            }
            val buildPathConverter = object : JsonGenerator.Converter {
                override fun handles(p0: Class<*>?): Boolean {
                    return p0 == ModuleBuildPathInfo::class.java
                }

                override fun convert(p0: Any?, p1: String?): Any? {
                    val moduleBuildPathInfo = p0 as? ModuleBuildPathInfo ?: return null
                    val result = mutableMapOf<String, Any>()
                    result["projectRootDir"] = moduleBuildPathInfo.projectRootDir
                    result["moduleRootDir"] = moduleBuildPathInfo.moduleRootDir
                    result["buildVariant"] = moduleBuildPathInfo.buildVariant
                    result["buildDirRelativePath"] = moduleBuildPathInfo.buildDirRelativePath
                    return result
                }
            }
            val generator = JsonGenerator.Options()
                .excludeFieldsByName("contentHash", "originalClassName")
                .excludeNulls()
                .addConverter(fileConverter)
                .addConverter(libraryConverter)
                .addConverter(buildPathConverter)
                .build()
            return generator
        }
    }
}
