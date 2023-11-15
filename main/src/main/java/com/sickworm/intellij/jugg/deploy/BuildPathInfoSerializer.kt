package com.sickworm.intellij.jugg.deploy

import com.google.gson.Gson
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.compiler.ModuleInfo
import java.io.File

class BuildPathInfoSerializer(private val dataFile: File, private val logger: Logger) {

    companion object {
        private const val VERSION: Int = 1
    }

    @Synchronized
    fun save(modules: Map<String, ModuleInfo>) {
        dataFile.parentFile?.mkdirs()
        val data = BuildPathInfo(VERSION, modules.mapValues { (_, moduleInfo) ->
            BuildPathInfoArgs(moduleInfo.buildPathInfo)
        })
        dataFile.writeText(Gson().toJson(data))
    }

    @Synchronized
    fun load(): Map<String, ModuleBuildPathInfo>? {
        if (!dataFile.exists()) {
            return null
        }
        return try {
            val result = Gson().fromJson(dataFile.readText(), BuildPathInfo::class.java)
            if (result.version != VERSION) {
                logger.warn("module build path info version not match")
                return null
            }
            return result.modulePathInfos.mapValues { (_, args) ->
                BuildPathInfoArgs.toModuleBuildPathInfo(args)
            }
        } catch (e: Exception) {
            logger.warn("Failed to load module build path info from ${dataFile.absolutePath}", e)
            null
        }
    }

    class BuildPathInfo(
        val version: Int,
        val modulePathInfos: Map<String, BuildPathInfoArgs>
    )

    class BuildPathInfoArgs(
        val projectRootDir: String,
        val moduleRootDir: String,
        val buildVariant: String,
    ) {
        constructor(info: ModuleBuildPathInfo) : this(
            info.projectRootDir.absolutePath,
            info.moduleRootDir.absolutePath,
            info.buildVariant,
        )

        companion object {

            fun toModuleBuildPathInfo(args: BuildPathInfoArgs): ModuleBuildPathInfo {
                return ModuleBuildPathInfo(
                    File(args.projectRootDir),
                    File(args.moduleRootDir),
                    args.buildVariant,
                )
            }
        }
    }
}

