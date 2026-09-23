package com.sickworm.intellij.jugg.hotfix;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Verifies the rootless compat import rules the host relies on: a request is applied atomically to
 * `code_cache/.overlay`, and every rejected request leaves the committed overlay untouched.
 *
 * The metadata keys and payload layout pinned here are the device-side half of the contract owned
 * by `com.sickworm.intellij.jugg.deploy.direct.RootlessCompatDeployArchive`.
 */
public class RootlessCompatDeployImporterTest {

    private static final String PACKAGE_NAME = "com.example.app";
    private static final String EXPECTED_OVERLAY_ID = "base-overlay";
    private static final String NEXT_OVERLAY_ID = "next-overlay";

    private File root;
    private File codeCacheDir;
    private File overlayDir;
    private File requestDir;

    @Before
    public void setUp() throws IOException {
        root = Files.createTempDirectory("jugg-rootless-import").toFile();
        codeCacheDir = new File(root, "code_cache");
        overlayDir = new File(codeCacheDir, ".overlay");
        assertTrue(overlayDir.mkdirs());
        requestDir = new File(root, "request");
        assertTrue(requestDir.mkdirs());
        HotfixLoader.codeCacheDir = codeCacheDir;
        HotfixLoader.overlayFilesDir = overlayDir;
    }

    @After
    public void tearDown() {
        HotfixLoader.codeCacheDir = null;
        HotfixLoader.overlayFilesDir = null;
        deleteRecursively(root);
    }

    @Test
    public void importRequest_shouldCommitDexFlagAndResourceApk() throws Exception {
        writeOverlayFile("id", EXPECTED_OVERLAY_ID);
        writeOverlayFile("Old.dex", "old");
        writeOverlayFile("base.apk/res/layout/old.xml", "old");
        stageRequest(overrides(
                "expectedOverlayId", EXPECTED_OVERLAY_ID,
                "nextOverlayId", NEXT_OVERLAY_ID,
                "isFullResourcePush", "false",
                "payloadEntries", "New.dex=dex,base.apk/resource.ap_=resource," +
                        "base.apk/.jugg_compat_deploy_enable=flag,base.apk/res/layout/old.xml=replaced"
        ));

        RootlessCompatDeployImporter.importRequest(PACKAGE_NAME, requestDir);

        assertEquals(NEXT_OVERLAY_ID, read(new File(overlayDir, "id")));
        assertEquals("dex", read(new File(overlayDir, "New.dex")));
        assertEquals("resource", read(new File(overlayDir, "base.apk/resource.ap_")));
        assertEquals("flag", read(new File(overlayDir, "base.apk/.jugg_compat_deploy_enable")));
        // Payload targets are replaced and untouched overlay files are preserved.
        assertEquals("replaced", read(new File(overlayDir, "base.apk/res/layout/old.xml")));
        assertEquals("old", read(new File(overlayDir, "Old.dex")));
        assertTrue(new File(overlayDir, "New.dex").canRead());
        assertFalse(new File(codeCacheDir, "rootless_import/" + requestDir.getName()).exists());
    }

    @Test
    public void importRequest_shouldKeepOldOverlayWhenEntryIsUnsafe() throws Exception {
        writeOverlayFile("id", EXPECTED_OVERLAY_ID);
        writeOverlayFile("Old.dex", "old");
        stageRequest(overrides(
                "expectedOverlayId", EXPECTED_OVERLAY_ID,
                "nextOverlayId", NEXT_OVERLAY_ID,
                "payloadEntries", "../escape.dex=bad,Ok.dex=ok"
        ));

        RootlessCompatDeployImporter.importRequest(PACKAGE_NAME, requestDir);

        assertUntouched();
        assertFalse(new File(codeCacheDir, "../escape.dex").exists());
    }

    @Test
    public void importRequest_shouldKeepOldOverlayOnOverlayIdMismatch() throws Exception {
        writeOverlayFile("id", "another-overlay");
        writeOverlayFile("Old.dex", "old");
        stageRequest(overrides(
                "expectedOverlayId", EXPECTED_OVERLAY_ID,
                "nextOverlayId", NEXT_OVERLAY_ID,
                "payloadEntries", "New.dex=dex"
        ));

        RootlessCompatDeployImporter.importRequest(PACKAGE_NAME, requestDir);

        assertEquals("another-overlay", read(new File(overlayDir, "id")));
        assertFalse(new File(overlayDir, "New.dex").exists());
    }

