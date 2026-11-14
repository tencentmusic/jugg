package com.sickworm.intellij.jugg.gradle.compile

import org.junit.Test
import kotlin.test.assertEquals

class GradleDaemonArgHelperTest {

    @Test
    fun `default resolution should return no-daemon argument`() {
        val command = "./gradlew :app:assembleDebug"
        val arg = GradleDaemonArgHelper.getDisableArgsIfEnabled(command, isNoDaemon = true)
        assertEquals(" --no-daemon", arg)
    }

    @Test
    fun `resolution with existing no-daemon flag should return empty to prevent duplicate`() {
        val command = "./gradlew :app:assembleDebug --no-daemon"
        val arg = GradleDaemonArgHelper.getDisableArgsIfEnabled(command, isNoDaemon = true)
        assertEquals("", arg)
    }

    @Test
    fun `resolution with typo no-deamon flag should return empty to prevent duplicate`() {
        val command = "./gradlew :app:assembleDebug --no-deamon"
        val arg = GradleDaemonArgHelper.getDisableArgsIfEnabled(command, isNoDaemon = true)
        assertEquals("", arg)
    }

    @Test
    fun `resolution should preserve unrelated arguments containing daemon substring`() {
        val command = "./gradlew :daemon:assembleDebug -Pdaemon=true -Pdesc=\"build daemon\""
        val arg = GradleDaemonArgHelper.getDisableArgsIfEnabled(command, isNoDaemon = true)
        assertEquals(" --no-daemon", arg)
    }

    @Test
    fun `resolution with isNoDaemon false should return empty`() {
        val command1 = "./gradlew :app:assembleDebug"
        assertEquals("", GradleDaemonArgHelper.getDisableArgsIfEnabled(command1, isNoDaemon = false))

        val command2 = "./gradlew :app:assembleDebug --daemon"
        assertEquals("", GradleDaemonArgHelper.getDisableArgsIfEnabled(command2, isNoDaemon = false))

        val command3 = "./gradlew :app:assembleDebug --no-daemon"
        assertEquals("", GradleDaemonArgHelper.getDisableArgsIfEnabled(command3, isNoDaemon = false))
    }

    @Test
    fun `resolution with existing daemon flag should append no-daemon suffix to override it`() {
        val command = "./gradlew :app:assembleDebug --daemon"
        val arg = GradleDaemonArgHelper.getDisableArgsIfEnabled(command, isNoDaemon = true)
        assertEquals(" --no-daemon", arg)
    }
}
