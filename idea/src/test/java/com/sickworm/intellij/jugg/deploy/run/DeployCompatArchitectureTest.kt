package com.sickworm.intellij.jugg.deploy.run

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.jar.JarFile

/**
 * Guards deploy compatibility boundaries that Gradle module dependencies cannot fully express.
 */
class DeployCompatArchitectureTest {

    @Test
    fun `shared apply changes api does not expose Android runtime types`() {
        val paths = listOf(
            "deploy_compat/interface/src/main/java/com/sickworm/intellij/jugg/deploy/api/DeployApiTypes.kt",
            "deploy_compat/interface/src/main/java/com/sickworm/intellij/jugg/deploy/run/IApplyChangesExecutor.kt",
            "deploy_compat/interface/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployCompatTypes.kt",
            "deploy_compat/interface/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggOverlayUpdate.kt",
        )
        val forbiddenPackages = listOf(
            "com.android.ddmlib",
            "com.android.tools.deploy.proto",
            "com.android.tools.deployer",
            "com.android.tools.idea.protobuf",
            "com.android.utils",
        )

        paths.forEach { path ->
            val text = findRepoFile(path).readText()
            forbiddenPackages.forEach { forbiddenPackage ->
                assertFalse("$path should not expose $forbiddenPackage", text.contains(forbiddenPackage))
            }
        }

        assertFalse(
            "deploy interface should not contain shared Android runtime converters",
            findRepoFile("deploy_compat/interface/src/main/java/com/sickworm/intellij/jugg/deploy/run")
                .resolve("StudioDeployApiConverters.kt").exists(),
        )

        val interfaceJar = findRepoFile("deploy_compat/interface/build/libs").listFiles()
            .orEmpty().filter { it.extension == "jar" }.maxByOrNull(File::lastModified)
            ?: error("deploy interface jar was not built")
        JarFile(interfaceJar).use { jar ->
            val entries = jar.entries().asSequence().filter { entry ->
                !entry.isDirectory && entry.name.endsWith(".class")
            }
            entries.forEach { entry ->
                val bytecode = jar.getInputStream(entry).readBytes().toString(Charsets.ISO_8859_1)
                if (!isIdeOnlyAndroidRuntimeEntry(entry.name)) {
                    assertFalse("${entry.name} should not link Android runtime classes", bytecode.contains("com/android/"))
                }
            }
        }

        val ideaBoundary = findRepoFile(
            "deploy_compat/interface/src/main/java/com/sickworm/intellij/jugg/deploy/run/IAsDeployerCompat.kt",
        ).readText()
        listOf(
            "com.android.ddmlib",
            "com.android.tools.deploy.proto",
            "com.android.tools.deployer.model",
            "com.android.tools.idea.protobuf",
            "com.android.utils",
        ).forEach { forbiddenPackage ->
            assertFalse("IAsDeployerCompat should not expose $forbiddenPackage", ideaBoundary.contains(forbiddenPackage))
        }
    }

    @Test
    fun `IDE only Android runtime allowlist rejects same prefix classes`() {
        assertTrue(isIdeOnlyAndroidRuntimeEntry("${IDE_ONLY_ANDROID_RUNTIME_CLASSES.first()}.class"))
        assertTrue(isIdeOnlyAndroidRuntimeEntry("${IDE_ONLY_ANDROID_RUNTIME_CLASSES.first()}\$Companion.class"))
        assertFalse(isIdeOnlyAndroidRuntimeEntry("${IDE_ONLY_ANDROID_RUNTIME_CLASSES.first()}Converter.class"))
    }

