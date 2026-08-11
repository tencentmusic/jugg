package com.sickworm.intellij.jugg.deploy

import org.junit.Assert
import org.junit.Test
import java.io.File

class LastChangedDeployRegistryTest {

    @Test
    fun testEmptyDeploymentDoesNotOverwriteLastChangedDeployment() {
        val registry = LastChangedDeployRegistry()
        registry.record(
            projectDir = "/fake/project",
            files = listOf(File("/fake/project/module/Foo.kt")),
            deployedAtMillis = 100L,
        )

        registry.record(
            projectDir = "/fake/project",
            files = emptyList(),
            deployedAtMillis = 200L,
        )

        Assert.assertEquals(
            LastChangedDeploySnapshot(
                deployedAtMillis = 100L,
                files = listOf(File("/fake/project/module/Foo.kt")),
            ),
            registry.get("/fake/project/"),
        )
    }
}
