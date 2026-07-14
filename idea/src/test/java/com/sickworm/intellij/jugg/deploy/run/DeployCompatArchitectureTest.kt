package com.sickworm.intellij.jugg.deploy.run

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards deploy compatibility boundaries that Gradle module dependencies cannot fully express.
 */
class DeployCompatArchitectureTest {

    @Test
    fun `compat modules only use versioned stub api jars`() {
        val deployCompatDir = findRepoFile("deploy_compat")
        val platformJars = deployCompatDir.walkTopDown()
            .filter {
                val path = it.relativeTo(deployCompatDir).invariantSeparatorsPath
                it.isFile && it.extension == "jar" && !path.startsWith("stub_api/") && "/build/" !in "/$path"
            }
            .toList()
        assertTrue("deploy_compat should not contain Android Studio JARs: $platformJars", platformJars.isEmpty())

        deployCompatDir.listFiles()
            .orEmpty()
            .filter { it.isDirectory && it.name.startsWith("v_") }
            .forEach { moduleDir ->
                val buildFile = File(moduleDir, "build.gradle")
                assertFalse(
                    "${moduleDir.name} should resolve APIs through getCompatApiFiles",
                    buildFile.readText().contains("fileTree(dir: 'libs'"),
                )
            }
    }

    @Test
    fun `deploy main path does not expose legacy deployer runtime types`() {
        val paths = listOf(
            "deploy_compat/interface/src/main/java/com/sickworm/intellij/jugg/deploy/run/IAsDeployerCompat.kt",
            "deploy_compat/interface/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggOverlayUpdate.kt",
            "idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/IJuggDeployerDeploymentService.kt",
            "idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeploymentService.kt",
            "idea/src/main/java/com/sickworm/intellij/jugg/deploy/IdeaDeviceAdb.kt",
            "idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployerHelper.kt",
            "idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/applychanges/JuggDeployTask.kt",
            "idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/applychanges/JuggDeployer.kt",
        )
        val legacyTypes = listOf(
            "AdbClient",
            "Installer",
            "InstallOptions",
            "UIService",
            "OverlayId",
            "DeployerException",
            "DeploymentCacheDatabase",
            "ApplicationDumper",
        )

        paths.forEach { path ->
            val file = findRepoFile(path)
            val text = file.readText()
            val signatureText = text.lineSequence()
                .filterNot { it.contains('"') }
                .filterNot { it.trimStart().startsWith("*") }
                .joinToString("\n")
            legacyTypes.forEach { legacyType ->
                val importReference = Regex("""import\s+com\.android\.tools\.deployer\.(\*|$legacyType)\b""")
                    .containsMatchIn(text)
                val signatureReference = Regex("""(^|[\s:<,(])$legacyType($|[\s>),?=])""")
                    .containsMatchIn(signatureText)
                assertFalse(
                    "$path should route legacy deployer $legacyType access through deploy_compat",
                    importReference || signatureReference,
                )
            }
        }

        assertFalse(
            "JuggDeployTask should route StudioFlags access through deploy_compat",
            findRepoFile(
                "idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/applychanges/JuggDeployTask.kt",
            ).readText().contains("StudioFlags"),
        )
    }

    @Test
    fun `deployer compat boundary does not expose device adb or installer construction methods`() {
        val compatInterface = findRepoFile(
            "deploy_compat/interface/src/main/java/com/sickworm/intellij/jugg/deploy/run/IAsDeployerCompat.kt",
        ).readText()
        val forbiddenApiNames = listOf(
            "getInstaller(",
            "createInstallOptions(",
            "getPids(",
            "getArch(",
            "shell(",
            "push(",
            "uninstall(",
        )

        forbiddenApiNames.forEach { apiName ->
            assertFalse(
                "IAsDeployerCompat should not expose $apiName",
                compatInterface.contains(apiName),
            )
        }
    }

    @Test
    fun `deployment cache store stays independent from studio deployer runtime`() {
        assertFalse(
            "Deployment cache store should use local source implementation instead of reflection",
            findOptionalRepoFile(
                "idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/ReflectiveJuggDeploymentCacheStore.kt",
            )?.exists() == true,
        )

        val storeText = findRepoFile(
            "main/src/main/java/com/sickworm/intellij/jugg/deploy/cache/JuggDeploymentCacheStore.kt",
        ).readText()
        listOf(
            "Class.forName",
            "getMethod(",
            "getDeclaredConstructor",
            "com.android.tools.deployer",
            "IAsDeployerCompat",
            "JuggDeploymentCacheEntry",
        ).forEach { forbiddenReference ->
            assertFalse(
                "JuggDeploymentCacheStore should not depend on $forbiddenReference",
                storeText.contains(forbiddenReference),
            )
        }
    }

    private fun findRepoFile(path: String): File {
        return findOptionalRepoFile(path) ?: throw IllegalStateException("Cannot find $path")
    }

    private fun findOptionalRepoFile(path: String): File? {
        var current = File("").absoluteFile
        while (true) {
            val candidate = File(current, path)
            if (candidate.exists()) {
                return candidate
            }
            current = current.parentFile ?: break
        }
        return null
    }
}