    @Test
    public void importRequest_shouldReportTheSameOkWhenTheCommittedOverlayIsImportedAgain() throws Exception {
        writeOverlayFile("id", EXPECTED_OVERLAY_ID);
        writeOverlayFile("Old.dex", "old");
        stageRequest(overrides(
                "expectedOverlayId", EXPECTED_OVERLAY_ID,
                "nextOverlayId", NEXT_OVERLAY_ID,
                "payloadEntries", "New.dex=dex"
        ));
        String okLine = "__JUGG_ROOTLESS_IMPORT__ OK " + requestDir.getName();

        try (MockedStatic<LogUtils> logs = mockStatic(LogUtils.class)) {
            RootlessCompatDeployImporter.importRequest(PACKAGE_NAME, requestDir);
            logs.verify(() -> LogUtils.i(HotfixLoader.TAG, okLine));

            // The app may start again before the host reads the result. The same request must keep
            // reporting the same terminal result instead of failing on the overlay it committed.
            RootlessCompatDeployImporter.importRequest(PACKAGE_NAME, requestDir);

            logs.verify(() -> LogUtils.i(HotfixLoader.TAG, okLine), times(2));
            assertEquals(NEXT_OVERLAY_ID, read(new File(overlayDir, "id")));
            assertEquals("dex", read(new File(overlayDir, "New.dex")));
            assertEquals("old", read(new File(overlayDir, "Old.dex")));
        }
    }

    @Test
    public void importRequest_shouldFailWhenTheOverlayIdIsNotThisRequestResult() throws Exception {
        writeOverlayFile("id", "unrelated-overlay");
        writeOverlayFile("Old.dex", "old");
        stageRequest(overrides(
                "expectedOverlayId", EXPECTED_OVERLAY_ID,
                "nextOverlayId", NEXT_OVERLAY_ID,
                "payloadEntries", "New.dex=dex"
        ));

        try (MockedStatic<LogUtils> logs = mockStatic(LogUtils.class)) {
            RootlessCompatDeployImporter.importRequest(PACKAGE_NAME, requestDir);

            logs.verify(() -> LogUtils.i(eq(HotfixLoader.TAG),
                    argThat(line -> line.startsWith("__JUGG_ROOTLESS_IMPORT__ FAILED " + requestDir.getName()))));
            assertFalse(new File(overlayDir, "New.dex").exists());
            assertEquals("old", read(new File(overlayDir, "Old.dex")));
        }
    }

    @Test
    public void importRequest_shouldKeepOldOverlayOnPayloadDigestMismatch() throws Exception {
        writeOverlayFile("id", EXPECTED_OVERLAY_ID);
        writeOverlayFile("Old.dex", "old");
        stageRequest(overrides(
                "expectedOverlayId", EXPECTED_OVERLAY_ID,
                "nextOverlayId", NEXT_OVERLAY_ID,
                "payloadSha256", repeat('0', 64),
                "payloadEntries", "New.dex=dex"
        ));

        RootlessCompatDeployImporter.importRequest(PACKAGE_NAME, requestDir);

        assertUntouched();
    }

    @Test
    public void importRequest_shouldRejectRequestOfAnotherPackage() throws Exception {
        writeOverlayFile("id", EXPECTED_OVERLAY_ID);
        writeOverlayFile("Old.dex", "old");
        stageRequest(overrides(
                "packageName", "com.example.other",
                "expectedOverlayId", EXPECTED_OVERLAY_ID,
                "nextOverlayId", NEXT_OVERLAY_ID,
                "payloadEntries", "New.dex=dex"
        ));

        RootlessCompatDeployImporter.importRequest(PACKAGE_NAME, requestDir);

        assertUntouched();
    }

    @Test
    public void importRequest_shouldRejectUnknownProtocolVersion() throws Exception {
        writeOverlayFile("id", EXPECTED_OVERLAY_ID);
        writeOverlayFile("Old.dex", "old");
        stageRequest(overrides(
                "protocolVersion", "99",
                "expectedOverlayId", EXPECTED_OVERLAY_ID,
                "nextOverlayId", NEXT_OVERLAY_ID,
                "payloadEntries", "New.dex=dex"
        ));

        RootlessCompatDeployImporter.importRequest(PACKAGE_NAME, requestDir);

        assertUntouched();
    }

    @Test
    public void importRequest_shouldCommitBaseInstallWithoutExistingOverlay() throws Exception {
        deleteRecursively(overlayDir);
        stageRequest(overrides(
                "expectedOverlayId", "",
                "nextOverlayId", NEXT_OVERLAY_ID,
                "payloadEntries", "base.apk/.jugg_compat_deploy_enable=flag"
        ));

        RootlessCompatDeployImporter.importRequest(PACKAGE_NAME, requestDir);

        assertEquals(NEXT_OVERLAY_ID, read(new File(overlayDir, "id")));
        assertEquals("flag", read(new File(overlayDir, "base.apk/.jugg_compat_deploy_enable")));
    }

    @Test
    public void importRequest_shouldRejectBaseInstallOverExistingOverlay() throws Exception {
        writeOverlayFile("id", EXPECTED_OVERLAY_ID);
        writeOverlayFile("Old.dex", "old");
        stageRequest(overrides(
                "expectedOverlayId", "",
                "nextOverlayId", NEXT_OVERLAY_ID,
                "payloadEntries", "New.dex=dex"
        ));

        RootlessCompatDeployImporter.importRequest(PACKAGE_NAME, requestDir);

        assertUntouched();
    }

