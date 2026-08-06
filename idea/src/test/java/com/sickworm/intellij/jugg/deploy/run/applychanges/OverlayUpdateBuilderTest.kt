package com.sickworm.intellij.jugg.deploy.run.applychanges

import com.android.tools.deployer.DeploymentCacheDatabase
import com.android.tools.deployer.OverlayId
import com.sickworm.intellij.jugg.deploy.api.Apk
import com.sickworm.intellij.jugg.deploy.api.ApkEntry
import com.sickworm.intellij.jugg.deploy.api.ByteString
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.deploy.data.ParsedDex
import com.sickworm.intellij.jugg.deploy.run.DeployItem
import com.sickworm.intellij.jugg.deploy.run.IAsDeployerCompat
import com.sickworm.intellij.jugg.deploy.run.JuggDeploymentCacheEntry
import com.sickworm.intellij.jugg.deploy.run.JuggDeployData
import com.sickworm.intellij.jugg.deploy.run.JuggOverlayUpdate
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any

class OverlayUpdateBuilderTest {

    @Test
    fun `build should keep first overlay when qualified paths duplicate`() {
        var fileOverlays = emptyMap<ApkEntry, ByteString>()
        val compat = Mockito.mock(IAsDeployerCompat::class.java)
        Mockito.doAnswer { invocation ->
            fileOverlays = invocation.getArgument(2)
            JuggOverlayUpdate(invocation.getArgument(0), invocation.getArgument(1), fileOverlays, Any())
        }.`when`(compat).createOverlayUpdate(any(), any(), any())
        val data = deployData(
            DeployItem(
                name = "resources.arsc",
                type = CompileOutput.Type.Res,
                checksum = 1L,
                content = "changed".toByteArray(),
                apkPath = "/base.apk",
            ),
            DeployItem(
                name = "resources.arsc",
                type = CompileOutput.Type.Res,
                checksum = 2L,
                content = "base".toByteArray(),
                apkPath = "/base.apk",
            ),
        )

        OverlayUpdateBuilder(compat).build(cacheEntry(), data)

        val matchingEntries = fileOverlays.entries.filter {
            it.key.qualifiedPath == "base.apk/resources.arsc"
        }
        assertEquals(1, matchingEntries.size)
        assertEquals("changed", matchingEntries.single().value.toStringUtf8())
    }

    private fun deployData(vararg overlays: DeployItem): JuggDeployData {
        return JuggDeployData(
            apks = emptyList(),
            newClasses = emptyList(),
            hotFixModifiedClasses = emptyList(),
            hotReloadModifiedClasses = emptyList(),
            effectedClassNodes = emptyList(),
            overlays = overlays.toList(),
            parsedDex = ParsedDex.EMPTY,
            isFullRes = true,
            isWarmUp = false,
            isPushOverlayOnly = true,
        )
    }

    private fun cacheEntry(): JuggDeploymentCacheEntry {
        val apk = apk("base.apk", "/base.apk", "com.example.app")
        return JuggDeploymentCacheEntry(
            raw = Any(),
            apks = listOf(apk),
            overlayId = com.sickworm.intellij.jugg.deploy.run.JuggOverlayId(Any(), "base", true),
        )
    }

    private fun OverlayId.toJuggOverlayId() =
        com.sickworm.intellij.jugg.deploy.run.JuggOverlayId(raw = this, sha = sha, isBaseInstall = isBaseInstall)

    private fun apk(name: String, path: String, packageName: String): Apk {
        return Apk(name, "checksum", path, packageName, emptyList(), emptyList(), emptyList(), emptyMap())
    }
}
