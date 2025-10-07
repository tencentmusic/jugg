package com.sickworm.intellij.jugg.mock

import com.google.gson.JsonSyntaxException
import com.sickworm.intellij.jugg.ide.logic.IdeaPlatformApi
import com.sickworm.intellij.jugg.platform.PlatformApi
import java.io.File

object TestGlobal {

    val projectInfo = try {
        PlatformApi.impl = IdeaPlatformApi()
        val projectInfoFromEnv = System.getenv("JUGG_PROJECT_INFO_PATH")
        val json = if (projectInfoFromEnv != null) {
            File(projectInfoFromEnv).readText()
        } else {
            ProjectInfo.DEMO_JSON
        }
        ProjectInfo.parseJson(json)
    } catch (e: JsonSyntaxException) {
        throw IllegalArgumentException("parse project info failed", e)
    }
}