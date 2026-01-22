package com.sickworm.intellij.jugg.compiler.overlay

import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.mock.GradleBuildHelper
import com.sickworm.intellij.jugg.mock.SimpleCompileContext
import com.sickworm.intellij.jugg.mock.TestGlobal
import java.io.File

open class ResourceCompileAabResGuardTest : ResourceCompileTest() {

    private var assembleOnce = false

    override fun build() {
        if (!assembleOnce) {
            GradleBuildHelper.appBundleReleaseToApk()
        }
        assembleOnce = true
        TestGlobal.buildDir.deleteRecursively()
    }

    override fun getContext(): SimpleCompileContext {
        val origin = super.getContext()
        return origin.copy(
            apkInfos = listOf(
                ApkInfo(
                    File(TestGlobal.apkFile, "../../../bundle/release/duplicated-app.apk").normalize(),
                    TestGlobal.packageName,
                )
            ),
            modules = origin.modules.mapValues { (_, module) ->
                module.copy(
                    buildVariant = "release",
                    buildPathInfo = module.buildPathInfo.copy(buildVariant = "release")
                )
            }
        )
    }

    override fun compileResourceOverlay() {
        super.compileResourceOverlay()
    }

    override fun compileStyleableLayout() {
        super.compileStyleableLayout()
    }
}