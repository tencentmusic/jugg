package com.sickworm.intellij.jugg.hotfix;

import android.content.Context;
import android.os.Build;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Imports a rootless compat payload staged by the host under
 * {@code <external-files>/jugg/rootless-compat/<requestId>}.
 *
 * The host cannot write {@code code_cache/.overlay} itself when ordinary shell, adb root and su are
 * all unavailable, so it only stages the payload and restarts the app. This importer runs from
 * {@link BootstrapApplication#attachBaseContext} before {@link HotfixLoader#isNeedEnableHotfix()},
 * so a successful import is loaded by the very same process start.
 *
 * Every failure keeps the committed overlay untouched and is reported once through
 * {@code __JUGG_ROOTLESS_IMPORT__ FAILED <requestId> <stage> <reason>} on the {@code jugg-agent}
 * log tag, which is the only result channel the host can read back.
 *
 * The protocol constants and the result line must stay in sync with
 * {@code com.sickworm.intellij.jugg.deploy.direct.RootlessCompatDeployArchive}.
 */
public final class RootlessCompatDeployImporter {

    private static final String TAG = HotfixLoader.TAG + "#RootlessImport";
    private static final String RESULT_MARKER = "__JUGG_ROOTLESS_IMPORT__";
    private static final String ROOT_DIR_NAME = "jugg/rootless-compat";
    private static final String PROTOCOL_VERSION = "1";
    private static final String PAYLOAD_FILE_NAME = "payload.zip";
    private static final String REQUEST_FILE_NAME = "request.properties";
    private static final String READY_FILE_NAME = "ready";
    private static final String OVERLAY_ID_FILE_NAME = "id";
    private static final String STAGING_DIR_NAME = "rootless_import";
    private static final String LEGACY_PATCH_DIR_NAME = ".ll";
    private static final String BASE_APK_PREFIX = "base.apk/";
    private static final String DEX_SUFFIX = ".dex";

    private static final String KEY_PROTOCOL_VERSION = "protocolVersion";
    private static final String KEY_REQUEST_ID = "requestId";
    private static final String KEY_PACKAGE_NAME = "packageName";
    private static final String KEY_EXPECTED_OVERLAY_ID = "expectedOverlayId";
    private static final String KEY_NEXT_OVERLAY_ID = "nextOverlayId";
    private static final String KEY_PAYLOAD_SHA256 = "payloadSha256";
    private static final String KEY_IS_FULL_RESOURCE_PUSH = "isFullResourcePush";

    private static final String SHA256_PATTERN = "[0-9a-f]{64}";

    private RootlessCompatDeployImporter() {
    }

    /**
     * Imports the pending request of this package, if any. Never throws: a failed import only keeps
     * the previous overlay and reports the reason, so the app still starts normally.
     */
    public static void importPending(Context base) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || HotfixLoader.codeCacheDir == null) {
            return;
        }
        String packageName;
        try {
            packageName = base.getPackageName();
        } catch (Throwable e) {
            return;
        }
        File externalFilesDir;
        try {
            externalFilesDir = base.getExternalFilesDir(null);
        } catch (Throwable e) {
            return;
        }
        if (externalFilesDir == null) {
            return;
        }
        File requestDir = findPendingRequest(pendingRootDir(externalFilesDir));
        if (requestDir == null) {
            return;
        }
        importRequest(packageName, requestDir);
    }

    static File pendingRootDir(File externalFilesDir) {
        return new File(externalFilesDir, ROOT_DIR_NAME);
    }

    /** Imports one staged request directory; failures are reported instead of propagated. */
    static void importRequest(String packageName, File requestDir) {
        String requestId = requestDir.getName();
        String stage = "lock";
        FileLock lock = null;
        RandomAccessFile lockFile = null;
        try {
            File lockDir = new File(HotfixLoader.codeCacheDir, STAGING_DIR_NAME);
            if (!lockDir.isDirectory() && !lockDir.mkdirs()) {
                throw new IOException("cannot create staging dir " + lockDir);
            }
            lockFile = new RandomAccessFile(new File(lockDir, ".lock"), "rw");
            FileChannel channel = lockFile.getChannel();
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                lock = null;
            }
            if (lock == null) {
                // Another app process owns the import and reports the single terminal result.
                LogUtils.i(TAG, "another process is importing " + requestId + ", skip");
                return;
            }

            stage = "metadata";
            Map<String, String> metadata = readMetadata(requestDir);
            validateMetadata(metadata, packageName, requestId);
            String expectedOverlayId = metadata.get(KEY_EXPECTED_OVERLAY_ID);
            String nextOverlayId = metadata.get(KEY_NEXT_OVERLAY_ID);
            boolean isFullResourcePush = Boolean.parseBoolean(metadata.get(KEY_IS_FULL_RESOURCE_PUSH));

            stage = "digest";
            File payload = new File(requestDir, PAYLOAD_FILE_NAME);
            verifyPayload(payload, metadata.get(KEY_PAYLOAD_SHA256));

            stage = "overlay-state";
            File overlayDir = HotfixLoader.overlayFilesDir;
            if (isOverlayCommitted(overlayDir, nextOverlayId)) {
                // The app may start again before the host reads the result, for example when the
                // previous start crashed after this very import. Report the same terminal result
                // instead of failing the overlay state check, so the host never reads one request
                // as both OK and FAILED.
                LogUtils.i(TAG, "imported already, overlay id " + nextOverlayId);
                LogUtils.i(HotfixLoader.TAG, RESULT_MARKER + " OK " + requestId);
                return;
            }
            checkOverlayState(overlayDir, expectedOverlayId);

            stage = "extract";
            File stagingDir = new File(new File(HotfixLoader.codeCacheDir, STAGING_DIR_NAME), requestId);
            deleteRecursively(stagingDir);
            extractPayload(payload, stagingDir);

            stage = "commit";
            commit(overlayDir, stagingDir, expectedOverlayId, nextOverlayId, isFullResourcePush);
            deleteRecursively(stagingDir);

            LogUtils.i(TAG, "imported " + requestId + ", overlay id " + nextOverlayId);
            LogUtils.i(HotfixLoader.TAG, RESULT_MARKER + " OK " + requestId);
        } catch (Throwable e) {
            LogUtils.i(HotfixLoader.TAG,
                    RESULT_MARKER + " FAILED " + requestId + " " + stage + " " + singleLine(e));
        } finally {
            releaseQuietly(lock);
            closeQuietly(lockFile);
        }
    }

    /** Returns the newest fully staged request of this package, or null when nothing is pending. */
    private static File findPendingRequest(File packageDir) {
        File[] children = packageDir.listFiles();
        if (children == null) {
            return null;
        }
        File newestReady = null;
        for (File child : children) {
            if (!child.isDirectory()) {
                continue;
            }
            File ready = new File(child, READY_FILE_NAME);
            if (!ready.isFile()) {
                continue;
            }
            if (newestReady == null || ready.lastModified() >= newestReady.lastModified()) {
                newestReady = ready;
            }
        }
        return newestReady == null ? null : newestReady.getParentFile();
    }

    private static Map<String, String> readMetadata(File requestDir) throws IOException {
        File metadataFile = new File(requestDir, REQUEST_FILE_NAME);
        if (!metadataFile.isFile()) {
            throw new IOException("request metadata is missing");
        }
        Map<String, String> metadata = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(metadataFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int separator = line.indexOf('=');
                if (separator <= 0) {
                    continue;
                }
                metadata.put(line.substring(0, separator), line.substring(separator + 1));
            }
        }
        return metadata;
    }

    private static void validateMetadata(Map<String, String> metadata, String packageName, String requestId)
            throws IOException {
        if (!PROTOCOL_VERSION.equals(metadata.get(KEY_PROTOCOL_VERSION))) {
            throw new IOException("unsupported protocol version " + metadata.get(KEY_PROTOCOL_VERSION));
        }
        if (!packageName.equals(metadata.get(KEY_PACKAGE_NAME))) {
            throw new IOException("request belongs to " + metadata.get(KEY_PACKAGE_NAME));
        }
        if (!requestId.equals(metadata.get(KEY_REQUEST_ID))) {
            throw new IOException("request id mismatch " + metadata.get(KEY_REQUEST_ID));
        }
        // An empty expected overlay id is valid: it marks the base install case.
        if (metadata.get(KEY_EXPECTED_OVERLAY_ID) == null) {
            throw new IOException("expected overlay id is missing");
        }
        if (isEmpty(metadata.get(KEY_NEXT_OVERLAY_ID))) {
            throw new IOException("next overlay id is missing");
        }
        String digest = metadata.get(KEY_PAYLOAD_SHA256);
        if (digest == null || !digest.matches(SHA256_PATTERN)) {
            throw new IOException("payload digest is missing or malformed");
        }
    }

    private static void verifyPayload(File payload, String expectedSha256) throws Exception {
        if (!payload.isFile()) {
            throw new IOException("payload is missing");
        }
        if (!expectedSha256.equals(sha256(payload))) {
            throw new IOException("payload digest mismatch");
        }
    }

    /**
     * True only when the committed overlay already carries this request's own next overlay id, which
     * means this exact payload has been applied. Any other committed overlay stays a state mismatch.
     */
    private static boolean isOverlayCommitted(File overlayDir, String nextOverlayId) {
        File idFile = new File(overlayDir, OVERLAY_ID_FILE_NAME);
        if (!idFile.isFile()) {
            return false;
        }
        try {
            return nextOverlayId.equals(readText(idFile).trim());
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Mirrors the Direct Overlay pre-apply guard: a base install must not find an existing overlay
     * directory, and an incremental deploy must find exactly the overlay id the host cached.
     */
    private static void checkOverlayState(File overlayDir, String expectedOverlayId) throws IOException {
        if (!overlayDir.exists()) {
            if (!expectedOverlayId.isEmpty()) {
                throw new IOException("device overlay is missing, expected " + expectedOverlayId);
            }
            return;
        }
        if (expectedOverlayId.isEmpty()) {
            throw new IOException("device overlay already exists for a base install");
        }
        File idFile = new File(overlayDir, OVERLAY_ID_FILE_NAME);
        if (!idFile.isFile()) {
            throw new IOException("device overlay id file is missing");
        }
        String actualOverlayId = readText(idFile).trim();
        if (!expectedOverlayId.equals(actualOverlayId)) {
            throw new IOException("device overlay id is " + actualOverlayId + ", expected " + expectedOverlayId);
        }
    }

    /** Extracts the payload into private staging so a rejected entry never touches the overlay. */
    private static void extractPayload(File payload, File stagingDir) throws IOException {
        if (!stagingDir.mkdirs() && !stagingDir.isDirectory()) {
            throw new IOException("cannot create staging dir " + stagingDir);
        }
        String stagingRoot = stagingDir.getCanonicalPath() + File.separator;
        Set<String> extracted = new HashSet<>();
        byte[] buffer = new byte[8192];
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(payload)))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (!isSafeEntryName(name)) {
                    throw new IOException("unsafe overlay entry " + name);
                }
                if (!extracted.add(name)) {
                    throw new IOException("duplicate overlay entry " + name);
                }
                File target = new File(stagingDir, name);
                if (!target.getCanonicalPath().startsWith(stagingRoot)) {
                    throw new IOException("overlay entry escapes the staging dir " + name);
                }
                File parent = target.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("cannot create " + parent);
                }
                try (FileOutputStream output = new FileOutputStream(target)) {
                    int length;
                    while ((length = zip.read(buffer)) > 0) {
                        output.write(buffer, 0, length);
                    }
                }
            }
        }
    }

    private static boolean isSafeEntryName(String name) {
        if (name == null || name.isEmpty() || name.startsWith("/") || name.contains("\\")) {
            return false;
        }
        for (String segment : name.split("/")) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Applies the staged files to the committed overlay directory. The overlay id file is written
     * last, so an interrupted commit leaves an overlay the host can still detect as mismatched.
     */
    private static void commit(
            File overlayDir,
            File stagingDir,
            String expectedOverlayId,
            String nextOverlayId,
            boolean isFullResourcePush
    ) throws IOException {
        if (!overlayDir.isDirectory() && !overlayDir.mkdirs()) {
            throw new IOException("cannot create overlay dir " + overlayDir);
        }
        deleteRecursively(new File(HotfixLoader.codeCacheDir, LEGACY_PATCH_DIR_NAME));
        if (!expectedOverlayId.isEmpty()) {
            if (!new File(overlayDir, OVERLAY_ID_FILE_NAME).delete()) {
                throw new IOException("cannot invalidate the committed overlay id");
            }
            removePayloadTargets(overlayDir, stagingDir, isFullResourcePush);
        }
        moveStagedFiles(stagingDir, overlayDir);
        markOverlayDexReadOnly(overlayDir);
        writeText(new File(overlayDir, OVERLAY_ID_FILE_NAME), nextOverlayId);
    }

    private static void removePayloadTargets(File overlayDir, File stagingDir, boolean isFullResourcePush) {
        for (String path : listRelativePaths(stagingDir)) {
            if (isFullResourcePush && path.startsWith(BASE_APK_PREFIX)) {
                continue;
            }
            //noinspection ResultOfMethodCallIgnored
            new File(overlayDir, path).delete();
        }
    }

    private static void moveStagedFiles(File stagingDir, File overlayDir) throws IOException {
        for (String path : listRelativePaths(stagingDir)) {
            File source = new File(stagingDir, path);
            File target = new File(overlayDir, path);
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("cannot create " + parent);
            }
            if (target.exists() && !target.delete()) {
                throw new IOException("cannot replace " + target);
            }
            if (!source.renameTo(target)) {
                copyFile(source, target);
                //noinspection ResultOfMethodCallIgnored
                source.delete();
            }
        }
    }

    private static List<String> listRelativePaths(File root) {
        List<String> paths = new ArrayList<>();
        collectRelativePaths(root, "", paths);
        return paths;
    }

    private static void collectRelativePaths(File dir, String prefix, List<String> paths) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            String path = prefix.isEmpty() ? child.getName() : prefix + "/" + child.getName();
            if (child.isDirectory()) {
                collectRelativePaths(child, path, paths);
            } else {
                paths.add(path);
            }
        }
    }

    private static void markOverlayDexReadOnly(File overlayDir) throws IOException {
        for (String path : listRelativePaths(overlayDir)) {
            if (!path.endsWith(DEX_SUFFIX)) {
                continue;
            }
            File dex = new File(overlayDir, path);
            if (dex.isFile() && !dex.setReadOnly()) {
                throw new IOException("cannot mark " + path + " read-only");
            }
        }
    }

    private static void copyFile(File source, File target) throws IOException {
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int length;
            while ((length = input.read(buffer)) > 0) {
                output.write(buffer, 0, length);
            }
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[8192];
        try (FileInputStream input = new FileInputStream(file)) {
            int length;
            while ((length = input.read(buffer)) > 0) {
                digest.update(buffer, 0, length);
            }
        }
        StringBuilder builder = new StringBuilder();
        for (byte value : digest.digest()) {
            builder.append(String.format(Locale.US, "%02x", value));
        }
        return builder.toString();
    }

    private static String readText(File file) throws IOException {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            char[] buffer = new char[1024];
            int length;
            while ((length = reader.read(buffer)) > 0) {
                builder.append(buffer, 0, length);
            }
        }
        return builder.toString();
    }

    /** Mirrors the Direct Overlay writer: the overlay id is stored without a trailing newline. */
    private static void writeText(File file, String content) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(content.getBytes("UTF-8"));
        }
    }

    private static void deleteRecursively(File file) {
        if (!file.exists()) {
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

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }

    private static String singleLine(Throwable throwable) {
        String message = throwable.getMessage();
        String text = message == null || message.isEmpty() ? throwable.getClass().getName() : message;
        return text.replace('\n', ' ').replace('\r', ' ');
    }

    private static void releaseQuietly(FileLock lock) {
        if (lock == null) {
            return;
        }
        try {
            lock.release();
        } catch (Throwable ignored) {
            // best effort
        }
    }

    private static void closeQuietly(RandomAccessFile file) {
        if (file == null) {
            return;
        }
        try {
            file.close();
        } catch (Throwable ignored) {
            // best effort
        }
    }
}
