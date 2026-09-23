package com.sickworm.intellij.jugg.deploy.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * L1 owner for the rootless compat pending archive contract shared with the app-side importer.
 */
class RootlessCompatDeployArchiveTest {

    @Test
    fun `payload zip keeps direct overlay entry paths`() {
        val payload = RootlessCompatDeployArchive.buildPayload(request())

        assertEquals(
            listOf("com.example.Foo.dex", "base.apk/.jugg_compat_deploy_enable", "base.apk/resource.ap_"),
            readZipEntries(payload),
        )
        assertEquals(
            listOf(byteArrayOf(1, 2), byteArrayOf(3), byteArrayOf(4, 5, 6)).map { it.toList() },
            readZipContents(payload).map { it.toList() },
        )
    }

    @Test
    fun `payload zip rejects traversal and duplicate entries`() {
        listOf("../escape.dex", "/absolute.dex", "base.apk\\windows.dex", "..").forEach { unsafe ->
            val error = runCatching {
                RootlessCompatDeployArchive.buildPayload(
                    request(files = listOf(DirectOverlayWriteFile(unsafe, byteArrayOf(1)))),
                )
            }.exceptionOrNull()

            assertTrue("expected rejection for $unsafe, got $error", error is IllegalArgumentException)
        }

        val duplicate = runCatching {
            RootlessCompatDeployArchive.buildPayload(
                request(
                    files = listOf(
                        DirectOverlayWriteFile("dup.dex", byteArrayOf(1)),
                        DirectOverlayWriteFile("dup.dex", byteArrayOf(2)),
                    ),
                ),
            )
        }.exceptionOrNull()

        assertTrue("expected rejection for duplicate entry, got $duplicate", duplicate is IllegalArgumentException)
    }

    @Test
    fun `metadata carries protocol fields and the payload digest`() {
        val payload = RootlessCompatDeployArchive.buildPayload(request())

        assertEquals(
            """
            protocolVersion=1
            requestId=1789261483352-ab12cd34
            packageName=com.example.app
            expectedOverlayId=base-overlay
            nextOverlayId=next-overlay
            payloadSha256=${RootlessCompatDeployArchive.sha256(payload)}
            isFullResourcePush=false
            createdAtMillis=1789261483352
            """.trimIndent() + "\n",
            RootlessCompatDeployArchive.buildMetadata(
                request = request(),
                requestId = "1789261483352-ab12cd34",
                payloadSha256 = RootlessCompatDeployArchive.sha256(payload),
                createdAtMillis = 1789261483352L,
            ),
        )
    }

    @Test
    fun `request id is one safe path segment`() {
        val requestId = RootlessCompatDeployArchive.newRequestId(1789261483352L)

        assertTrue(requestId, requestId.startsWith("1789261483352-"))
        assertTrue(requestId, requestId.matches(Regex("[A-Za-z0-9-]+")))
    }

    @Test
    fun `request dir is package and request scoped`() {
        assertEquals(
            "/sdcard/Android/data/com.example.app/files/jugg/rootless-compat/1789261483352-ab12cd34",
            RootlessCompatDeployArchive.requestDir("com.example.app", "1789261483352-ab12cd34"),
        )
    }

    @Test
    fun `import result accepts one terminal line for the matching request`() {
        val output = listOf(
            "01-01 00:00:00.000  1000  1000 I jugg-agent: unrelated",
            "${RootlessCompatDeployArchive.IMPORT_RESULT_MARKER} OK 1789261483352-ab12cd34",
        ).joinToString("\n")

        val result = RootlessCompatDeployArchive.parseImportResult(output, "1789261483352-ab12cd34")

        assertNotNull(result)
        assertEquals(true, result!!.success)
        assertEquals("", result.stage)
        assertEquals("", result.detail)
    }

    @Test
    fun `import result keeps the failing stage and single line reason`() {
        val output = "${RootlessCompatDeployArchive.IMPORT_RESULT_MARKER} FAILED 1789261483352-ab12cd34 " +
            "digest payload digest mismatch\n"

        val result = RootlessCompatDeployArchive.parseImportResult(output, "1789261483352-ab12cd34")

        assertNotNull(result)
        assertEquals(false, result!!.success)
        assertEquals("digest", result.stage)
        assertEquals("payload digest mismatch", result.detail)
    }

    @Test
    fun `import result accepts repeated agreeing lines and rejects conflicting ones`() {
        val requestId = "1789261483352-ab12cd34"
        val okLine = "${RootlessCompatDeployArchive.IMPORT_RESULT_MARKER} OK $requestId"

        // The app process may start again while the host is still waiting for the result.
        assertNotNull(
            RootlessCompatDeployArchive.parseImportResult("$okLine\n$okLine\n", requestId),
        )
        assertNull(
            RootlessCompatDeployArchive.parseImportResult(
                "$okLine\n${RootlessCompatDeployArchive.IMPORT_RESULT_MARKER} FAILED $requestId digest mismatch\n",
                requestId,
            ),
        )
    }

    @Test
    fun `import result ignores other requests and unrelated output`() {
        val otherRequest = "${RootlessCompatDeployArchive.IMPORT_RESULT_MARKER} OK 1789261483000-ffffffff"

        assertNull(
            RootlessCompatDeployArchive.parseImportResult(otherRequest, "1789261483352-ab12cd34"),
        )
        assertNull(RootlessCompatDeployArchive.parseImportResult("nothing here", "1789261483352-ab12cd34"))
    }

    @Test
    fun `sha256 matches the known digest`() {
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            RootlessCompatDeployArchive.sha256("hello".toByteArray()),
        )
    }

    private fun request(
        files: List<DirectOverlayWriteFile> = listOf(
            DirectOverlayWriteFile("com.example.Foo.dex", byteArrayOf(1, 2)),
            DirectOverlayWriteFile("base.apk/.jugg_compat_deploy_enable", byteArrayOf(3)),
            DirectOverlayWriteFile("base.apk/resource.ap_", byteArrayOf(4, 5, 6)),
        ),
    ) = DirectOverlayWriteRequest(
        packageName = "com.example.app",
        expectedOverlayId = "base-overlay",
        overlayId = "next-overlay",
        files = files,
        isFullResourcePush = false,
    )

    private fun readZipEntries(payload: ByteArray): List<String> {
        val entries = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(payload)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries += entry.name
                entry = zip.nextEntry
            }
        }
        return entries
    }

    private fun readZipContents(payload: ByteArray): List<ByteArray> {
        val contents = mutableListOf<ByteArray>()
        ZipInputStream(ByteArrayInputStream(payload)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                contents += zip.readBytes()
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return contents
    }
}
