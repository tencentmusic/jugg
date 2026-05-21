package com.sickworm.intellij.jugg.gradle.compile

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RsyncAuthRetryPolicyTest {

    @Test
    fun isRetryable_shouldReturnTrue_forSshPasswordAuthFailureBeforeTransfer() {
        val output = listOf(
            "** WARNING: connection is not using a post-quantum key exchange algorithm.",
            "Permission denied, please try again.",
            "Permission denied, please try again.",
            "root@21.91.206.239: Permission denied (publickey,gssapi-keyex,gssapi-with-mic,password).",
            "rsync: connection unexpectedly closed (0 bytes received so far) [sender]",
            "rsync error: unexplained error (code 255) at io.c(232) [sender=3.4.1]",
            "(Jugg) RsyncSyncFileCommand result: 255",
        )

        assertTrue(RsyncAuthRetryPolicy.isRetryable(255, output))
    }

    @Test
    fun isRetryable_shouldReturnFalse_whenTransferAlreadyStarted() {
        val output = listOf(
            "sending incremental file list",
            "Permission denied, please try again.",
            "(Jugg) RsyncSyncFileCommand result: 255",
        )

        assertFalse(RsyncAuthRetryPolicy.isRetryable(255, output))
    }

    @Test
    fun isRetryable_shouldReturnFalse_forNonAuthRsyncFailure() {
        val output = listOf(
            "rsync: failed to set times on \"/data/remote/demo\": Operation not permitted (1)",
            "(Jugg) RsyncSyncFileCommand result: 255",
        )

        assertFalse(RsyncAuthRetryPolicy.isRetryable(255, output))
    }

    @Test
    fun isRetryable_shouldReturnFalse_whenExitCodeIsNot255() {
        val output = listOf(
            "Permission denied, please try again.",
            "(Jugg) RsyncSyncFileCommand result: 1",
        )

        assertFalse(RsyncAuthRetryPolicy.isRetryable(1, output))
    }
}