    @Test
    public void importRequest_shouldKeepBaseApkEntriesOnFullResourcePush() throws Exception {
        writeOverlayFile("id", EXPECTED_OVERLAY_ID);
        writeOverlayFile("base.apk/res/layout/stale.xml", "stale");
        stageRequest(overrides(
                "expectedOverlayId", EXPECTED_OVERLAY_ID,
                "nextOverlayId", NEXT_OVERLAY_ID,
                "isFullResourcePush", "true",
                "payloadEntries", "base.apk/resource.ap_=resource"
        ));

        RootlessCompatDeployImporter.importRequest(PACKAGE_NAME, requestDir);

        assertEquals(NEXT_OVERLAY_ID, read(new File(overlayDir, "id")));
        assertEquals("stale", read(new File(overlayDir, "base.apk/res/layout/stale.xml")));
    }

    @Test
    public void importRequest_shouldRemoveNonBaseApkPayloadTargetsOnFullResourcePush() throws Exception {
        writeOverlayFile("id", EXPECTED_OVERLAY_ID);
        writeOverlayFile("Removed.dex", "stale");
        stageRequest(overrides(
                "expectedOverlayId", EXPECTED_OVERLAY_ID,
                "nextOverlayId", NEXT_OVERLAY_ID,
                "isFullResourcePush", "true",
                "payloadEntries", "Removed.dex=fresh"
        ));

        RootlessCompatDeployImporter.importRequest(PACKAGE_NAME, requestDir);

        assertEquals("fresh", read(new File(overlayDir, "Removed.dex")));
    }

    @Test
    public void pendingRootDir_shouldStayInsideTheAppExternalFilesDirectory() {
        File externalFilesDir = new File(
                "/storage/emulated/0/Android/data/com.example.app/files"
        );

        assertEquals(
                new File(externalFilesDir, "jugg/rootless-compat"),
                RootlessCompatDeployImporter.pendingRootDir(externalFilesDir)
        );
    }

    private void assertUntouched() throws IOException {
        assertEquals(EXPECTED_OVERLAY_ID, read(new File(overlayDir, "id")));
        assertEquals("old", read(new File(overlayDir, "Old.dex")));
        assertFalse(new File(overlayDir, "New.dex").exists());
    }

    /**
     * Stages a request directory using the same layout and property keys as the host archive, and
     * fills in the payload digest unless the test overrides it.
     */
    private void stageRequest(Map<String, String> overrides) throws Exception {
        File payload = new File(requestDir, "payload.zip");
        Map<String, String> entries = parseEntries(overrides.get("payloadEntries"));
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(payload))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }

        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("protocolVersion", "1");
        metadata.put("requestId", requestDir.getName());
        metadata.put("packageName", PACKAGE_NAME);
        metadata.put("expectedOverlayId", EXPECTED_OVERLAY_ID);
        metadata.put("nextOverlayId", NEXT_OVERLAY_ID);
        metadata.put("payloadSha256", sha256(payload));
        metadata.put("isFullResourcePush", "false");
        metadata.put("createdAtMillis", "1789261483352");
        overrides.forEach((key, value) -> {
            if (!"payloadEntries".equals(key)) {
                metadata.put(key, value);
            }
        });

        StringBuilder text = new StringBuilder();
        metadata.forEach((key, value) -> text.append(key).append('=').append(value).append('\n'));
        Files.write(new File(requestDir, "request.properties").toPath(),
                text.toString().getBytes(StandardCharsets.UTF_8));
        Files.write(new File(requestDir, "ready").toPath(), new byte[0]);
    }

    private static Map<String, String> overrides(String... pairs) {
        Map<String, String> overrides = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            overrides.put(pairs[i], pairs[i + 1]);
        }
        return overrides;
    }

    private static Map<String, String> parseEntries(String spec) {
        Map<String, String> entries = new LinkedHashMap<>();
        if (spec == null) {
            return entries;
        }
        for (String pair : spec.split(",")) {
            int separator = pair.indexOf('=');
            entries.put(pair.substring(0, separator), pair.substring(separator + 1));
        }
        return entries;
    }

    private void writeOverlayFile(String relativePath, String content) throws IOException {
        File file = new File(overlayDir, relativePath);
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            assertTrue(parent.mkdirs());
        }
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = Files.readAllBytes(file.toPath());
        StringBuilder builder = new StringBuilder();
        for (byte value : digest.digest(bytes)) {
            builder.append(String.format(Locale.US, "%02x", value));
        }
        return builder.toString();
    }

    private static String repeat(char value, int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            builder.append(value);
        }
        return builder.toString();
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
