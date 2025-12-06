package com.sickworm.intellij.jugg.hotfix;

import static android.os.Build.VERSION.SDK_INT;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;

public class FileUtil {

    private static final String FILES_OPERATION = HotfixLoader.TAG + "#File";
    private static final String PERMISSION_WRITE_EXTERNAL_STORAGE = "android.permission.WRITE_EXTERNAL_STORAGE";

    private static String getFileMD5(File file){
        if (!file.isFile()) {
            return "";
        }
        MessageDigest digest = null;
        FileInputStream in = null;
        byte buffer[] = new byte[8192];
        int len;
        try {
            digest =MessageDigest.getInstance("MD5");
            in = new FileInputStream(file);
            while ((len = in.read(buffer)) != -1) {
                digest.update(buffer, 0, len);
            }
            BigInteger bigInt = new BigInteger(1, digest.digest());
            return bigInt.toString(16);
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        } finally {
            try {
                in.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    static boolean copyFile(File src, File destDir) {

        Log.i(FILES_OPERATION, "copyFile: src = " + src);
        Log.i(FILES_OPERATION, "copyFile: destDir = " + destDir);

        if (src == null || !src.exists() || destDir == null || !destDir.exists()) {
            Log.i(FILES_OPERATION, "copyFile: params error");
            return false;
        }

        File dest = new File(destDir, src.getName());

        Log.i(FILES_OPERATION, "copyFile: dest = " + dest);

        if (dest.exists()) {

            Log.i(FILES_OPERATION, "copyFile: dest already exist");

            String oldMd5 = getFileMD5(src);
            String dstMd5 = getFileMD5(dest);

            Log.i(FILES_OPERATION, "copyFile: oldMd5 = " + oldMd5);
            Log.i(FILES_OPERATION, "copyFile: dstMd5 = " + dstMd5);

            if (oldMd5 == null || dstMd5 == null || !oldMd5.equals(dstMd5)) {
                boolean ret = dest.delete();
                if (!ret) {
                    Log.i(FILES_OPERATION, "copyFile: error while deleting existing file");
                    return false;
                } else {
                    Log.i(FILES_OPERATION, "copyFile: deleting existing file success");
                }
            } else {
                Log.i(FILES_OPERATION, "copyFile: dest same as src, skip copy");
                return true;
            }
        }

        try {
            boolean ret = dest.createNewFile();
            if (!ret) {
                Log.i(FILES_OPERATION, "copyFile: error while create new file");
                return false;
            }
        } catch (IOException e) {
            Log.i(FILES_OPERATION, "copyFile: error while create new file: " + e.getMessage());
            return false;
        }

        FileChannel srcChannel = null;
        FileChannel dstChannel = null;

        try {
            srcChannel = new FileInputStream(src).getChannel();
            dstChannel = new FileOutputStream(dest).getChannel();
            srcChannel.transferTo(0, srcChannel.size(), dstChannel);
            Log.i(FILES_OPERATION, "copyFile: file copy success");
            return true;
        } catch (Exception e) {
            Log.i(FILES_OPERATION, "copyFile: file copy failed: " + e.getMessage());
            e.printStackTrace();
            return false;
        } finally {
            try {
                srcChannel.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            try {
                dstChannel.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    static void ensurePermissionGranted(Context context) {
        if (SDK_INT >= Build.VERSION_CODES.M) {
            if (context.checkSelfPermission(PERMISSION_WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                LogUtils.i(FILES_OPERATION, "ensurePermissionGranted: permission NOT granted ! " + PERMISSION_WRITE_EXTERNAL_STORAGE);
                throw new RuntimeException(FILES_OPERATION + " : No file permissions ! [应用没有被授予文件读写权限，请到设置页面手动开启]");
            }
        }
    }

    static boolean isExternalStoragePermissionGranted(Context context) {
        if (SDK_INT >= Build.VERSION_CODES.M) {
            return context.checkSelfPermission(PERMISSION_WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        } else {
            return true;
        }
    }

    static void copyFromAssetsToFile(Context context, String fileName, File cacheTo) throws IOException {
        try (InputStream inputStream = context.getAssets().open(fileName)) {
            if (cacheTo.exists()) {
                if (!cacheTo.delete()) {
                    throw new IOException("Could not delete file " + cacheTo.getAbsolutePath());
                }
            }
            if (!cacheTo.createNewFile()) {
                throw new IOException("Could not create file " + cacheTo.getAbsolutePath());
            }
            try (FileOutputStream outputStream = new FileOutputStream(cacheTo)) {
                byte[] buf = new byte[1024];
                int len;
                while ((len = inputStream.read(buf)) > 0) {
                    outputStream.write(buf, 0, len);
                }
            }
            if (!cacheTo.setReadOnly()) {
                LogUtils.w("FileUtil", "Could not set file " + cacheTo.getAbsolutePath() + " to read-only");
            }
        }
    }

    static void deleteRecursively(File dir) {
        if (dir.isDirectory()) {
            String[] children = dir.list();
            if (children == null) {
                return;
            }
            for (String child : children) {
                deleteRecursively(new File(dir, child));
            }
        }
        if (!dir.delete()) {
            throw new RuntimeException("deleteRecursively failed: " + dir);
        }
    }
}