    @Test
    fun `compat modules only use versioned stub api jars`() {
        val deployCompatDir = findRepoFile("deploy_compat")
        val compatModuleDirs = deployCompatDir.listFiles()
            .orEmpty()
            .filter { it.isDirectory && it.name.startsWith("v_") }
        val platformJars = compatModuleDirs.flatMap { moduleDir ->
            moduleDir.walkTopDown()
                .filter {
                    val path = it.relativeTo(moduleDir).invariantSeparatorsPath
                    it.isFile && it.extension == "jar" && "/build/" !in "/$path"
                }
                .toList()
        }
        assertTrue("deploy_compat should not contain Android Studio JARs: $platformJars", platformJars.isEmpty())

        compatModuleDirs.forEach { moduleDir ->
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
            "main/src/main/java/com/sickworm/intellij/jugg/deploy/run/IJuggDeployerDeploymentService.kt",
            "main/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeploymentService.kt",
            "idea/src/main/java/com/sickworm/intellij/jugg/deploy/IdeaDeviceAdb.kt",
            "idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployerHelper.kt",
            "main/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployOrchestrator.kt",
            "main/src/main/java/com/sickworm/intellij/jugg/deploy/run/applychanges/JuggDeployTask.kt",
            "main/src/main/java/com/sickworm/intellij/jugg/deploy/run/applychanges/JuggDeployer.kt",
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
                "main/src/main/java/com/sickworm/intellij/jugg/deploy/run/applychanges/JuggDeployTask.kt",
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
    fun `IDE compat facade keeps best effort fallback for every API`() {
        val facade = findRepoFile(
            "idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/AsDeployerCompat.kt",
        ).readText()

        assertFalse("AsDeployerCompat should not bypass compatibility fallback", facade.contains("invokePriority"))
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

    @Test
    fun `shared deploy lifecycle keeps host dependencies behind environments`() {
        val sharedFiles = listOf(
            "main/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployOrchestrator.kt",
            "main/src/main/java/com/sickworm/intellij/jugg/deploy/run/LaunchContextFactory.kt",
            "main/src/main/java/com/sickworm/intellij/jugg/deploy/run/flow/DeployStateRecover.kt",
            "main/src/main/java/com/sickworm/intellij/jugg/deploy/run/flow/DeployRetryHandler.kt",
        )
        val forbiddenReferences = listOf(
            "import com.intellij.openapi.project.Project",
            "import com.sickworm.intellij.jugg.deploy.IdeaDeviceAdb",
            "import com.sickworm.intellij.jugg.deploy.run.AsDeployerCompat",
            "import com.android.tools.idea.run.IdeService",
            "import com.intellij.openapi.progress.ProgressIndicator",
        )
        sharedFiles.forEach { path ->
            val source = findRepoFile(path).readText()
            forbiddenReferences.forEach { forbidden ->
                assertFalse("$path should keep $forbidden behind IDeployHost", source.contains(forbidden))
            }
        }

        val compatInterface = findRepoFile(
            "deploy_compat/interface/src/main/java/com/sickworm/intellij/jugg/deploy/run/IAsDeployerCompat.kt",
        ).readText()
        assertTrue(compatInterface.contains("interface IAsDeployerCompat : IApplyChangesExecutor"))

        val standaloneEnvironment = findRepoFile(
            "cmd_line/src/main/java/com/sickworm/intellij/jugg/cmdline/standalone/StandaloneDeployEnvironment.kt",
        ).readText()
        assertTrue(standaloneEnvironment.contains("StandaloneApplyChangesExecutor"))
        assertTrue(standaloneEnvironment.contains("StandaloneDeviceManager"))
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

    private fun isIdeOnlyAndroidRuntimeEntry(entryName: String): Boolean {
        return IDE_ONLY_ANDROID_RUNTIME_CLASSES.any { className ->
            entryName == "$className.class" ||
                entryName.startsWith("$className\$") && entryName.endsWith(".class")
        }
    }

    private companion object {
        val IDE_ONLY_ANDROID_RUNTIME_CLASSES = listOf(
            "com/sickworm/intellij/jugg/deploy/run/AndroidDebugClientReadyWaiter",
            "com/sickworm/intellij/jugg/deploy/run/AndroidStudioDebuggerAttachStarter",
            "com/sickworm/intellij/jugg/deploy/run/JavaDebuggerSessionStarter",
        )
    }
}
