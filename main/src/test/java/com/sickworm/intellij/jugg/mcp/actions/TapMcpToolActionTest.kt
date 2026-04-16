package com.sickworm.intellij.jugg.mcp.actions

import com.android.ddmlib.IDevice
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.compiler.ForceGradleCompileHelper
import com.sickworm.intellij.jugg.compiler.GradleCompileExecutionResult
import com.sickworm.intellij.jugg.compiler.RemoteSshInfoResult
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.ide.bean.ConfirmResult
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.ide.logic.IJuggConfigurationRunner
import com.sickworm.intellij.jugg.ide.logic.JuggRunInvocationResult
import com.sickworm.intellij.jugg.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.mcp.McpErrorCode
import com.sickworm.intellij.jugg.mcp.McpToolStatus
import com.sickworm.intellij.jugg.mcp.viewhierarchy.FindAndTapResult
import com.sickworm.intellij.jugg.mcp.viewhierarchy.MatchCandidate
import com.sickworm.intellij.jugg.mcp.viewhierarchy.MatchedElementData
import com.sickworm.intellij.jugg.mcp.viewhierarchy.ViewHierarchyClient
import com.sickworm.intellij.jugg.platform.IPlatformApi
import com.sickworm.intellij.jugg.platform.PlatformApi
import com.sickworm.intellij.jugg.project.dependency.DependencyDiffResult
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import java.io.File

/**
 * TapMcpToolActionTest validates tap/longPress/swipe actions and element-mode server behavior.
 */
class TapMcpToolActionTest {
    @Before
    fun setUpGuard() {
        McpAppReadyGuard.resetForTest()
    }

    @After
    fun tearDownGuard() {
        McpAppReadyGuard.resetForTest()
    }

