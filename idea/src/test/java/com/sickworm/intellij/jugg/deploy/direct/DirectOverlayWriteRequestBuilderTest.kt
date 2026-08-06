package com.sickworm.intellij.jugg.deploy.direct

import com.sickworm.intellij.jugg.deploy.api.DexComparator
import com.sickworm.intellij.jugg.deploy.api.DexClass
import com.sickworm.intellij.jugg.deploy.run.IAsDeployerCompat
import com.sickworm.intellij.jugg.deploy.run.JuggDeploymentCacheEntry
import com.sickworm.intellij.jugg.deploy.run.JuggOverlayFile
import com.sickworm.intellij.jugg.deploy.run.JuggOverlayId
import com.sickworm.intellij.jugg.deploy.run.JuggOverlayUpdate
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever

class DirectOverlayWriteRequestBuilderTest {

    @Test
    fun `build should drop duplicate overlay paths before writing zip`() {
        val compat = Mockito.mock(IAsDeployerCompat::class.java)
        val baseId = JuggOverlayId(raw = Any(), sha = "base", isBaseInstall = false)
        val nextId = JuggOverlayId(raw = Any(), sha = "next", isBaseInstall = false)
        whenever(compat.buildOverlayId(eq(baseId), any())).thenReturn(nextId)
        val clazz = DexClass("com/example/Foo", 1L, byteArrayOf(1), null)
        val overlayUpdate = JuggOverlayUpdate(
            cachedDump = JuggDeploymentCacheEntry(raw = Any(), apks = emptyList(), overlayId = baseId),
            dexOverlays = DexComparator.ChangedClasses(listOf(clazz), listOf(clazz)),
            fileOverlays = emptyMap(),
            raw = Any(),
        )

        val prepared = DirectOverlayWriteRequestBuilder().build(
            packageName = "com.example",
            overlayUpdate = overlayUpdate,
            asDeployerCompat = compat,
            isFullResourcePush = false,
        )

        assertEquals(listOf("com/example/Foo.dex"), prepared.request.files.map { it.path })
        @Suppress("UNCHECKED_CAST")
        val overlayFiles = Mockito.mockingDetails(compat).invocations.single {
            it.method.name == "buildOverlayId"
        }.arguments[1] as List<JuggOverlayFile>
        assertEquals(listOf("com/example/Foo.dex"), overlayFiles.map { it.path })
    }
}