    @Test
    fun testTapShouldWaitTopActivityOnResumeStableBeforeTap() {
        val dumpsysResumed = "topResumedActivity=ActivityRecord{123 com.example.app/.MainActivity t12}"
        val (action, adb) = setup(
            commandBehavior = { cmd, _ ->
                if (cmd == "dumpsys activity activities") {
                    return@setup dumpsysResumed
                }
                null
            }
        )
        val result = action.execute(
            mapOf("projectDir" to "/tmp/test", "x" to 540, "y" to 960),
            runtime()
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue(adb.executedCommands.contains("input tap 540 960"))
        Assert.assertEquals(2, adb.executedCommands.count { it == "dumpsys activity activities" })
    }

    @Test
    fun testTapShouldFailWhenTopActivityOnResumeIsNotStable() {
        val dumpsysOutputs = listOf(
            "topResumedActivity=ActivityRecord{123 com.example.app/.MainActivity t12}",
            "mFocusedActivity: ActivityRecord{456 com.example.app/.MainActivity t12 state=PAUSED}",
            "topResumedActivity=ActivityRecord{789 com.example.app/.OtherActivity t13}",
        )
        var dumpsysIndex = 0
        val (action, adb) = setup(
            commandBehavior = { cmd, _ ->
                if (cmd == "dumpsys activity activities") {
                    val current = dumpsysOutputs[dumpsysIndex % dumpsysOutputs.size]
                    dumpsysIndex += 1
                    return@setup current
                }
                if (cmd.startsWith("input tap ")) {
                    throw RuntimeException("transient tap error")
                }
                null
            }
        )

        val result = action.execute(
            mapOf("projectDir" to "/tmp/test", "x" to 10, "y" to 20),
            runtime()
        )
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertEquals(McpErrorCode.INTERNAL_ERROR, result.errorCode)
        Assert.assertTrue(result.message.contains("currently not stable"))
        Assert.assertTrue(adb.executedCommands.any { it.startsWith("input tap ") })
    }

    @Test
    fun testDefaultTapActionCoordinateMode() {
        val (action, adb) = setup()
        val result = action.execute(
            mapOf("projectDir" to "/tmp/test", "x" to 540, "y" to 960),
            runtime()
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        Assert.assertEquals("tap", data["action"])
        Assert.assertEquals("coordinate", data["mode"])
        Assert.assertEquals(540, data["x"])
        Assert.assertEquals(960, data["y"])
        Assert.assertTrue(adb.executedCommands.contains("input tap 540 960"))
    }

    @Test
    fun testTapPercentMode() {
        val (action, adb) = setup(
            shellOutputs = mapOf(
                "wm size" to "Physical size: 1080x2400",
            )
        )
        val result = action.execute(
            mapOf("projectDir" to "/tmp/test", "xPercent" to 50.0, "yPercent" to 50.0),
            runtime()
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        Assert.assertEquals("tap", data["action"])
        Assert.assertEquals("percent", data["mode"])
        Assert.assertEquals(540, data["x"])
        Assert.assertEquals(1200, data["y"])
        Assert.assertEquals(1080, data["screenWidth"])
        Assert.assertEquals(2400, data["screenHeight"])
        Assert.assertTrue(adb.executedCommands.contains("input tap 540 1200"))
    }

    @Test
    fun testTapPercentModeShouldClampToScreenBounds() {
        val (action, adb) = setup(
            shellOutputs = mapOf(
                "wm size" to "Physical size: 1080x2400",
            )
        )
        val result = action.execute(
            mapOf("projectDir" to "/tmp/test", "xPercent" to 100.0, "yPercent" to 100.0),
            runtime()
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        Assert.assertEquals(1079, data["x"])
        Assert.assertEquals(2399, data["y"])
        Assert.assertTrue(adb.executedCommands.contains("input tap 1079 2399"))
    }

    @Test
    fun testTapPercentModeSupportsWmSizeWithSpaces() {
        val (action, adb) = setup(
            shellOutputs = mapOf(
                "wm size" to "Physical size: 1080 x 2400",
            )
        )
        val result = action.execute(
            mapOf("projectDir" to "/tmp/test", "xPercent" to 50.0, "yPercent" to 50.0),
            runtime()
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue(adb.executedCommands.contains("input tap 540 1200"))
    }

    @Test
    fun testSwipeCoordinateMode() {
        val (action, adb) = setup()
        val result = action.execute(
            mapOf(
                "projectDir" to "/tmp/test",
                "action" to "swipe",
                "x" to 540,
                "y" to 1800,
                "endX" to 540,
                "endY" to 400,
                "duration" to 300,
            ),
            runtime(),
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        Assert.assertEquals("swipe", data["action"])
        Assert.assertEquals("coordinate", data["mode"])
        Assert.assertEquals(540, data["x"])
        Assert.assertEquals(1800, data["y"])
        Assert.assertEquals(540, data["endX"])
        Assert.assertEquals(400, data["endY"])
        Assert.assertEquals(300, data["duration"])
        Assert.assertTrue(adb.executedCommands.contains("input swipe 540 1800 540 400 300"))
    }

    @Test
    fun testSwipePercentMode() {
        val (action, adb) = setup(
            shellOutputs = mapOf(
                "wm size" to "Physical size: 1080x2400",
            )
        )
        val result = action.execute(
            mapOf(
                "projectDir" to "/tmp/test",
                "action" to "swipe",
                "xPercent" to 50.0,
                "yPercent" to 80.0,
                "endXPercent" to 50.0,
                "endYPercent" to 20.0,
            ),
            runtime(),
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        Assert.assertEquals("swipe", data["action"])
        Assert.assertEquals("percent", data["mode"])
        Assert.assertEquals(540, data["x"])
        Assert.assertEquals(1920, data["y"])
        Assert.assertEquals(540, data["endX"])
        Assert.assertEquals(480, data["endY"])
        Assert.assertTrue(adb.executedCommands.contains("input swipe 540 1920 540 480 300"))
    }

    @Test
    fun testSwipeWithoutEndCoordinates() {
        val (action, _) = setup()
        val result = action.execute(
            mapOf(
                "projectDir" to "/tmp/test",
                "action" to "swipe",
                "x" to 10,
                "y" to 20,
            ),
            runtime(),
        )
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertEquals(McpErrorCode.INVALID_PARAMS, result.errorCode)
        Assert.assertTrue(result.message.contains("requires both start and end"))
    }

    @Test
    fun testSwipeElementModeReturnsError() {
        val (action, _) = setup()
        val result = action.execute(
            mapOf(
                "projectDir" to "/tmp/test",
                "action" to "swipe",
                "text" to "Login",
            ),
            runtime(),
        )
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertEquals(McpErrorCode.INVALID_PARAMS, result.errorCode)
        Assert.assertTrue(result.message.contains("does not support element mode"))
    }

    @Test
    fun testLongPressCoordinateModeDefaultDuration() {
        val (action, adb) = setup()
        val result = action.execute(
            mapOf(
                "projectDir" to "/tmp/test",
                "action" to "long-press",
                "x" to 540,
                "y" to 960,
            ),
            runtime(),
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        Assert.assertEquals("long-press", data["action"])
        Assert.assertEquals("coordinate", data["mode"])
        Assert.assertEquals(500, data["duration"])
        Assert.assertTrue(adb.executedCommands.contains("input swipe 540 960 540 960 500"))
    }

    @Test
    fun testLongPressPercentMode() {
        val (action, adb) = setup(
            shellOutputs = mapOf(
                "wm size" to "Physical size: 1080x2400",
            )
        )
        val result = action.execute(
            mapOf(
                "projectDir" to "/tmp/test",
                "action" to "long-press",
                "xPercent" to 50.0,
                "yPercent" to 50.0,
                "duration" to 800,
            ),
            runtime(),
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        Assert.assertEquals("long-press", data["action"])
        Assert.assertEquals("percent", data["mode"])
        Assert.assertEquals(800, data["duration"])
        Assert.assertTrue(adb.executedCommands.contains("input swipe 540 1200 540 1200 800"))
    }

    @Test
    fun testTapElementModeUsesServerSuccess() {
        val (action, adb) = setup(packageName = "com.example.app")
        Mockito.mockConstruction(ViewHierarchyClient::class.java) { mock, _ ->
            Mockito.`when`(mock.findAndTap("Login", null, null, null)).thenReturn(
                FindAndTapResult.Success(
                    x = 321,
                    y = 654,
                    matchedElement = matchedElementData(),
                    matchCount = 1,
                )
            )
        }.use {
            val result = action.execute(
                mapOf("projectDir" to "/tmp/test", "text" to "Login"),
                runtime()
            )
            Assert.assertEquals(McpToolStatus.OK, result.status)
            Assert.assertTrue(adb.executedCommands.none { it.startsWith("input ") })
            @Suppress("UNCHECKED_CAST")
            val data = result.data as Map<String, Any>
            Assert.assertEquals("tap", data["action"])
            Assert.assertEquals("element", data["mode"])
            Assert.assertEquals(321, data["x"])
            Assert.assertEquals(654, data["y"])
            Assert.assertEquals(1, data["matchCount"])
            @Suppress("UNCHECKED_CAST")
            val matched = data["matchedElement"] as Map<String, Any>
            Assert.assertEquals("Login", matched["text"])
            Assert.assertEquals("Button", matched["className"])
            Assert.assertEquals(321, matched["centerX"])
            Assert.assertEquals(654, matched["centerY"])
        }
    }

    @Test
    fun testLongPressElementModeUsesServerSuccess() {
        val (action, adb) = setup(packageName = "com.example.app")
        Mockito.mockConstruction(ViewHierarchyClient::class.java) { mock, _ ->
            Mockito.`when`(mock.findAndLongPress("Login", null, null, null, 900)).thenReturn(
                FindAndTapResult.Success(
                    x = 111,
                    y = 222,
                    matchedElement = matchedElementData(),
                    matchCount = 1,
                )
            )
        }.use {
            val result = action.execute(
                mapOf(
                    "projectDir" to "/tmp/test",
                    "action" to "long-press",
                    "text" to "Login",
                    "duration" to 900,
                ),
                runtime(),
            )
            Assert.assertEquals(McpToolStatus.OK, result.status)
            Assert.assertTrue(adb.executedCommands.none { it.startsWith("input ") })
            @Suppress("UNCHECKED_CAST")
            val data = result.data as Map<String, Any>
            Assert.assertEquals("long-press", data["action"])
            Assert.assertEquals("element", data["mode"])
            Assert.assertEquals(900, data["duration"])
            Assert.assertEquals(1, data["matchCount"])
            @Suppress("UNCHECKED_CAST")
            val matched = data["matchedElement"] as Map<String, Any>
            Assert.assertEquals("Login", matched["text"])
        }
    }

    @Test
    fun testTapElementModeUsesServerMultipleMatches() {
        val (action, adb) = setup(packageName = "com.example.app")
        val boundsA = listOf(1, 2, 101, 102)
        val boundsB = listOf(201, 202, 301, 302)
        Mockito.mockConstruction(ViewHierarchyClient::class.java) { mock, _ ->
            Mockito.`when`(mock.findAndTap("Item", null, null, null)).thenReturn(
                FindAndTapResult.Multiple(
                    matchCount = 2,
                    matches = listOf(
                        MatchCandidate("Item", "id/a", "", "android.widget.TextView", boundsA, 51, 52),
                        MatchCandidate("Item", "id/b", "", "android.widget.TextView", boundsB, 251, 252),
                    ),
                    message = "multiple",
                )
            )
        }.use {
            val result = action.execute(
                mapOf("projectDir" to "/tmp/test", "text" to "Item"),
                runtime()
            )
            Assert.assertEquals(McpToolStatus.ERROR, result.status)
            Assert.assertEquals(McpErrorCode.INVALID_PARAMS, result.errorCode)
            Assert.assertTrue(adb.executedCommands.none { it.startsWith("input ") })
            @Suppress("UNCHECKED_CAST")
            val data = result.data as Map<String, Any>
            Assert.assertEquals("tap", data["action"])
            Assert.assertEquals("element", data["mode"])
            Assert.assertEquals(2, data["matchCount"])
            @Suppress("UNCHECKED_CAST")
            val matches = data["matches"] as List<Map<String, Any>>
            Assert.assertEquals(2, matches.size)
            Assert.assertEquals(51, matches[0]["centerX"])
            Assert.assertEquals(251, matches[1]["centerX"])
        }
    }

    @Test
    fun testTapElementModeUsesServerNotFound() {
        val (action, adb) = setup(packageName = "com.example.app")
        Mockito.mockConstruction(ViewHierarchyClient::class.java) { mock, _ ->
            Mockito.`when`(mock.findAndTap("Missing", null, null, null)).thenReturn(
                FindAndTapResult.NotFound(
                    candidates = listOf(
                        MatchCandidate(
                            text = "Login",
                            resourceId = "com.example:id/login",
                            contentDesc = "",
                            className = "android.widget.Button",
                            bounds = null,
                            centerX = 10,
                            centerY = 20,
                        )
                    ),
                    message = "not found",
                )
            )
        }.use {
            val result = action.execute(
                mapOf("projectDir" to "/tmp/test", "text" to "Missing"),
                runtime()
            )
            Assert.assertEquals(McpToolStatus.ERROR, result.status)
            Assert.assertEquals(McpErrorCode.INTERNAL_ERROR, result.errorCode)
            Assert.assertTrue(result.message.contains("No matching UI element found"))
            Assert.assertTrue(result.message.contains("Login"))
            Assert.assertTrue(adb.executedCommands.none { it.startsWith("input ") })
        }
    }

    @Test
    fun testTapElementModeNotFoundByResourceIdUsesResourceIdCandidates() {
        val (action, _) = setup(packageName = "com.example.app")
        Mockito.mockConstruction(ViewHierarchyClient::class.java) { mock, _ ->
            Mockito.`when`(mock.findAndTap(null, "missing_id", null, null)).thenReturn(
                FindAndTapResult.NotFound(
                    candidates = listOf(
                        MatchCandidate(
                            text = "Login",
                            resourceId = "com.example:id/login",
                            contentDesc = "login button",
                            className = "android.widget.Button",
                            bounds = null,
                            centerX = 10,
                            centerY = 20,
                        ),
                        MatchCandidate(
                            text = "",
                            resourceId = "",
                            contentDesc = "",
                            className = "com.google.android.material.tabs.TabLayout\$TabView",
                            bounds = null,
                            centerX = 30,
                            centerY = 40,
                        ),
                    ),
                    message = "not found",
                )
            )
        }.use {
            val result = action.execute(
                mapOf("projectDir" to "/tmp/test", "resourceId" to "missing_id"),
                runtime(),
            )
            Assert.assertEquals(McpToolStatus.ERROR, result.status)
            Assert.assertTrue(result.message.contains("No matching UI element found"))
            Assert.assertTrue(result.message.contains("resource-id=\"com.example:id/login\""))
            Assert.assertFalse(result.message.contains("class=\"com.google.android.material.tabs.TabLayout\$TabView\""))
        }
    }

    @Test
    fun testTapElementModeServerUnavailableReturnsError() {
        val (action, _) = setup(packageName = "com.example.app")
        Mockito.mockConstruction(ViewHierarchyClient::class.java) { mock, _ ->
            Mockito.`when`(mock.findAndTap("Any", null, null, null)).thenReturn(null)
        }.use {
            val result = action.execute(
                mapOf("projectDir" to "/tmp/test", "text" to "Any"),
                runtime()
            )
            Assert.assertEquals(McpToolStatus.ERROR, result.status)
            Assert.assertEquals(McpErrorCode.INTERNAL_ERROR, result.errorCode)
            Assert.assertTrue(result.message.contains("server is unavailable"))
        }
    }

    @Test
    fun testLongPressHyphenActionCoordinateMode() {
        val (action, adb) = setup()
        val result = action.execute(
            mapOf(
                "projectDir" to "/tmp/test",
                "action" to "long-press",
                "x" to 540,
                "y" to 960,
            ),
            runtime(),
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        Assert.assertEquals("long-press", data["action"])
        Assert.assertEquals("coordinate", data["mode"])
        Assert.assertEquals(500, data["duration"])
        Assert.assertTrue(adb.executedCommands.contains("input swipe 540 960 540 960 500"))
    }

    @Test
    fun testLongPressHyphenActionElementMode() {
        val (action, adb) = setup(packageName = "com.example.app")
        Mockito.mockConstruction(ViewHierarchyClient::class.java) { mock, _ ->
            Mockito.`when`(mock.findAndLongPress("Login", null, null, null, 500)).thenReturn(
                FindAndTapResult.Success(
                    x = 111,
                    y = 222,
                    matchedElement = matchedElementData(),
                    matchCount = 1,
                )
            )
        }.use {
            val result = action.execute(
                mapOf(
                    "projectDir" to "/tmp/test",
                    "action" to "long-press",
                    "text" to "Login",
                ),
                runtime(),
            )
            Assert.assertEquals(McpToolStatus.OK, result.status)
            Assert.assertTrue(adb.executedCommands.none { it.startsWith("input ") })
            @Suppress("UNCHECKED_CAST")
            val data = result.data as Map<String, Any>
            Assert.assertEquals("long-press", data["action"])
            Assert.assertEquals("element", data["mode"])
        }
    }

    @Test
    fun testIdAliasForResourceId() {
        val (action, _) = setup(packageName = "com.example.app")
        Mockito.mockConstruction(ViewHierarchyClient::class.java) { mock, _ ->
            Mockito.`when`(mock.findAndTap(null, "btn_login", null, null)).thenReturn(
                FindAndTapResult.Success(
                    x = 321,
                    y = 654,
                    matchedElement = matchedElementData(),
                    matchCount = 1,
                )
            )
        }.use {
            val result = action.execute(
                mapOf("projectDir" to "/tmp/test", "id" to "btn_login"),
                runtime()
            )
            Assert.assertEquals(McpToolStatus.OK, result.status)
            @Suppress("UNCHECKED_CAST")
            val data = result.data as Map<String, Any>
            Assert.assertEquals("element", data["mode"])
        }
    }

    @Test
    fun testDescAliasForContentDesc() {
        val (action, _) = setup(packageName = "com.example.app")
        Mockito.mockConstruction(ViewHierarchyClient::class.java) { mock, _ ->
            Mockito.`when`(mock.findAndTap(null, null, "login button", null)).thenReturn(
                FindAndTapResult.Success(
                    x = 321,
                    y = 654,
                    matchedElement = matchedElementData(),
                    matchCount = 1,
                )
            )
        }.use {
            val result = action.execute(
                mapOf("projectDir" to "/tmp/test", "desc" to "login button"),
                runtime()
            )
            Assert.assertEquals(McpToolStatus.OK, result.status)
            @Suppress("UNCHECKED_CAST")
            val data = result.data as Map<String, Any>
            Assert.assertEquals("element", data["mode"])
        }
    }

    @Test
    fun testClassAliasForClassName() {
        val (action, _) = setup(packageName = "com.example.app")
        Mockito.mockConstruction(ViewHierarchyClient::class.java) { mock, _ ->
            Mockito.`when`(mock.findAndTap("Login", null, null, "Button")).thenReturn(
                FindAndTapResult.Success(
                    x = 321,
                    y = 654,
                    matchedElement = matchedElementData(),
                    matchCount = 1,
                )
            )
        }.use {
            val result = action.execute(
                mapOf("projectDir" to "/tmp/test", "text" to "Login", "class" to "Button"),
                runtime()
            )
            Assert.assertEquals(McpToolStatus.OK, result.status)
            @Suppress("UNCHECKED_CAST")
            val data = result.data as Map<String, Any>
            Assert.assertEquals("element", data["mode"])
        }
    }

    @Test
    fun testUnknownActionReturnsError() {
        val (action, _) = setup()
        val result = action.execute(
            mapOf("projectDir" to "/tmp/test", "action" to "fling", "x" to 1, "y" to 2),
            runtime(),
        )
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertEquals(McpErrorCode.INVALID_PARAMS, result.errorCode)
        Assert.assertTrue(result.message.contains("Unsupported action"))
    }

    @Test
    fun testTapPriorityCoordinateOverPercent() {
        val (action, _) = setup(
            shellOutputs = mapOf(
                "wm size" to "Physical size: 1080x2400",
            ),
            packageName = "com.example.app",
        )
        val result = action.execute(
            mapOf("projectDir" to "/tmp/test", "x" to 100, "y" to 200, "xPercent" to 50.0, "yPercent" to 50.0, "text" to "Login"),
            runtime()
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        Assert.assertEquals("coordinate", data["mode"])
        Assert.assertEquals(100, data["x"])
        Assert.assertEquals(200, data["y"])
    }

    @Test
    fun testTapNoDeviceReturnsNoDevice() {
        PlatformApi.impl = FakePlatformApi(emptyMap())
        val deployTargetManager = Mockito.mock(IDeployTargetManager::class.java)
        Mockito.`when`(deployTargetManager.getSelectedDevices()).thenReturn(emptyList())
        Mockito.`when`(deployTargetManager.getConnectedDevices()).thenReturn(emptyList())

        val action = TapMcpToolAction()
        val result = action.execute(
            mapOf("projectDir" to "/tmp/test", "x" to 100, "y" to 200),
            runtime(deployTargetManager)
        )
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertEquals(McpErrorCode.NO_DEVICE, result.errorCode)
    }

    @Test
    fun testTapNoParametersReturnsError() {
        val (action, _) = setup()
        val result = action.execute(
            mapOf("projectDir" to "/tmp/test"),
            runtime()
        )
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertEquals(McpErrorCode.INVALID_PARAMS, result.errorCode)
        Assert.assertTrue(result.message.contains("No valid tap mode"))
    }

    @Test
    fun testTapInvalidParamsShouldTakePriorityOverAppNotReady() {
        val (action, _) = setup()
        var readyChecks = 0
        val result = action.execute(
            mapOf(
                "projectDir" to "/tmp/test",
                "action" to "swipe",
                "x" to 10,
                "y" to 20,
            ),
            runtime(
                isAppReadyProvider = {
                    readyChecks += 1
                    false
                },
            ),
        )
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertEquals(McpErrorCode.INVALID_PARAMS, result.errorCode)
        Assert.assertTrue(result.message.contains("requires both start and end"))
        Assert.assertEquals(0, readyChecks)
    }

    @Test
    fun testTapRetriesAfterPreWaitWhenFirstAttemptFails() {
        McpAppReadyGuard.preTimeoutOverrideForTest = 10L
        McpAppReadyGuard.prePollIntervalOverrideForTest = 1L
        McpAppReadyGuard.preFailureRetryIntervalOverrideForTest = 0L
        var inputTapCount = 0
        val (action, adb) = setup(
            commandBehavior = { cmd, _ ->
                if (cmd.startsWith("input tap ")) {
                    inputTapCount++
                    if (inputTapCount == 1) {
                        throw RuntimeException("transient tap error")
                    }
                }
                null
            }
        )
        var readyChecks = 0
        val result = action.execute(
            mapOf("projectDir" to "/tmp/test", "x" to 100, "y" to 200),
            runtime(
                isAppReadyProvider = {
                    readyChecks++
                    readyChecks >= 2
                }
            ),
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertEquals(2, inputTapCount)
        Assert.assertEquals(2, adb.executedCommands.count { it == "input tap 100 200" })
    }

    private fun matchedElementData(): MatchedElementData {
        return MatchedElementData(
            text = "Login",
            className = "Button",
            resourceId = "login",
            contentDesc = "",
            bounds = listOf(1, 2, 3, 4),
            centerX = 321,
            centerY = 654,
        )
    }

    // --- Test helpers ---

    private fun setup(
        shellOutputs: Map<String, String> = emptyMap(),
        packageName: String? = null,
        commandBehavior: ((String, Int) -> String?)? = null,
    ): Pair<TapMcpToolAction, FakeDeviceAdb> {
        val device = Mockito.mock(IDevice::class.java)
        val adb = FakeDeviceAdb(shellOutputs, commandBehavior)
        PlatformApi.impl = FakePlatformApi(mapOf(device to adb))

        val deployTargetManager = Mockito.mock(IDeployTargetManager::class.java)
        Mockito.`when`(deployTargetManager.getSelectedDevices()).thenReturn(listOf(device))
        Mockito.`when`(deployTargetManager.getConnectedDevices()).thenReturn(listOf(device))
        if (packageName != null) {
            Mockito.`when`(deployTargetManager.getPackageName()).thenReturn(packageName)
        }
        currentDeployTargetManager = deployTargetManager
        return TapMcpToolAction() to adb
    }

    private var currentDeployTargetManager: IDeployTargetManager? = null

    private fun runtime(
        dtm: IDeployTargetManager? = null,
        isAppReadyProvider: () -> Boolean = { true },
    ): IMcpRuntime {
        val deployTargetManager = dtm ?: currentDeployTargetManager!!
        val project = Mockito.mock(Project::class.java)
        Mockito.`when`(project.basePath).thenReturn("/tmp/test")
        return object : IMcpRuntime {
            override val logger: com.intellij.openapi.diagnostic.Logger
                get() = com.intellij.openapi.diagnostic.Logger.getInstance("TestMcpRuntime")
            override val project: Project = project
            override val deployTargetManager: IDeployTargetManager = deployTargetManager
            override val forceGradleCompileHelper: ForceGradleCompileHelper = object : ForceGradleCompileHelper() {
                override fun executeGradleCompile(autoConfirm: Boolean, useCleanAndReinstall: Boolean) {
                    throw UnsupportedOperationException("not used")
                }

                override fun executeGradleCompileBlocking(
                    autoConfirm: Boolean,
                    useCleanAndReinstall: Boolean,
                ): GradleCompileExecutionResult {
                    throw UnsupportedOperationException("not used")
                }

                override fun resolveExecutionType(): String = "local"

                override fun requestRemoteSshInfo(requestedBy: String, reason: String): RemoteSshInfoResult {
                    throw UnsupportedOperationException("not used")
                }
            }
            override val juggConfigurationRunner: IJuggConfigurationRunner = object : IJuggConfigurationRunner {
                override val isCompiling: Boolean = false
                override fun runTask(options: JuggGradleCompileOptions, compileUiHandler: CompileUiHandler) =
                    throw UnsupportedOperationException("not used")

                override fun forceReInstallNextTime() {
                    throw UnsupportedOperationException("not used")
                }

                override fun runFirstConfiguration(isRpcMode: Boolean, isSkipDeploy: Boolean): JuggRunInvocationResult {
                    throw UnsupportedOperationException("not used")
                }
            }

            override fun isAppReadyDeploy(): Boolean {
                return isAppReadyProvider()
            }
        }
    }

    private fun jsonObject(raw: String): JsonObject {
        return JsonParser.parseString(raw).asJsonObject
    }

    private class FakeDeviceAdb(
        private val shellOutputs: Map<String, String> = emptyMap(),
        private val commandBehavior: ((String, Int) -> String?)? = null,
    ) : IDeviceAdb {
        override val displayName: String? = "fake_device"
        override val api: Int = 34
        override val serial: String = "emulator-5554"
        override val isOnline: Boolean = true

        val executedCommands = mutableListOf<String>()
        private var commandCount: Int = 0

        override fun execAdbShellCmd(cmd: String): String {
            executedCommands.add(cmd)
            commandCount += 1
            val custom = commandBehavior
            if (custom != null) {
                val customResult = custom(cmd, commandCount)
                if (customResult != null) {
                    return customResult
                }
            }
            if (cmd == "dumpsys activity activities") {
                return DEFAULT_DUMPSYS_ACTIVITY_OUTPUT
            }
            return shellOutputs[cmd].orEmpty()
        }

        override fun push(from: File, to: String): Boolean = true
        override fun pull(from: String, to: File): Boolean = true
        override fun getDefaultLaunchActivity(apkFile: File): String? = null
        override fun getArch(packageName: String): String = "ARCH_64_BIT"
        override fun getProperty(name: String): String? = null

        companion object {
            private const val DEFAULT_DUMPSYS_ACTIVITY_OUTPUT =
                "topResumedActivity=ActivityRecord{100 com.example.app/.MainActivity t10}"
        }
    }

    private class FakePlatformApi(
        private val adbByDevice: Map<IDevice, IDeviceAdb>,
    ) : IPlatformApi {
        override fun showDialog(
            title: String, content: String, okButtonText: String?,
            cancelButtonText: String?, isShowCancelButton: Boolean,
        ): Boolean = false

        override fun showChangeConfirmDialog(
            diffResult: DependencyDiffResult?, isRunLater: Boolean, logger: Logger,
        ): ConfirmResult {
            throw UnsupportedOperationException("not used")
        }

        override fun showUserAndPasswordInputDialog(
            content: String, subTitle: String?, isPassword: Boolean,
            defaultInputText: String?, title: String?,
        ): String? = null

        override fun allAvailableJavaHomes(): List<String> = emptyList()
        override fun getGradleJdkPath(project: Project, logger: Logger): String? = null
        override fun getAndroidHomePath(logger: Logger): String? = null
        override fun getIdeVersion(): String = "test"
        override fun toDeviceAdb(device: IDevice): IDeviceAdb? = adbByDevice[device]
        override fun isHasRelaunchActivityIssues(device: IDeviceAdb, logger: Logger): Boolean = false

        override fun invokeMcp(request: com.sickworm.intellij.jugg.mcp.McpJsonRpcRequest): com.sickworm.intellij.jugg.mcp.McpJsonRpcResponse {
            throw UnsupportedOperationException("not used")
        }

        override fun getInitializedProjectDirs(): List<File> = emptyList()

        override fun executeGradleCompile(autoConfirm: Boolean, useCleanAndReinstall: Boolean) {
            throw UnsupportedOperationException("not used")
        }
    }
}
